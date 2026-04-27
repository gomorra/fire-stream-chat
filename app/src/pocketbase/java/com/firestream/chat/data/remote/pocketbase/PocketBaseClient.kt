// region: AGENT-NOTE
// PocketBase HTTP client. Wraps OkHttp with auth-header injection, in-memory
// session state (StateFlow + DataStore as persistence side-effect), and
// 401 → auto-refresh → re-bridge → retry. Reads parse JSON via `org.json` to
// match the existing house style (MessageRepositoryImpl uses JSONObject the
// same way; `org.json` ships in android.jar, no new prod dep).
//
// Don't put here:
//   - SSE realtime — that's PocketBaseRealtime.kt next door.
//   - Firebase ID-token re-mint logic itself — that's PocketBaseAuthSource via
//     the [PbTokenBridge] callback. The cycle is broken with `Lazy<>`.
//   - Synchronous DataStore reads outside the singleton init — `setSession`
//     and `clearSession` update the in-memory StateFlow first and persist
//     async via @ApplicationScope so they outlive a ViewModel onCleared
//     (see feedback_datastore_scope_fence). Per-request token reads are pure
//     `sessionToken.value` (no per-call DataStore IO).
// endregion
package com.firestream.chat.data.remote.pocketbase

import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import android.content.Context
import com.firestream.chat.BuildConfig
import com.firestream.chat.di.ApplicationScope
import dagger.Lazy
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import org.json.JSONObject
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

private val Context.pocketBaseDataStore by preferencesDataStore(name = "pocketbase_session")

/**
 * Thin OkHttp wrapper for PocketBase REST. Carries a single shared client +
 * in-memory session state so every `*Source` impl in this flavor authenticates
 * uniformly without per-request DataStore IO.
 *
 * Step 5 layered on the auth flow: [authedRequest] retries once on 401 by
 * (a) calling PB's `auth-refresh` if it has a session token, and if that
 * fails (b) asking [PbTokenBridge] to re-bridge a fresh Firebase ID token.
 * Concurrent 401s coalesce on a single refresh via [refreshMutex].
 */
@Singleton
class PocketBaseClient @Inject constructor(
    @ApplicationContext private val context: Context,
    @ApplicationScope private val appScope: CoroutineScope,
    private val tokenBridge: Lazy<PbTokenBridge>
) {
    val baseUrl: String = BuildConfig.POCKETBASE_URL

    private val tokenKey = stringPreferencesKey("pb_session_token")
    private val userIdKey = stringPreferencesKey("pb_user_id")

    private val _sessionToken = MutableStateFlow<String?>(null)
    private val _pbUserId = MutableStateFlow<String?>(null)

    /** Latest PB session token, or null if signed out. */
    val sessionToken: StateFlow<String?> = _sessionToken.asStateFlow()

    /** Latest PB user record id, or null if signed out. */
    val pbUserId: StateFlow<String?> = _pbUserId.asStateFlow()

    private val refreshMutex = Mutex()

    private val httpClient: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        // SSE needs a long read timeout per stream; PocketBaseRealtime uses
        // [streamingClient] below. Keep this one tuned for plain REST.
        .build()

    /**
     * OkHttp client tuned for streaming responses (SSE). Shares the underlying
     * connection pool / dispatcher with [httpClient] — only the read timeout
     * differs. Built once and reused.
     */
    val streamingClient: OkHttpClient by lazy {
        httpClient.newBuilder()
            .readTimeout(0, TimeUnit.MILLISECONDS)
            .build()
    }

    init {
        // One-shot DataStore read at construction. PocketBaseClient is a
        // @Singleton so this happens once, on first injection. The blocking
        // read is the price of synchronous `sessionToken.value` reads on the
        // hot REST path — without it, the first few requests after process
        // start would race the DataStore emission.
        runBlocking {
            val prefs = context.pocketBaseDataStore.data.first()
            _sessionToken.value = prefs[tokenKey]
            _pbUserId.value = prefs[userIdKey]
        }
    }

    /**
     * Updates the in-memory session and persists asynchronously. Subsequent
     * synchronous reads of [sessionToken]/[pbUserId] reflect the new value
     * immediately; the DataStore write happens on `appScope` and outlives any
     * ViewModel that triggered it.
     */
    fun setSession(token: String, userId: String) {
        _sessionToken.value = token
        _pbUserId.value = userId
        appScope.launch {
            context.pocketBaseDataStore.edit { prefs ->
                prefs[tokenKey] = token
                prefs[userIdKey] = userId
            }
        }
    }

    fun clearSession() {
        _sessionToken.value = null
        _pbUserId.value = null
        appScope.launch {
            context.pocketBaseDataStore.edit { prefs ->
                prefs.remove(tokenKey)
                prefs.remove(userIdKey)
            }
        }
    }

    suspend fun get(path: String): JSONObject = authedRequest {
        Request.Builder().url("$baseUrl$path").get()
    }

    suspend fun post(path: String, body: JSONObject): JSONObject = authedRequest {
        Request.Builder().url("$baseUrl$path").post(body.toRequestBody())
    }

    suspend fun patch(path: String, body: JSONObject): JSONObject = authedRequest {
        Request.Builder().url("$baseUrl$path").patch(body.toRequestBody())
    }

    suspend fun delete(path: String): JSONObject = authedRequest {
        Request.Builder().url("$baseUrl$path").delete()
    }

    /**
     * Sends an unauthenticated POST. Used by the bridge endpoint itself, which
     * doesn't take a PB session — it mints one. Skipping the Authorization
     * header avoids confusing PB during sign-in when an old session may still
     * sit in DataStore.
     */
    suspend fun unauthedPost(path: String, body: JSONObject): JSONObject =
        withContext(Dispatchers.IO) {
            val request = Request.Builder()
                .url("$baseUrl$path")
                .post(body.toRequestBody())
                .build()
            httpClient.newCall(request).execute().use(::parseJson)
        }

    /**
     * Builds the request, attaches the in-memory session token (if any),
     * executes on IO, parses JSON. On 401, runs [recoverFromUnauthorized] and
     * retries once. Other non-2xx statuses bubble up as [PocketBaseHttpException].
     */
    private suspend fun authedRequest(buildRequest: () -> Request.Builder): JSONObject =
        withContext(Dispatchers.IO) {
            try {
                executeOnce(buildRequest())
            } catch (e: PocketBaseHttpException) {
                if (e.statusCode != 401) throw e
                if (!recoverFromUnauthorized(e.body)) throw e
                executeOnce(buildRequest())
            }
        }

    private fun executeOnce(builder: Request.Builder): JSONObject {
        val token = _sessionToken.value
        if (!token.isNullOrEmpty()) {
            builder.header("Authorization", token)
        }
        return httpClient.newCall(builder.build()).execute().use(::parseJson)
    }

    /**
     * Two-step recovery, single-flight via [refreshMutex] so a burst of
     * concurrent 401s only triggers one refresh round-trip:
     *   1. If we have a session token, hit `/api/collections/users/auth-refresh`.
     *      PB extends the session for ~30 days as long as the token isn't fully
     *      expired and the user record still exists.
     *   2. Otherwise (or if step 1 fails), delegate to [PbTokenBridge] which
     *      re-bridges a fresh Firebase ID token. If even that fails the caller
     *      sees the original 401 and can route to login.
     *
     * Callers acquiring the lock after another caller refreshed see the new
     * token and skip the work — checked via [staleToken].
     */
    @Suppress("UNUSED_PARAMETER")
    private suspend fun recoverFromUnauthorized(body401: String): Boolean {
        val staleToken = _sessionToken.value
        return refreshMutex.withLock {
            if (_sessionToken.value != staleToken) return@withLock true
            val current = _sessionToken.value
            if (!current.isNullOrEmpty() && tryAuthRefresh(current)) {
                return@withLock true
            }
            runCatching { tokenBridge.get().refreshSession() }.getOrDefault(false)
        }
    }

    private fun tryAuthRefresh(token: String): Boolean = runCatching {
        val request = Request.Builder()
            .url("$baseUrl/api/collections/users/auth-refresh")
            .header("Authorization", token)
            .post("".toRequestBody(JSON_MEDIA))
            .build()
        httpClient.newCall(request).execute().use { response ->
            if (!response.isSuccessful) return@use false
            val body = response.body?.string().orEmpty()
            if (body.isBlank()) return@use false
            val json = JSONObject(body)
            val newToken = json.optString("token").takeIf { it.isNotEmpty() } ?: return@use false
            val record = json.optJSONObject("record") ?: return@use false
            val userId = record.optString("id").takeIf { it.isNotEmpty() } ?: return@use false
            setSession(newToken, userId)
            true
        }
    }.getOrDefault(false)

    private fun parseJson(response: Response): JSONObject {
        val raw = response.body?.string().orEmpty()
        if (!response.isSuccessful) {
            throw PocketBaseHttpException(response.code, raw)
        }
        return if (raw.isBlank()) JSONObject() else JSONObject(raw)
    }

    companion object {
        private val JSON_MEDIA = "application/json; charset=utf-8".toMediaType()

        private fun JSONObject.toRequestBody(): RequestBody =
            this.toString().toRequestBody(JSON_MEDIA)
    }
}

class PocketBaseHttpException(val statusCode: Int, val body: String) :
    RuntimeException("PocketBase HTTP $statusCode: $body")
