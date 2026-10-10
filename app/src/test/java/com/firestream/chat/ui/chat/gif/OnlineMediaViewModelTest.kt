package com.firestream.chat.ui.chat.gif

import com.firestream.chat.domain.model.AppError
import com.firestream.chat.domain.model.OnlineMedia
import com.firestream.chat.domain.model.OnlineMediaKind
import com.firestream.chat.domain.model.OnlineMediaPage
import com.firestream.chat.domain.model.OnlineMediaRendition
import com.firestream.chat.domain.repository.OnlineMediaRepository
import com.firestream.chat.test.MainDispatcherRule
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import java.io.IOException

class OnlineMediaViewModelTest {

    @get:Rule
    val mainDispatcherRule = MainDispatcherRule(StandardTestDispatcher())

    private val repository = mockk<OnlineMediaRepository>()
    private val accepted = MutableStateFlow(true)
    private val appScope = TestScope(mainDispatcherRule.testDispatcher)

    private fun media(slug: String, kind: OnlineMediaKind = OnlineMediaKind.GIF): OnlineMedia {
        val file = OnlineMediaRendition("https://static.klipy.com/$slug.webp", 100, 80, "image/webp")
        return OnlineMedia(kind, slug, slug, file, file)
    }

    private fun page(number: Int, hasNext: Boolean, vararg slugs: String) =
        Result.success(OnlineMediaPage(slugs.map { media(it) }, number, hasNext))

    private fun viewModel() = OnlineMediaViewModel(repository, appScope)

    @Before
    fun setUp() {
        every { repository.isAvailable } returns true
        every { repository.noticeAccepted } returns accepted
        coEvery { repository.acceptNotice() } answers { accepted.value = true }
        coEvery { repository.reportShare(any()) } returns Result.success(Unit)
        coEvery { repository.trending(any(), any()) } returns page(1, false, "trend")
        coEvery { repository.search(any(), any(), any()) } returns page(1, false, "found")
    }

    @Test
    fun `nothing is requested before the notice is accepted, and accepting loads what the tab asked for`() =
        runTest(mainDispatcherRule.testDispatcher) {
            accepted.value = false
            val viewModel = viewModel()
            advanceUntilIdle()
            assertEquals(false, viewModel.uiState.value.noticeAccepted)

            viewModel.onQuery(OnlineMediaKind.GIF, "")
            advanceUntilIdle()
            coVerify(exactly = 0) { repository.trending(any(), any()) }

            viewModel.acceptNotice()
            advanceUntilIdle()

            assertEquals(true, viewModel.uiState.value.noticeAccepted)
            assertEquals(listOf("trend"), viewModel.uiState.value.gifs.items.map { it.slug })
            coVerify(exactly = 1) { repository.acceptNotice() }
            // The accept and the stored flag both ask for the load. It runs once.
            coVerify(exactly = 1) { repository.trending(OnlineMediaKind.GIF, 1) }
            coVerify(exactly = 0) { repository.trending(OnlineMediaKind.STICKER, any()) }
        }

    @Test
    fun `the notice is unknown until the stored answer is read`() = runTest(mainDispatcherRule.testDispatcher) {
        val viewModel = viewModel()

        assertNull(viewModel.uiState.value.noticeAccepted)
        advanceUntilIdle()
        assertEquals(true, viewModel.uiState.value.noticeAccepted)
    }

    @Test
    fun `a search waits for the typing to pause and only the last query is requested`() =
        runTest(mainDispatcherRule.testDispatcher) {
            val viewModel = viewModel()
            advanceUntilIdle()

            viewModel.onQuery(OnlineMediaKind.GIF, "c")
            advanceTimeBy(100)
            viewModel.onQuery(OnlineMediaKind.GIF, "ca")
            advanceTimeBy(100)
            viewModel.onQuery(OnlineMediaKind.GIF, " cat ")
            assertTrue(viewModel.uiState.value.gifs.isLoading)
            advanceUntilIdle()

            coVerify(exactly = 1) { repository.search(any(), any(), any()) }
            coVerify(exactly = 1) { repository.search(OnlineMediaKind.GIF, "cat", 1) }
            val feed = viewModel.uiState.value.gifs
            assertEquals("cat", feed.query)
            assertEquals(listOf("found"), feed.items.map { it.slug })
            assertFalse(feed.isLoading)
        }

    @Test
    fun `a tab shown again with the query it already holds requests nothing`() =
        runTest(mainDispatcherRule.testDispatcher) {
            val viewModel = viewModel()
            advanceUntilIdle()

            viewModel.onQuery(OnlineMediaKind.STICKER, "")
            advanceUntilIdle()
            viewModel.onQuery(OnlineMediaKind.STICKER, "")
            advanceUntilIdle()

            coVerify(exactly = 1) { repository.trending(OnlineMediaKind.STICKER, 1) }
            assertEquals(listOf("trend"), viewModel.uiState.value.stickers.items.map { it.slug })
            assertTrue(viewModel.uiState.value.gifs.items.isEmpty())
        }

    @Test
    fun `pages are appended in order while the provider has more, and a repeated item is shown once`() =
        runTest(mainDispatcherRule.testDispatcher) {
            coEvery { repository.trending(OnlineMediaKind.GIF, 1) } returns page(1, true, "a", "b")
            coEvery { repository.trending(OnlineMediaKind.GIF, 2) } returns page(2, false, "b", "c")
            val viewModel = viewModel()
            advanceUntilIdle()
            viewModel.onQuery(OnlineMediaKind.GIF, "")
            advanceUntilIdle()

            viewModel.loadMore(OnlineMediaKind.GIF)
            advanceUntilIdle()
            viewModel.loadMore(OnlineMediaKind.GIF)
            advanceUntilIdle()

            val feed = viewModel.uiState.value.gifs
            assertEquals(listOf("a", "b", "c"), feed.items.map { it.slug })
            assertEquals(2, feed.page)
            assertFalse(feed.hasNext)
            coVerify(exactly = 0) { repository.trending(OnlineMediaKind.GIF, 3) }
        }

    @Test
    fun `a failed page is an AppError that the grid does not retry and the user can`() =
        runTest(mainDispatcherRule.testDispatcher) {
            coEvery { repository.trending(OnlineMediaKind.GIF, 1) } returns page(1, true, "a")
            coEvery { repository.trending(OnlineMediaKind.GIF, 2) } returns Result.failure(IOException("offline"))
            val viewModel = viewModel()
            advanceUntilIdle()
            viewModel.onQuery(OnlineMediaKind.GIF, "")
            advanceUntilIdle()

            viewModel.loadMore(OnlineMediaKind.GIF)
            advanceUntilIdle()
            assertTrue(viewModel.uiState.value.gifs.error is AppError.Network)
            assertEquals(listOf("a"), viewModel.uiState.value.gifs.items.map { it.slug })

            viewModel.loadMore(OnlineMediaKind.GIF)
            advanceUntilIdle()
            coVerify(exactly = 1) { repository.trending(OnlineMediaKind.GIF, 2) }

            coEvery { repository.trending(OnlineMediaKind.GIF, 2) } returns page(2, false, "b")
            viewModel.retry(OnlineMediaKind.GIF)
            advanceUntilIdle()

            assertNull(viewModel.uiState.value.gifs.error)
            assertEquals(listOf("a", "b"), viewModel.uiState.value.gifs.items.map { it.slug })
        }

    @Test
    fun `a later page that brings nothing new ends the paging, whatever the provider says`() =
        runTest(mainDispatcherRule.testDispatcher) {
            coEvery { repository.trending(OnlineMediaKind.GIF, 1) } returns page(1, true, "a", "b")
            coEvery { repository.trending(OnlineMediaKind.GIF, 2) } returns page(2, true, "b", "a")
            val viewModel = viewModel()
            advanceUntilIdle()
            viewModel.onQuery(OnlineMediaKind.GIF, "")
            advanceUntilIdle()

            viewModel.loadMore(OnlineMediaKind.GIF)
            advanceUntilIdle()
            viewModel.loadMore(OnlineMediaKind.GIF)
            advanceUntilIdle()

            assertFalse(viewModel.uiState.value.gifs.hasNext)
            coVerify(exactly = 0) { repository.trending(OnlineMediaKind.GIF, 3) }
        }

    @Test
    fun `items the provider gave no slug are all kept`() = runTest(mainDispatcherRule.testDispatcher) {
        val first = media("one").copy(slug = "")
        val second = media("two").copy(slug = "")
        coEvery { repository.trending(OnlineMediaKind.GIF, 1) } returns
            Result.success(OnlineMediaPage(listOf(first, second), 1, false))
        val viewModel = viewModel()
        advanceUntilIdle()

        viewModel.onQuery(OnlineMediaKind.GIF, "")
        advanceUntilIdle()

        assertEquals(listOf(first, second), viewModel.uiState.value.gifs.items)
    }

    @Test
    fun `accepting the notice loads the tab on screen and no other`() = runTest(mainDispatcherRule.testDispatcher) {
        accepted.value = false
        val viewModel = viewModel()
        advanceUntilIdle()
        viewModel.onQuery(OnlineMediaKind.STICKER, "cat")
        viewModel.onQuery(OnlineMediaKind.GIF, "")

        viewModel.acceptNotice()
        advanceUntilIdle()

        coVerify(exactly = 1) { repository.trending(OnlineMediaKind.GIF, 1) }
        coVerify(exactly = 0) { repository.search(any(), any(), any()) }
    }

    @Test
    fun `a first page that failed is asked for again by a retry, not by the tab shown again`() = runTest(mainDispatcherRule.testDispatcher) {
        coEvery { repository.search(OnlineMediaKind.GIF, "cat", 1) } returns Result.failure(IOException("offline"))
        val viewModel = viewModel()
        advanceUntilIdle()
        viewModel.onQuery(OnlineMediaKind.GIF, "cat")
        advanceUntilIdle()
        assertTrue(viewModel.uiState.value.gifs.error != null)

        viewModel.onQuery(OnlineMediaKind.GIF, "cat")
        advanceUntilIdle()
        coVerify(exactly = 1) { repository.search(OnlineMediaKind.GIF, "cat", 1) }

        coEvery { repository.search(OnlineMediaKind.GIF, "cat", 1) } returns page(1, false, "found")
        viewModel.retry(OnlineMediaKind.GIF)
        advanceUntilIdle()

        assertEquals(listOf("found"), viewModel.uiState.value.gifs.items.map { it.slug })
    }

    @Test
    fun `a build without a key requests nothing and reads no flag`() = runTest(mainDispatcherRule.testDispatcher) {
        every { repository.isAvailable } returns false
        val viewModel = viewModel()
        advanceUntilIdle()

        viewModel.onQuery(OnlineMediaKind.GIF, "cat")
        viewModel.acceptNotice()
        viewModel.loadMore(OnlineMediaKind.GIF)
        advanceUntilIdle()

        assertFalse(viewModel.uiState.value.isAvailable)
        coVerify(exactly = 0) { repository.search(any(), any(), any()) }
        coVerify(exactly = 0) { repository.trending(any(), any()) }
        coVerify(exactly = 0) { repository.acceptNotice() }
    }

    @Test
    fun `a sent pick is reported to the provider`() = runTest(mainDispatcherRule.testDispatcher) {
        val pick = media("cat-wave", OnlineMediaKind.STICKER)
        coEvery { repository.reportShare(pick) } returns Result.failure(IOException("offline"))
        val viewModel = viewModel()

        viewModel.onSent(pick)
        advanceUntilIdle()

        coVerify(exactly = 1) { repository.reportShare(pick) }
    }
}
