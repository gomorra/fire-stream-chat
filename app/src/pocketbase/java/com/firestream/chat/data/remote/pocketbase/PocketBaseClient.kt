// region: AGENT-NOTE
// PocketBase HTTP client. Wraps OkHttp with auth-header injection, session-token
// storage in DataStore, and 401 → auto-refresh → re-bridge → retry. Reads parse
// JSON via `org.json` to match the existing house style (MessageRepositoryImpl
// + FirestoreMessageSource already use it; `org.json` ships in android.jar, no
// new prod dep).
//
// Don't put here:
//   - SSE realtime — that's PocketBaseRealtime.kt next door.
//   - Firebase ID-token re-mint logic itself — that's PocketBaseAuthSource via
//     the [PbTokenBridge] callback. The cycle is broken with `Lazy<>`.
//   - Token writes from non-application scope — must use @ApplicationScope so
//     they outlive a ViewModel onCleared (see feedback_datastore_scope_fence).
// endregion
package com.firestream.chat.data.remote.pocketbase

import androidx.datastore.preferences.core.Preferences
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
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
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
 * session-token storage so every `*Source` impl in this flavor can authenticate
 * uniformly.
 *
 * Step 5 layered on the auth flow: [authedRequest] now retries once on 401 by
 * (a) calling PB's `auth-refresh` if it has a session token, and if that fails
 * (b) asking [PbTokenBridge] to re-bridge a fresh Firebase ID token.
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

    private val httpClient: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        // SSE needs a long read timeout per stream; PocketBaseRealtime overrides
        // with its own client. Keep this one for plain REST.
        .build()

    val sessionTokenFlow: Flow<String?> = context.pocketBaseDataStore.data
        .map { prefs: Preferences -> prefs[tokenKey] }

    val pbUserIdFlow: Flow<String?> = context.pocketBaseDataStore.data
        .map { prefs: Preferences -> prefs[userIdKey] }

    fun setSession(token: String, userId: String) {
        appScope.launch {
            context.pocketBaseDataStore.edit { prefs ->
                prefs[tokenKey] = token
                prefs[userIdKey] = userId
            }
        }
    }

    fun clearSession() {
        appScope.launch {
            context.pocketBaseDataStore.edit { prefs ->
                prefs.remove(tokenKey)
                prefs.remove(userIdKey)
            }
        }
    }

    /** OkHttp client tuned for streaming responses (SSE). Exposed for [PocketBaseRealtime]. */
    fun streamingClient(): OkHttpClient = httpClient.newBuilder()
        .readTimeout(0, TimeUnit.MILLISECONDS)
        .build()

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
     * Builds the request, attaches the session token (if any) as
     * `Authorization: <token>`, executes synchronously on IO, and parses the
     * body as JSON. On 401, retries once after running [recoverFromUnauthorized].
     *
     * The retry uses a *fresh* token read from DataStore — the recovery path
     * may have just persisted a new one.
     */
    private suspend fun authedRequest(buildRequest: () -> Request.Builder): JSONObject =
        withContext(Dispatchers.IO) {
            val firstResponse = executeOnce(buildRequest())
            if (firstResponse !is AuthedResult.Unauthorized) {
                return@withContext firstResponse.unwrap()
            }
            // 401 path: try to recover, retry once, fail loudly if still 401.
            if (!recoverFromUnauthorized()) {
                throw PocketBaseHttpException(401, firstResponse.body)
            }
            executeOnce(buildRequest()).unwrap()
        }

    private suspend fun executeOnce(builder: Request.Builder): AuthedResult {
        val token = sessionTokenFlow.first()
        if (!token.isNullOrEmpty()) {
            builder.header("Authorization", token)
        }
        return httpClient.newCall(builder.build()).execute().use { response ->
            when {
                response.isSuccessful -> AuthedResult.Ok(parseBody(response))
                response.code == 401 -> AuthedResult.Unauthorized(response.body?.string().orEmpty())
                else -> AuthedResult.Error(PocketBaseHttpException(response.code, response.body?.string().orEmpty()))
            }
        }
    }

    /**
     * Two-step recovery:
     *   1. If we have a session token, hit `/api/collections/users/auth-refresh`.
     *      PB extends the session for ~30 days as long as the token isn't fully
     *      expired and the user record still exists.
     *   2. Otherwise (or if step 1 also returns 401), delegate to
     *      [PbTokenBridge.refreshSession] which re-bridges a fresh Firebase
     *      ID token. If even that fails the caller sees the original 401 and
     *      can route to login.
     */
    private suspend fun recoverFromUnauthorized(): Boolean {
        val token = sessionTokenFlow.first()
        if (!token.isNullOrEmpty() && tryAuthRefresh(token)) {
            return true
        }
        return runCatching { tokenBridge.get().refreshSession() }.getOrDefault(false)
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

    private fun parseBody(response: Response): JSONObject {
        val raw = response.body?.string().orEmpty()
        return if (raw.isBlank()) JSONObject() else JSONObject(raw)
    }

    private fun parseJson(response: Response): JSONObject {
        val raw = response.body?.string().orEmpty()
        if (!response.isSuccessful) {
            throw PocketBaseHttpException(response.code, raw)
        }
        return if (raw.isBlank()) JSONObject() else JSONObject(raw)
    }

    private sealed interface AuthedResult {
        fun unwrap(): JSONObject
        data class Ok(val body: JSONObject) : AuthedResult { override fun unwrap() = body }
        data class Unauthorized(val body: String) : AuthedResult {
            override fun unwrap() = throw PocketBaseHttpException(401, body)
        }
        data class Error(val cause: PocketBaseHttpException) : AuthedResult {
            override fun unwrap(): JSONObject = throw cause
        }
    }

    companion object {
        private val JSON_MEDIA = "application/json; charset=utf-8".toMediaType()

        private fun JSONObject.toRequestBody(): RequestBody =
            this.toString().toRequestBody(JSON_MEDIA)
    }
}

class PocketBaseHttpException(val statusCode: Int, val body: String) :
    RuntimeException("PocketBase HTTP $statusCode: $body")
