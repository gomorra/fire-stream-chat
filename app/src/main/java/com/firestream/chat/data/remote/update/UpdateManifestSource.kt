package com.firestream.chat.data.remote.update

import com.firestream.chat.BuildConfig
import com.firestream.chat.domain.model.AppUpdate
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.CacheControl
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Fetches `latest-{flavor}.json` from `BuildConfig.UPDATE_MANIFEST_URL` and
 * parses it into an [AppUpdate]. The URL points at GitHub's "latest release"
 * alias so the response always reflects the most recent published release.
 */
@Singleton
class UpdateManifestSource @Inject constructor(
    private val okHttpClient: OkHttpClient
) {

    suspend fun fetchLatest(): AppUpdate = withContext(Dispatchers.IO) {
        val request = Request.Builder()
            .url(BuildConfig.UPDATE_MANIFEST_URL)
            .cacheControl(CacheControl.FORCE_NETWORK)
            .build()

        okHttpClient.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                throw IOException("Manifest fetch failed: HTTP ${response.code}")
            }
            val body = response.body?.string()
                ?: throw IOException("Manifest response had no body")
            parse(body)
        }
    }

    internal fun parse(body: String): AppUpdate {
        val json = JSONObject(body)
        return AppUpdate(
            versionCode = json.getInt("versionCode"),
            versionName = json.getString("versionName"),
            apkUrl = json.getString("apkUrl"),
            sha256 = json.getString("sha256"),
            minSupportedVersionCode = json.optInt("minSupportedVersionCode", 1),
            releaseNotes = json.optString("releaseNotes", ""),
            publishedAt = json.optString("publishedAt", ""),
            mandatory = json.optBoolean("mandatory", false)
        )
    }
}
