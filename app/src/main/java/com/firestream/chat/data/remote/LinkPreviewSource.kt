package com.firestream.chat.data.remote

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
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
    private val okHttpClient: OkHttpClient,
    private val webPagePreviewCapture: WebPagePreviewCapture
) {
    private val cache = mutableMapOf<String, LinkPreview>()

    /** Detects the first URL in [text], returns null if none found. */
    fun extractUrl(text: String): String? =
        URL_REGEX.find(text)?.value

    suspend fun fetchPreview(url: String): LinkPreview? {
        cache[url]?.let { return it }

        val parsed = withContext(Dispatchers.IO) {
            try {
                val request = Request.Builder()
                    .url(url)
                    .header("User-Agent", "Mozilla/5.0 (compatible; FireStreamBot/1.0)")
                    .build()
                val html = okHttpClient.newCall(request).execute().use { it.body?.string() }
                if (html != null) parseHtmlMeta(url, html) else null
            } catch (_: Exception) {
                null
            }
        }

        // Fallback: if the page exposed no image via any meta/link tag, render the
        // page in an offscreen WebView and use the top-of-page screenshot as the
        // preview image.
        val imageUrl = parsed?.imageUrl ?: webPagePreviewCapture.capture(url)

        val preview = LinkPreview(
            url = url,
            title = parsed?.title,
            description = parsed?.description,
            imageUrl = imageUrl
        )
        // Only cache if we got something useful — a fully empty preview shouldn't
        // block a later retry (e.g. on a transient network error).
        if (preview.title != null || preview.description != null || preview.imageUrl != null) {
            cache[url] = preview
            return preview
        }
        return null
    }

    private data class ParsedMeta(
        val title: String?,
        val description: String?,
        val imageUrl: String?
    )

    private fun parseHtmlMeta(pageUrl: String, html: String): ParsedMeta {
        val title = OG_TITLE.find(html)?.groupValues?.getOrNull(1)?.trim()
            ?: TWITTER_TITLE.find(html)?.groupValues?.getOrNull(1)?.trim()
            ?: TITLE_TAG.find(html)?.groupValues?.getOrNull(1)?.trim()

        val description = OG_DESC.find(html)?.groupValues?.getOrNull(1)?.trim()
            ?: TWITTER_DESC.find(html)?.groupValues?.getOrNull(1)?.trim()
            ?: META_DESC.find(html)?.groupValues?.getOrNull(1)?.trim()

        val rawImage = OG_IMAGE.find(html)?.groupValues?.getOrNull(1)?.trim()
            ?: TWITTER_IMAGE.find(html)?.groupValues?.getOrNull(1)?.trim()
            ?: APPLE_TOUCH_ICON.find(html)?.groupValues?.getOrNull(1)?.trim()
            ?: LINK_ICON.find(html)?.groupValues?.getOrNull(1)?.trim()

        val imageUrl = rawImage?.let { resolveUrl(pageUrl, it) }
        return ParsedMeta(title = title, description = description, imageUrl = imageUrl)
    }

    // Resolves a possibly-relative image reference against the page's URL:
    //   "//cdn.example.com/a.png"  → "https://cdn.example.com/a.png"
    //   "/img/a.png"               → "https://example.com/img/a.png"
    //   "a.png"                    → "https://example.com/path/a.png"
    //   "https://…"                → unchanged
    private fun resolveUrl(base: String, ref: String): String? {
        return try {
            val baseHttp = base.toHttpUrlOrNull() ?: return ref.takeIf { it.startsWith("http://") || it.startsWith("https://") }
            when {
                ref.startsWith("http://") || ref.startsWith("https://") -> ref
                ref.startsWith("//") -> "${baseHttp.scheme}:$ref"
                else -> baseHttp.resolve(ref)?.toString()
            }
        } catch (_: Exception) {
            null
        }
    }

    companion object {
        private val URL_REGEX = Regex("""https?://[^\s]+""")

        private val OG_TITLE = metaRegex("property", "og:title")
        private val OG_DESC = metaRegex("property", "og:description")
        private val OG_IMAGE = metaRegex("property", "og:image")

        private val TWITTER_TITLE = metaRegex("name", "twitter:title")
        private val TWITTER_DESC = metaRegex("name", "twitter:description")
        private val TWITTER_IMAGE = metaRegex("name", "twitter:image")

        private val META_DESC = metaRegex("name", "description")

        private val TITLE_TAG = Regex(
            """<title[^>]*>([^<]+)</title>""",
            RegexOption.IGNORE_CASE
        )
        // Grabs the href of the first <link rel="apple-touch-icon"> (any size).
        private val APPLE_TOUCH_ICON = Regex(
            """<link[^>]+rel=["'][^"']*apple-touch-icon[^"']*["'][^>]*href=["']([^"']+)["']""",
            RegexOption.IGNORE_CASE
        )
        // Fallback to <link rel="icon"> (favicons). Large ones (>= 128px) look OK.
        private val LINK_ICON = Regex(
            """<link[^>]+rel=["'](?:shortcut )?icon["'][^>]*href=["']([^"']+)["']""",
            RegexOption.IGNORE_CASE
        )

        // Matches <meta {attr}="{value}" ... content="...">. Most sites emit
        // meta tags in this order; sites that reverse it will miss here but
        // will still fall through to the WebView screenshot.
        private fun metaRegex(attr: String, value: String): Regex = Regex(
            """<meta[^>]+$attr=["']$value["'][^>]+content=["']([^"']+)["']""",
            RegexOption.IGNORE_CASE
        )
    }
}
