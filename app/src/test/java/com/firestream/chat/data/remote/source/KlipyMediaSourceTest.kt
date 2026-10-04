package com.firestream.chat.data.remote.source

import com.firestream.chat.domain.model.OnlineMediaKind
import kotlinx.coroutines.test.runTest
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.SocketPolicy
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.IOException

/**
 * Klipy's API against a local server: which url each call asks for, what an
 * answer becomes, and that no error carries the key.
 */
class KlipyMediaSourceTest {

    private val server = MockWebServer()
    private lateinit var source: KlipyMediaSource

    @Before
    fun setUp() {
        server.start()
        source = KlipyMediaSource(OkHttpClient(), KEY, server.url("/"))
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    private fun file(host: String, name: String, vararg formats: String) = formats.joinToString(",", "{", "}") { format ->
        """"$format": {"url": "https://$host/ii/$name.$format", "width": 320, "height": 240, "size": 1000}"""
    }

    private fun item(slug: String, files: String) =
        """{"id": 7, "slug": "$slug", "title": "Title of $slug", "type": "gif", "file": $files}"""

    private fun standardFiles(name: String) =
        """{"md": ${file("static.klipy.com", "$name-md", "gif", "webp")}, "sm": ${file("static1.klipy.com", "$name-sm", "gif", "webp")}}"""

    private fun page(items: List<String>, currentPage: Int = 1, hasNext: Boolean = false) =
        """{"result": true, "data": {"data": [${items.joinToString(",")}], "current_page": $currentPage, "per_page": 24, "has_next": $hasNext}}"""

    private fun enqueuePage(vararg items: String, currentPage: Int = 1, hasNext: Boolean = false) {
        server.enqueue(MockResponse().setBody(page(items.toList(), currentPage, hasNext)))
    }

    // ── the four endpoints ──────────────────────────────────────────────────

    @Test
    fun `each call asks its own endpoint, with the key as a path segment`() = runTest {
        repeat(4) { enqueuePage() }

        source.search(OnlineMediaKind.GIF, "cat", 1, CUSTOMER)
        source.trending(OnlineMediaKind.GIF, 1, CUSTOMER)
        source.search(OnlineMediaKind.STICKER, "cat", 1, CUSTOMER)
        source.trending(OnlineMediaKind.STICKER, 1, CUSTOMER)

        val paths = List(4) { server.takeRequest().requestUrl!!.encodedPath }
        assertEquals(
            listOf(
                "/api/v1/$KEY/gifs/search",
                "/api/v1/$KEY/gifs/trending",
                "/api/v1/$KEY/stickers/search",
                "/api/v1/$KEY/stickers/trending",
            ),
            paths,
        )
    }

    @Test
    fun `a search carries the query, the page, the page size and the customer id`() = runTest {
        enqueuePage(currentPage = 3, hasNext = true)

        val page = source.search(OnlineMediaKind.GIF, "grumpy cat & co", 3, CUSTOMER)

        val url = server.takeRequest().requestUrl!!
        assertEquals("grumpy cat & co", url.queryParameter("q"))
        assertEquals("3", url.queryParameter("page"))
        assertEquals("24", url.queryParameter("per_page"))
        assertEquals(CUSTOMER, url.queryParameter("customer_id"))
        assertEquals(3, page.page)
        assertTrue(page.hasNext)
    }

    @Test
    fun `trending carries no query, and a page below one is asked as the first`() = runTest {
        enqueuePage()

        val page = source.trending(OnlineMediaKind.STICKER, 0, CUSTOMER)

        val url = server.takeRequest().requestUrl!!
        assertNull(url.queryParameter("q"))
        assertEquals("1", url.queryParameter("page"))
        assertFalse(page.hasNext)
    }

    // The shared client has a disk cache, and the url holds the key.
    @Test
    fun `a request asks for its answer not to be stored`() = runTest {
        enqueuePage()

        source.trending(OnlineMediaKind.GIF, 1, CUSTOMER)

        assertEquals("no-store", server.takeRequest().getHeader("Cache-Control"))
    }

    // ── what an answer becomes ──────────────────────────────────────────────

    @Test
    fun `items keep Klipy's order, with the small file as the preview and the medium one to send, webp first`() = runTest {
        enqueuePage(item("zebra", standardFiles("zebra")), item("apple", standardFiles("apple")), item("mango", standardFiles("mango")))

        val page = source.search(OnlineMediaKind.STICKER, "fruit", 1, CUSTOMER)

        assertEquals(listOf("zebra", "apple", "mango"), page.items.map { it.slug })
        val first = page.items.first()
        assertEquals(OnlineMediaKind.STICKER, first.kind)
        assertEquals("Title of zebra", first.title)
        assertEquals("https://static1.klipy.com/ii/zebra-sm.webp", first.preview.url)
        assertEquals("https://static.klipy.com/ii/zebra-md.webp", first.send.url)
        assertEquals("image/webp", first.send.mimeType)
        assertEquals(320, first.send.width)
        assertEquals(240, first.send.height)
    }

    @Test
    fun `an item without a webp file is offered as a gif`() = runTest {
        val files = """{"md": ${file("static.klipy.com", "a-md", "gif", "mp4")}, "sm": ${file("static.klipy.com", "a-sm", "gif")}}"""
        enqueuePage(item("a", files))

        val media = source.trending(OnlineMediaKind.GIF, 1, CUSTOMER).items.single()

        assertEquals("https://static.klipy.com/ii/a-md.gif", media.send.url)
        assertEquals("image/gif", media.send.mimeType)
        assertEquals("image/gif", media.preview.mimeType)
    }

    @Test
    fun `an item whose files are on a foreign host is left out, and the rest keep their order`() = runTest {
        val foreign = """{"md": ${file("evil.example", "x-md", "webp")}, "sm": ${file("evil.example", "x-sm", "webp")}}"""
        val plainHttp = """{"md": {"webp": {"url": "http://static.klipy.com/ii/y.webp", "width": 1, "height": 1}}}"""
        enqueuePage(
            item("first", standardFiles("first")),
            item("foreign", foreign),
            item("plain-http", plainHttp),
            """{"id": 9, "slug": "an-ad", "type": "ad"}""",
            item("last", standardFiles("last")),
        )

        val page = source.trending(OnlineMediaKind.GIF, 1, CUSTOMER)

        assertEquals(listOf("first", "last"), page.items.map { it.slug })
    }

    // Klipy's results are not filtered by anything but the host of their files.
    @Test
    fun `an item with an odd slug is kept, and only its share report is refused`() = runTest {
        enqueuePage(item("../../share", standardFiles("dots")))

        val media = source.trending(OnlineMediaKind.GIF, 1, CUSTOMER).items.single()
        val report = runCatching { source.reportShare(media.kind, media.slug, CUSTOMER) }

        assertEquals("../../share", media.slug)
        assertTrue(report.exceptionOrNull() is KlipyException)
        assertEquals(1, server.requestCount)
    }

    @Test
    fun `the large file is never the one to send`() = runTest {
        val files = """{"hd": ${file("static.klipy.com", "a-hd", "webp")}, "xs": ${file("static.klipy.com", "a-xs", "webp")}}"""
        enqueuePage(item("a", files))

        assertTrue(source.trending(OnlineMediaKind.GIF, 1, CUSTOMER).items.isEmpty())
    }

    // One foreign url does not cost the item: the next size on a Klipy host is taken.
    @Test
    fun `a size on a foreign host is skipped for the next size on a Klipy host`() = runTest {
        val files = """{"md": ${file("evil.example", "a-md", "webp")}, "sm": ${file("static2.klipy.com", "a-sm", "webp")}}"""
        enqueuePage(item("a", files))

        val media = source.trending(OnlineMediaKind.GIF, 1, CUSTOMER).items.single()

        assertEquals("https://static2.klipy.com/ii/a-sm.webp", media.send.url)
        assertEquals("https://static2.klipy.com/ii/a-sm.webp", media.preview.url)
    }

    // ── the share report ────────────────────────────────────────────────────

    @Test
    fun `a share is reported with a post to the item's slug, with the customer id as its body`() = runTest {
        server.enqueue(MockResponse().setBody("""{"result": true}"""))

        source.reportShare(OnlineMediaKind.STICKER, "cat-wave_2", CUSTOMER)

        val request = server.takeRequest()
        assertEquals("POST", request.method)
        assertEquals("/api/v1/$KEY/stickers/share/cat-wave_2", request.requestUrl!!.encodedPath)
        assertEquals(CUSTOMER, JSONObject(request.body.readUtf8()).getString("customer_id"))
    }

    @Test
    fun `a slug that is no plain name is never put into a url`() = runTest {
        val results = listOf("..", "a/b", "a?b", "", "a b").map { slug ->
            runCatching { source.reportShare(OnlineMediaKind.GIF, slug, CUSTOMER) }
        }

        assertTrue(results.all { it.exceptionOrNull() is KlipyException })
        assertEquals(0, server.requestCount)
    }

    // ── errors never carry the key ──────────────────────────────────────────

    private fun assertNoKey(error: Throwable?) {
        var current = error
        assertTrue("an error was expected", current != null)
        while (current != null) {
            val text = "${current.message} $current ${current.stackTraceToString()}"
            assertFalse("the key is in: $text", KEY in text)
            assertFalse("a request url is in: $text", "/api/v1" in text)
            current = current.cause
        }
    }

    @Test
    fun `an error answer is reported by its status, without the key or the url`() = runTest {
        server.enqueue(MockResponse().setResponseCode(429).setBody("""{"result": false, "url": "/api/v1/$KEY/gifs/search"}"""))

        val error = runCatching { source.search(OnlineMediaKind.GIF, "cat", 1, CUSTOMER) }.exceptionOrNull()

        assertTrue(error is KlipyException)
        assertTrue("429" in error!!.message!!)
        assertNoKey(error)
    }

    @Test
    fun `a lost connection is an IOException without the key, the url or a cause`() = runTest {
        server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.DISCONNECT_AT_START))

        val error = runCatching { source.trending(OnlineMediaKind.GIF, 1, CUSTOMER) }.exceptionOrNull()

        assertTrue("$error", error is IOException)
        assertNull(error!!.cause)
        assertNoKey(error)
    }

    @Test
    fun `an unreachable server is an IOException without the key`() = runTest {
        server.shutdown()

        val error = runCatching { source.search(OnlineMediaKind.STICKER, "cat", 1, CUSTOMER) }.exceptionOrNull()

        assertTrue("$error", error is IOException)
        assertNoKey(error)
    }

    @Test
    fun `an answer that is no page is refused without the key`() = runTest {
        server.enqueue(MockResponse().setBody("<html>/api/v1/$KEY/gifs/search</html>"))
        server.enqueue(MockResponse().setBody("""{"result": false, "errors": ["/api/v1/$KEY"]}"""))

        val notJson = runCatching { source.search(OnlineMediaKind.GIF, "cat", 1, CUSTOMER) }.exceptionOrNull()
        val noData = runCatching { source.search(OnlineMediaKind.GIF, "cat", 1, CUSTOMER) }.exceptionOrNull()

        assertTrue(notJson is KlipyException)
        assertTrue(noData is KlipyException)
        assertNoKey(notJson)
        assertNoKey(noData)
    }

    // ── a build without a key ───────────────────────────────────────────────

    @Test
    fun `without a key nothing is available and nothing is requested`() = runTest {
        val keyless = KlipyMediaSource(OkHttpClient(), "", server.url("/"))

        assertFalse(keyless.isAvailable)
        assertTrue(source.isAvailable)
        assertTrue(runCatching { keyless.trending(OnlineMediaKind.GIF, 1, CUSTOMER) }.exceptionOrNull() is KlipyException)
        assertTrue(runCatching { keyless.reportShare(OnlineMediaKind.GIF, "cat", CUSTOMER) }.exceptionOrNull() is KlipyException)
        assertEquals(0, server.requestCount)
    }

    private companion object {
        const val KEY = "SECRETKEY123"
        const val CUSTOMER = "3f0c9c7e-1111-4222-8333-444455556666"
    }
}
