package com.firestream.chat.data.remote

import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
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
import org.junit.Test

class LinkPreviewSourceTest {

    private val okHttpClient = mockk<OkHttpClient>()
    private val webPagePreviewCapture = mockk<WebPagePreviewCapture>()

    private val source = LinkPreviewSource(okHttpClient, webPagePreviewCapture)

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
        every { okHttpClient.newCall(any()) } returns call
    }

    // ── extractUrl ────────────────────────────────────────────────────────

    @Test
    fun `extractUrl finds URL in text`() {
        assertEquals(
            "https://example.com/foo",
            source.extractUrl("check this out https://example.com/foo right here"),
        )
    }

    @Test
    fun `extractUrl returns null for text without URL`() {
        assertNull(source.extractUrl("just some words, no link"))
    }

    @Test
    fun `extractUrl finds first URL when multiple present`() {
        assertEquals(
            "https://first.example.com",
            source.extractUrl("https://first.example.com and https://second.example.com"),
        )
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
    fun `fetchPreview does not cache empty results so transient failures retry`() = runTest {
        stubHtml("<html></html>")
        coEvery { webPagePreviewCapture.capture(any()) } returns null

        source.fetchPreview("https://example.com/transient")
        source.fetchPreview("https://example.com/transient")

        // Both attempts hit the network because the first returned null
        // — caching it would have permanently masked a recoverable error.
        coVerify(exactly = 2) { okHttpClient.newCall(any()) }
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

        val first = source.fetchPreview("https://example.com/retry")
        assertNull(first)

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
}
