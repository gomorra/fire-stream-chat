package com.firestream.chat.data.remote

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import javax.inject.Inject
import javax.inject.Singleton

data class LinkPreview(
    val url: String,
    val title: String?,
    val description: String?,
    val imageUrl: String?
)

@Singleton
class LinkPreviewSource @Inject constructor(
    private val okHttpClient: OkHttpClient
) {
    private val cache = mutableMapOf<String, LinkPreview>()

    /** Detects the first URL in [text], returns null if none found. */
    fun extractUrl(text: String): String? =
        URL_REGEX.find(text)?.value

    suspend fun fetchPreview(url: String): LinkPreview? {
        cache[url]?.let { return it }
        return withContext(Dispatchers.IO) {
            try {
                val request = Request.Builder()
                    .url(url)
                    .header("User-Agent", "Mozilla/5.0 (compatible; FireStreamBot/1.0)")
                    .build()
                val response = okHttpClient.newCall(request).execute()
                val html = response.use { it.body?.string() } ?: return@withContext null
                val preview = parseOgTags(url, html)
                cache[url] = preview
                preview
            } catch (_: Exception) {
                null
            }
        }
    }

    private fun parseOgTags(url: String, html: String): LinkPreview {
        val title = OG_TITLE.find(html)?.groupValues?.getOrNull(1)?.trim()
            ?: TITLE_TAG.find(html)?.groupValues?.getOrNull(1)?.trim()
        val description = OG_DESC.find(html)?.groupValues?.getOrNull(1)?.trim()
        val imageUrl = OG_IMAGE.find(html)?.groupValues?.getOrNull(1)?.trim()
        return LinkPreview(url = url, title = title, description = description, imageUrl = imageUrl)
    }

    companion object {
        private val URL_REGEX = Regex("""https?://[^\s]+""")
        private val OG_TITLE = Regex("""<meta[^>]+property=["']og:title["'][^>]+content=["']([^"']+)["']""", RegexOption.IGNORE_CASE)
        private val OG_DESC = Regex("""<meta[^>]+property=["']og:description["'][^>]+content=["']([^"']+)["']""", RegexOption.IGNORE_CASE)
        private val OG_IMAGE = Regex("""<meta[^>]+property=["']og:image["'][^>]+content=["']([^"']+)["']""", RegexOption.IGNORE_CASE)
        private val TITLE_TAG = Regex("""<title[^>]*>([^<]+)</title>""", RegexOption.IGNORE_CASE)
    }
}
