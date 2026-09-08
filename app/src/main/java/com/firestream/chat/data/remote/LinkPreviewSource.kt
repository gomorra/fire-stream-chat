package com.firestream.chat.data.remote

import androidx.annotation.VisibleForTesting
import com.firestream.chat.data.util.SingleFlight
import com.firestream.chat.domain.util.MessageUrls
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okio.ByteString.Companion.encodeUtf8
import java.util.concurrent.ConcurrentHashMap
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
    private val cache = ConcurrentHashMap<String, LinkPreview>()

    /**
     * URLs that produced nothing, with the timestamp of the attempt. Without this a
     * dead link retries its full fetch — including the ~20 s offscreen WebView capture —
     * every time it is asked for. Entries expire after [FAILURE_COOLDOWN_MS] so a
     * transient network error still recovers.
     */
    private val failures = ConcurrentHashMap<String, Long>()

    /** Concurrent askers for one URL share a single fetch rather than racing. */
    private val singleFlight = SingleFlight<String, LinkPreview?>()

    @VisibleForTesting
    internal var nowMs: () -> Long = System::currentTimeMillis

    /** Detects the first URL in [text], returns null if none found. */
    fun extractUrl(text: String): String? = MessageUrls.extractUrl(text)

    suspend fun fetchPreview(url: String): LinkPreview? {
        cache[url]?.let { return it }
        failures[url]?.let { failedAt ->
            if (nowMs() - failedAt < FAILURE_COOLDOWN_MS) return null
            failures.remove(url)
        }
        return singleFlight.run(url) {
            val preview = loadPreview(url)
            if (preview != null) cache[url] = preview else failures[url] = nowMs()
            preview
        }
    }

    private suspend fun loadPreview(url: String): LinkPreview? {
        val parsed = withContext(Dispatchers.IO) {
            try {
                val request = Request.Builder()
                    .url(url)
                    // A real browser UA, not a bot string: many sites (Google properties
                    // included) serve a stripped consent/interstitial page to unknown
                    // agents, which carries no og: tags at all.
                    .header("User-Agent", BROWSER_UA)
                    .header("Accept", "text/html,application/xhtml+xml")
                    .header("Accept-Language", "en-US,en;q=0.9")
                    .build()
                okHttpClient.newCall(request).execute().use { response ->
                    readHtmlHead(response)?.let { parseHtmlMeta(url, it) }
                }
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
        // A fully empty preview is nothing to show — report it as a failure so the
        // cooldown applies instead of retrying on the next emission.
        return preview.takeIf {
            it.title != null || it.description != null || it.imageUrl != null
        }
    }

    /**
     * Reads the response only as far as the end of `<head>`, and never past
     * [MAX_HTML_BYTES].
     *
     * Pulling a multi-megabyte page in full just to regex its head is pure waste — it
     * costs bandwidth, an allocation the size of the page, and ten regex sweeps over
     * all of it. Everything we parse lives in the first few KB, so Okio scans for the
     * terminator incrementally and stops there; the byte cap is the fallback for a
     * page with no `</head` in range (an unclosed head, or an uppercase `</HEAD`).
     */
    private fun readHtmlHead(response: Response): String? {
        if (!response.isSuccessful) return null
        val body = response.body ?: return null
        val contentType = body.contentType()
        if (contentType != null && contentType.subtype.lowercase() !in HTML_SUBTYPES) return null

        val source = body.source()
        // Okio has no byte-capped ByteString search, so grow the read instead of
        // asking for the whole cap up front: a typical page ends its head inside the
        // first probe and never pulls the rest.
        var searchedTo = 0L
        var probe = INITIAL_PROBE_BYTES
        while (true) {
            source.request(probe)
            val from = (searchedTo - HEAD_CLOSE.size + 1).coerceAtLeast(0)
            val headEnd = source.buffer.indexOf(HEAD_CLOSE, from)
            if (headEnd >= 0) return source.readUtf8(headEnd)

            val buffered = source.buffer.size
            if (buffered < probe || probe >= MAX_HTML_BYTES) {
                val available = minOf(buffered, MAX_HTML_BYTES)
                return if (available == 0L) null else source.readUtf8(available)
            }
            searchedTo = buffered
            probe = (probe * 4).coerceAtMost(MAX_HTML_BYTES)
        }
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

        // We deliberately skip <link rel="icon"> — it's typically a 16–32 px
        // favicon, which Coil upscales into a blurry square that users
        // perceive as an empty preview. Better to fall through to the
        // WebView screenshot fallback, which produces a real page thumbnail.
        val rawImage = OG_IMAGE.find(html)?.groupValues?.getOrNull(1)?.trim()
            ?: TWITTER_IMAGE.find(html)?.groupValues?.getOrNull(1)?.trim()
            ?: APPLE_TOUCH_ICON.find(html)?.groupValues?.getOrNull(1)?.trim()

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
        private const val MAX_HTML_BYTES = 256L * 1024
        private const val INITIAL_PROBE_BYTES = 8L * 1024
        private val HEAD_CLOSE = "</head".encodeUtf8()
        private val HTML_SUBTYPES = setOf("html", "xhtml+xml", "plain")
        private const val BROWSER_UA =
            "Mozilla/5.0 (Linux; Android 14; Pixel 8) AppleWebKit/537.36 " +
                "(KHTML, like Gecko) Chrome/122.0.0.0 Mobile Safari/537.36"

        @VisibleForTesting
        internal const val FAILURE_COOLDOWN_MS = 10L * 60 * 1000

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

        // Matches <meta {attr}="{value}" ... content="...">. Most sites emit
        // meta tags in this order; sites that reverse it will miss here but
        // will still fall through to the WebView screenshot.
        private fun metaRegex(attr: String, value: String): Regex = Regex(
            """<meta[^>]+$attr=["']$value["'][^>]+content=["']([^"']+)["']""",
            RegexOption.IGNORE_CASE
        )
    }
}
