// region: AGENT-NOTE
// PocketBase HTTP client. Wraps OkHttp with auth-header injection and
// session-token storage in DataStore. Reads parse JSON via `org.json` to match
// the existing house style (MessageRepositoryImpl + FirestoreMessageSource
// already use it; `org.json` ships in android.jar, no new prod dep).
//
// Don't put here:
//   - SSE realtime — that's PocketBaseRealtime.kt next door.
//   - 401 retry / re-bridge with Firebase ID token — deferred to step 5
//     (auth bridge); needs PocketBaseAuthSource to know how to re-mint.
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
 * Step 4 (current) installs the wrapper; the real auth bridge that obtains the
 * token lives in step 5's `PocketBaseAuthSource`.
 */
@Singleton
class PocketBaseClient @Inject constructor(
    @ApplicationContext private val context: Context,
    @ApplicationScope private val appScope: CoroutineScope
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
     * Builds the request, attaches the session token (if any) as
     * `Authorization: <token>`, executes synchronously on IO, and parses the
     * body as JSON. PocketBase returns `{}` for 204-style success on writes;
     * callers that don't need the body can ignore the return value.
     *
     * 401 retry path is deferred to step 5 — it needs `PocketBaseAuthSource`
     * to re-mint via the Firebase bridge.
     */
    private suspend fun authedRequest(buildRequest: () -> Request.Builder): JSONObject =
        withContext(Dispatchers.IO) {
            val token = sessionTokenFlow.first()
            val builder = buildRequest()
            if (!token.isNullOrEmpty()) {
                builder.header("Authorization", token)
            }
            httpClient.newCall(builder.build()).execute().use { response ->
                parseJson(response)
            }
        }

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
