package com.firestream.chat.data.remote

import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import okhttp3.Call
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LinkPreviewSourceTest {

    private val okHttpClient = mockk<OkHttpClient>()
    private val webPagePreviewCapture = mockk<WebPagePreviewCapture>()

    private val source = LinkPreviewSource(okHttpClient, webPagePreviewCapture)

    /** Every request the source issued, so a test can assert how it identified itself. */
    private val sentRequests = mutableListOf<Request>()

    private fun stubHtml(html: String, url: String = "https://example.com/page") {
        val response = Response.Builder()
            .request(Request.Builder().url(url).build())
            .protocol(Protocol.HTTP_1_1)
            .code(200)
            .message("OK")
            .body(html.toResponseBody("text/html".toMediaType()))
            .build()
        val call = mockk<Call>()
        every { call.execute() } returns response
        every { okHttpClient.newCall(capture(sentRequests)) } returns call
    }

    // ── extractUrl ────────────────────────────────────────────────────────
    // Detection itself is MessageUrls' job and is covered by MessageUrlsTest;
    // this only pins that fetchPreview's companion delegate still reaches it.

    @Test
    fun `extractUrl delegates to the shared detector`() {
        assertEquals(
            "https://example.com/foo",
            source.extractUrl("check this out https://example.com/foo."),
        )
        assertNull(source.extractUrl("just some words, no link"))
    }

    // ── fetchPreview meta tag fallback chain ──────────────────────────────

    @Test
    fun `fetchPreview uses og image when present`() = runTest {
        stubHtml("""
            <html><head>
                <meta property="og:title" content="OG Title" />
                <meta property="og:description" content="OG Desc" />
                <meta property="og:image" content="https://cdn.example.com/og.png" />
            </head></html>
        """.trimIndent())

        val preview = source.fetchPreview("https://example.com/page")

        assertNotNull(preview)
        assertEquals("OG Title", preview!!.title)
        assertEquals("OG Desc", preview.description)
        assertEquals("https://cdn.example.com/og.png", preview.imageUrl)
        coVerify(exactly = 0) { webPagePreviewCapture.capture(any()) }
    }

    @Test
    fun `fetchPreview falls back to twitter image when no og image`() = runTest {
        stubHtml("""
            <html><head>
                <meta property="og:title" content="Title" />
                <meta name="twitter:image" content="https://cdn.example.com/twitter.png" />
            </head></html>
        """.trimIndent())

        val preview = source.fetchPreview("https://example.com/page2")

        assertEquals("https://cdn.example.com/twitter.png", preview!!.imageUrl)
    }

    @Test
    fun `fetchPreview falls back to apple-touch-icon when no og or twitter image`() = runTest {
        stubHtml("""
            <html><head>
                <title>Page Title</title>
                <link rel="apple-touch-icon" sizes="180x180" href="/icons/touch.png" />
            </head></html>
        """.trimIndent())

        val preview = source.fetchPreview("https://example.com/page3")

        // Resolved against the page URL — relative paths become absolute.
        assertEquals("https://example.com/icons/touch.png", preview!!.imageUrl)
        assertEquals("Page Title", preview.title)
    }

    @Test
    fun `fetchPreview falls back to WebView screenshot when no meta image at all`() = runTest {
        stubHtml("""
            <html><head>
                <title>No Image Page</title>
                <meta name="description" content="A page with text only" />
            </head></html>
        """.trimIndent())
        coEvery {
            webPagePreviewCapture.capture("https://example.com/none")
        } returns "/cache/screenshots/abc.png"

        val preview = source.fetchPreview("https://example.com/none")

        assertEquals("/cache/screenshots/abc.png", preview!!.imageUrl)
        assertEquals("No Image Page", preview.title)
        coVerify(exactly = 1) { webPagePreviewCapture.capture("https://example.com/none") }
    }

    @Test
    fun `fetchPreview returns null when fallback also yields nothing`() = runTest {
        stubHtml("<html><head></head><body></body></html>")
        coEvery { webPagePreviewCapture.capture(any()) } returns null

        val preview = source.fetchPreview("https://example.com/empty")

        // No title, description, or image of any kind → no useful preview to show.
        assertNull(preview)
    }

    // ── caching ───────────────────────────────────────────────────────────

    @Test
    fun `fetchPreview caches successful results and skips network on second call`() = runTest {
        stubHtml("""
            <html><head>
                <meta property="og:title" content="Cached" />
                <meta property="og:image" content="https://cdn.example.com/c.png" />
            </head></html>
        """.trimIndent())

        source.fetchPreview("https://example.com/cached")
        source.fetchPreview("https://example.com/cached")

        // Network was called exactly once for the same URL — second
        // call short-circuits at the cache lookup at the top of fetchPreview.
        coVerify(exactly = 1) { okHttpClient.newCall(any()) }
    }

    @Test
    fun `fetchPreview suppresses a retry inside the failure cooldown`() = runTest {
        stubHtml("<html></html>")
        coEvery { webPagePreviewCapture.capture(any()) } returns null

        assertNull(source.fetchPreview("https://example.com/transient"))
        assertNull(source.fetchPreview("https://example.com/transient"))

        // The second attempt short-circuits. The message flow re-emits on every
        // status write, and re-running a dead link's full fetch — HTTP plus the
        // ~20 s WebView capture — on each emission is what made chat lag.
        coVerify(exactly = 1) { okHttpClient.newCall(any()) }
    }

    @Test
    fun `fetchPreview retries once the failure cooldown expires`() = runTest {
        stubHtml("<html></html>")
        coEvery { webPagePreviewCapture.capture(any()) } returns null
        var now = 0L
        source.nowMs = { now }

        assertNull(source.fetchPreview("https://example.com/transient"))
        now += LinkPreviewSource.FAILURE_COOLDOWN_MS + 1
        assertNull(source.fetchPreview("https://example.com/transient"))

        // Cooldown, not a permanent block — a network blip still recovers.
        coVerify(exactly = 2) { okHttpClient.newCall(any()) }
    }

    @Test
    fun `concurrent fetches for one url issue a single request`() = runBlocking {
        stubHtml("""<html><head><meta property="og:title" content="Shared" /></head></html>""")
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        coEvery { webPagePreviewCapture.capture(any()) } coAnswers {
            entered.complete(Unit)
            release.await()
            null
        }

        val first = async { source.fetchPreview("https://example.com/race") }
        entered.await()
        val second = async { source.fetchPreview("https://example.com/race") }
        release.complete(Unit)

        assertEquals(first.await(), second.await())
        // Second caller joins the in-flight fetch instead of starting its own —
        // without this, every re-emission stacked another capture on the first.
        coVerify(exactly = 1) { okHttpClient.newCall(any()) }
    }

    // ── relative URL resolution ───────────────────────────────────────────

    @Test
    fun `fetchPreview resolves protocol-relative og image against page scheme`() = runTest {
        stubHtml(
            """<html><head><meta property="og:image" content="//cdn.example.com/img.png" /></head></html>""",
            url = "https://news.example.com/article",
        )

        val preview = source.fetchPreview("https://news.example.com/article")

        assertEquals("https://cdn.example.com/img.png", preview!!.imageUrl)
    }

    @Test
    fun `fetchPreview leaves absolute https image untouched`() = runTest {
        stubHtml(
            """<html><head><meta property="og:image" content="https://other.example.com/x.jpg" /></head></html>""",
        )

        val preview = source.fetchPreview("https://example.com/page-abs")

        assertEquals("https://other.example.com/x.jpg", preview!!.imageUrl)
    }

    @Test
    fun `fetchPreview tolerates network exception and returns null`() = runTest {
        val call = mockk<Call>()
        every { call.execute() } throws java.io.IOException("network down")
        every { okHttpClient.newCall(any()) } returns call
        coEvery { webPagePreviewCapture.capture(any()) } returns null

        val preview = source.fetchPreview("https://example.com/network-error")

        assertNull(preview)
    }

    @Test
    fun `fetchPreview survives exception path then succeeds on retry`() = runTest {
        // First call: throws. Second call: returns valid HTML.
        val callError = mockk<Call>()
        every { callError.execute() } throws java.io.IOException("flaky")
        val callOk = mockk<Call>()
        val response = Response.Builder()
            .request(Request.Builder().url("https://example.com/retry").build())
            .protocol(Protocol.HTTP_1_1)
            .code(200)
            .message("OK")
            .body(
                """<html><head><meta property="og:image" content="https://cdn/x.png"/></head></html>"""
                    .toResponseBody("text/html".toMediaType()),
            )
            .build()
        every { callOk.execute() } returns response

        val newCallSequence = mutableListOf(callError, callOk)
        every { okHttpClient.newCall(any()) } answers { newCallSequence.removeAt(0) }
        coEvery { webPagePreviewCapture.capture(any()) } returns null

        var now = 0L
        source.nowMs = { now }

        val first = source.fetchPreview("https://example.com/retry")
        assertNull(first)

        now += LinkPreviewSource.FAILURE_COOLDOWN_MS + 1
        val second = source.fetchPreview("https://example.com/retry")
        assertNotNull(second)
        assertEquals("https://cdn/x.png", second!!.imageUrl)
    }

    @Test
    fun `fetchPreview falls through to WebView capture even when body is empty`() = runTest {
        // Regression guard for the screenshot fallback chain (commits 2862445 → 1b85356 → 0d570f1):
        // meta-parsing producing nothing must not short-circuit before asking WebPagePreviewCapture.
        stubHtml("<html><head></head></html>")
        coEvery { webPagePreviewCapture.capture(any()) } returns "/tmp/p.png"

        val preview = source.fetchPreview("https://example.com/screenshot-only")

        assertNotNull(preview)
        assertEquals("/tmp/p.png", preview!!.imageUrl)
        assertNull(preview.title)
        coVerify(exactly = 1) { webPagePreviewCapture.capture("https://example.com/screenshot-only") }
    }

    @Test
    fun `fetchPreview parses meta tags with reversed attribute order`() = runTest {
        stubHtml(
            """
            <html><head>
                <meta content="Restaurant Bagdad · Stuttgart" property="og:title">
                <meta content="https://cdn.example.com/photo.png" property="og:image">
                <meta content="Fine dining" property="og:description">
            </head></html>
            """.trimIndent(),
            url = "https://example.com/reversed"
        )

        val preview = source.fetchPreview("https://example.com/reversed")

        assertNotNull(preview)
        assertEquals("Restaurant Bagdad · Stuttgart", preview!!.title)
        assertEquals("https://cdn.example.com/photo.png", preview.imageUrl)
        assertEquals("Fine dining", preview.description)
        coVerify(exactly = 0) { webPagePreviewCapture.capture(any()) }
    }

    @Test
    fun `fetchPreview parses itemprop microdata`() = runTest {
        stubHtml(
            """
            <html><head>
                <meta content="Item Name" itemprop="name">
                <meta content="Item Description" itemprop="description">
                <meta content="https://cdn.example.com/item.png" itemprop="image">
            </head></html>
            """.trimIndent(),
            url = "https://example.com/microdata"
        )

        val preview = source.fetchPreview("https://example.com/microdata")

        assertNotNull(preview)
        assertEquals("Item Name", preview!!.title)
        assertEquals("Item Description", preview.description)
        assertEquals("https://cdn.example.com/item.png", preview.imageUrl)
    }

    @Test
    fun `fetchPreview unescapes HTML entities in title and description`() = runTest {
        stubHtml(
            """
            <html><head>
                <meta property="og:title" content="Rock &amp; Roll &#39;Special&#39; &quot;Live&quot;" />
                <meta property="og:description" content="A &lt; B &amp; C &gt; D" />
                <meta property="og:image" content="https://cdn.example.com/art.png" />
            </head></html>
            """.trimIndent(),
            url = "https://example.com/entities"
        )

        val preview = source.fetchPreview("https://example.com/entities")

        assertNotNull(preview)
        assertEquals("Rock & Roll 'Special' \"Live\"", preview!!.title)
        assertEquals("A < B & C > D", preview.description)
    }

    @Test
    fun `fetchPreview extracts place name from canonical maps URL when title is generic`() = runTest {
        val mapsUrl = "https://www.google.com/maps/place/Brandenburg+Gate/@52.5162746,13.3777041,17z"
        stubHtml(
            """
            <html><head>
                <title>Google Maps</title>
                <meta content="Google Maps" property="og:title">
            </head></html>
            """.trimIndent(),
            url = mapsUrl
        )

        val preview = source.fetchPreview(mapsUrl)

        assertNotNull(preview)
        assertEquals("Brandenburg Gate", preview!!.title)
        assertNotNull(preview.imageUrl)
        coVerify(exactly = 0) { webPagePreviewCapture.capture(any()) }
    }

    // ── how the fetch identifies itself ───────────────────────────────────

    @Test
    fun `fetchPreview identifies itself as a social crawler`() = runTest {
        stubHtml(
            """<html><head><meta property="og:title" content="Titled"></head></html>""",
            url = "https://example.com/ua"
        )
        coEvery { webPagePreviewCapture.capture(any()) } returns null

        source.fetchPreview("https://example.com/ua")

        // A browser UA is what made Google properties answer with a consent
        // interstitial carrying no og: tags; a crawler UA is served the real metadata.
        val userAgent = sentRequests.single().header("User-Agent")
        assertNotNull(userAgent)
        assertTrue("expected a crawler UA, got $userAgent", userAgent!!.startsWith("WhatsApp/"))
    }

    @Test
    fun `fetchPreview retries as a browser when the crawler UA is refused`() = runTest {
        val url = "https://example.com/bot-fight"
        val refused = Response.Builder()
            .request(Request.Builder().url(url).build())
            .protocol(Protocol.HTTP_1_1)
            .code(403)
            .message("Forbidden")
            .body("".toResponseBody("text/html".toMediaType()))
            .build()
        val served = Response.Builder()
            .request(Request.Builder().url(url).build())
            .protocol(Protocol.HTTP_1_1)
            .code(200)
            .message("OK")
            .body(
                """<html><head><meta property="og:title" content="Behind the wall"></head></html>"""
                    .toResponseBody("text/html".toMediaType())
            )
            .build()
        var attempt = 0
        every { okHttpClient.newCall(capture(sentRequests)) } answers {
            val response = if (attempt++ == 0) refused else served
            mockk<Call>().also { every { it.execute() } returns response }
        }
        coEvery { webPagePreviewCapture.capture(any()) } returns null

        val preview = source.fetchPreview(url)

        assertNotNull(preview)
        assertEquals("Behind the wall", preview!!.title)
        assertEquals(2, sentRequests.size)
        assertTrue(sentRequests[0].header("User-Agent")!!.startsWith("WhatsApp/"))
        assertTrue(sentRequests[1].header("User-Agent")!!.startsWith("Mozilla/"))
    }

    @Test
    fun `fetchPreview does not retry an ordinary not-found`() = runTest {
        val url = "https://example.com/gone"
        val missing = Response.Builder()
            .request(Request.Builder().url(url).build())
            .protocol(Protocol.HTTP_1_1)
            .code(404)
            .message("Not Found")
            .body("".toResponseBody("text/html".toMediaType()))
            .build()
        val call = mockk<Call>()
        every { call.execute() } returns missing
        every { okHttpClient.newCall(capture(sentRequests)) } returns call
        coEvery { webPagePreviewCapture.capture(any()) } returns null

        assertNull(source.fetchPreview(url))
        assertEquals(1, sentRequests.size)
    }
}
