package com.firestream.chat.ui.search

import com.firestream.chat.data.remote.LinkPreview
import com.firestream.chat.data.remote.LinkPreviewSource
import com.firestream.chat.domain.model.MessageType
import com.firestream.chat.test.MainDispatcherRule
import com.firestream.chat.test.TestData
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

/**
 * The demand-driven half of link results: a row asks for its preview as it
 * composes, and composition is re-entered constantly — on every scroll back, on
 * every re-emitted result list. Without the scan map that is one network fetch
 * (and possibly one 20-second WebView capture) per recomposition, which is the
 * failure this class exists to prevent and which is invisible on screen.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class SearchLinkPreviewLoaderTest {

    @get:Rule
    val mainDispatcherRule = MainDispatcherRule(StandardTestDispatcher())

    private val source = mockk<LinkPreviewSource>()
    private val preview = LinkPreview(
        url = "https://example.com/article",
        title = "An article",
        description = null,
        imageUrl = "https://example.com/hero.png",
    )

    private val resolved = mutableListOf<Pair<String, LinkPreview>>()

    private fun scopeLoader(scope: kotlinx.coroutines.CoroutineScope) =
        SearchLinkPreviewLoader(scope, source) { id, p -> resolved += id to p }

    @Test
    fun `resolves the preview behind a link and reports it against the message id`() = runTest {
        coEvery { source.fetchPreview(any()) } returns preview
        val loader = scopeLoader(this)

        loader.request(TestData.message(id = "m1", content = "look at https://example.com/article"))
        advanceUntilIdle()

        assertEquals(listOf("m1" to preview), resolved)
    }

    @Test
    fun `asks the source once however often the row recomposes`() = runTest {
        coEvery { source.fetchPreview(any()) } returns preview
        val loader = scopeLoader(this)
        val message = TestData.message(id = "m1", content = "look at https://example.com/article")

        repeat(5) { loader.request(message) }
        advanceUntilIdle()

        coVerify(exactly = 1) { source.fetchPreview("https://example.com/article") }
        assertEquals(1, resolved.size)
    }

    @Test
    fun `an edited message is rescanned, because its link may have changed`() = runTest {
        coEvery { source.fetchPreview(any()) } returns preview
        val loader = scopeLoader(this)

        loader.request(TestData.message(id = "m1", content = "https://example.com/article"))
        loader.request(TestData.message(id = "m1", content = "https://example.com/other"))
        advanceUntilIdle()

        coVerify(exactly = 1) { source.fetchPreview("https://example.com/article") }
        coVerify(exactly = 1) { source.fetchPreview("https://example.com/other") }
    }

    @Test
    fun `a message with no link never reaches the source`() = runTest {
        val loader = scopeLoader(this)

        loader.request(TestData.message(id = "m1", content = "no link in here at all"))
        advanceUntilIdle()

        coVerify(exactly = 0) { source.fetchPreview(any()) }
        assertTrue(resolved.isEmpty())
    }

    @Test
    fun `a non-text message never reaches the source`() = runTest {
        val loader = scopeLoader(this)

        // A DOCUMENT's content is its filename, which can look like anything.
        loader.request(
            TestData.message(
                id = "m1",
                content = "https://example.com/article",
                type = MessageType.DOCUMENT,
            )
        )
        advanceUntilIdle()

        coVerify(exactly = 0) { source.fetchPreview(any()) }
    }

    @Test
    fun `a source that resolves nothing reports nothing`() = runTest {
        coEvery { source.fetchPreview(any()) } returns null
        val loader = scopeLoader(this)

        loader.request(TestData.message(id = "m1", content = "https://example.com/article"))
        advanceUntilIdle()

        assertTrue(resolved.isEmpty())
    }
}
