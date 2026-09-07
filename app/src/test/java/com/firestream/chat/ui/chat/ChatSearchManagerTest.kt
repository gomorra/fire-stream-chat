package com.firestream.chat.ui.chat

import com.firestream.chat.domain.model.MessageFilterType
import com.firestream.chat.domain.model.MessageSearchFilter
import com.firestream.chat.domain.model.MessageStatus
import com.firestream.chat.domain.model.MessageType
import com.firestream.chat.domain.model.Message
import com.firestream.chat.domain.usecase.message.SearchMessagesUseCase
import com.firestream.chat.test.MainDispatcherRule
import com.firestream.chat.test.fakes.FakeMessageRepository
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test

/**
 * The two search inputs — the text box and the chip row — feed the same query
 * but on different timings: typing debounces, a chip tap does not. These tests
 * pin that asymmetry, plus the reset semantics that keep the two axes from
 * outliving each other.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ChatSearchManagerTest {

    @get:Rule
    val mainDispatcherRule = MainDispatcherRule(StandardTestDispatcher())

    private val repository = FakeMessageRepository()
    private val useCase = SearchMessagesUseCase(repository)
    private val uiState = MutableStateFlow(ChatUiState())

    private val hello = message(id = "t1", content = "hello harbour", type = MessageType.TEXT)
    private val photo = message(id = "p1", content = "", type = MessageType.IMAGE)

    @Before
    fun setUp() {
        repository.emit(CHAT_ID, listOf(hello, photo))
    }

    private fun TestScope.newManager() = ChatSearchManager(
        chatId = CHAT_ID,
        searchMessagesUseCase = useCase,
        _uiState = uiState,
        scope = this,
    )

    // ── Debounce asymmetry ───────────────────────────────────────────────────

    @Test
    fun `typing still debounces before querying`() = runTest {
        val manager = newManager()

        manager.onSearchQueryChange("harbour")
        advanceTimeBy(200)
        runCurrent()
        assertTrue("query must not fire before the debounce elapses", uiState.value.overlays.searchResults.isEmpty())

        advanceUntilIdle()
        assertEquals(listOf("t1"), uiState.value.overlays.searchResults.map { it.id })
    }

    @Test
    fun `a chip tap re-queries immediately without the typing debounce`() = runTest {
        val manager = newManager()

        manager.onFilterChange(MessageSearchFilter(type = MessageFilterType.PHOTOS))
        runCurrent()

        assertEquals(listOf("p1"), uiState.value.overlays.searchResults.map { it.id })
    }

    @Test
    fun `a chip tap re-runs the query against the text already typed`() = runTest {
        val manager = newManager()
        manager.onSearchQueryChange("harbour")
        advanceUntilIdle()

        manager.onFilterChange(MessageSearchFilter(type = MessageFilterType.PHOTOS))
        runCurrent()

        // "harbour" AND photos matches nothing; the query must carry both axes.
        assertTrue(uiState.value.overlays.searchResults.isEmpty())
        assertEquals(
            MessageSearchFilter(type = MessageFilterType.PHOTOS),
            repository.lastSearchFilter,
        )
    }

    @Test
    fun `the filter reaches the repository unchanged`() = runTest {
        val manager = newManager()
        val filter = MessageSearchFilter(
            type = MessageFilterType.DOCS,
            isStarred = true,
            fromMs = 5L,
            toMs = 50L,
        )

        manager.onFilterChange(filter)
        advanceUntilIdle()

        assertEquals(filter, repository.lastSearchFilter)
        assertEquals(filter, uiState.value.overlays.searchFilter)
    }

    // ── Browse mode ──────────────────────────────────────────────────────────

    @Test
    fun `a filter with a blank query browses`() = runTest {
        val manager = newManager()

        manager.onFilterChange(MessageSearchFilter(type = MessageFilterType.PHOTOS))
        advanceUntilIdle()

        assertEquals(listOf("p1"), uiState.value.overlays.searchResults.map { it.id })
    }

    @Test
    fun `clearing the last chip with a blank query empties the results without a round trip`() = runTest {
        val manager = newManager()
        manager.onFilterChange(MessageSearchFilter(type = MessageFilterType.PHOTOS))
        advanceUntilIdle()
        repository.lastSearchFilter = null

        manager.onFilterChange(MessageSearchFilter.NONE)
        advanceUntilIdle()

        assertTrue(uiState.value.overlays.searchResults.isEmpty())
        assertNull("no query should have been issued", repository.lastSearchFilter)
    }

    @Test
    fun `openSearchWithFilter opens search already browsing`() = runTest {
        val manager = newManager()

        manager.openSearchWithFilter(MessageSearchFilter(type = MessageFilterType.PHOTOS))
        advanceUntilIdle()

        assertTrue(uiState.value.overlays.isSearchActive)
        assertEquals(MessageFilterType.PHOTOS, uiState.value.overlays.searchFilter.type)
        assertEquals(listOf("p1"), uiState.value.overlays.searchResults.map { it.id })
    }

    // ── Reset semantics ──────────────────────────────────────────────────────

    @Test
    fun `clearSearch resets both axes`() = runTest {
        val manager = newManager()
        manager.onSearchQueryChange("harbour")
        manager.onFilterChange(MessageSearchFilter(isStarred = true))
        advanceUntilIdle()

        manager.clearSearch()

        val overlays = uiState.value.overlays
        assertFalse(overlays.isSearchActive)
        assertEquals("", overlays.searchQuery)
        assertEquals(MessageSearchFilter.NONE, overlays.searchFilter)
        assertTrue(overlays.searchResults.isEmpty())
    }

    @Test
    fun `closing search via toggle resets both axes`() = runTest {
        val manager = newManager()
        manager.toggleSearch()
        manager.onSearchQueryChange("harbour")
        manager.onFilterChange(MessageSearchFilter(type = MessageFilterType.VOICE))
        advanceUntilIdle()

        manager.toggleSearch()

        val overlays = uiState.value.overlays
        assertFalse(overlays.isSearchActive)
        assertEquals("", overlays.searchQuery)
        assertEquals(MessageSearchFilter.NONE, overlays.searchFilter)
        assertTrue(overlays.searchResults.isEmpty())
    }

    @Test
    fun `a superseded query does not clobber the results of the one replacing it`() = runTest {
        val manager = newManager()

        manager.onSearchQueryChange("harbour")
        // The debounce is still pending; the chip tap cancels it mid-flight.
        advanceTimeBy(100)
        manager.onFilterChange(MessageSearchFilter(fromMs = 0L))
        advanceUntilIdle()

        // The cancelled job must not have written its empty-results fallback
        // over what the job replacing it produced.
        assertEquals(listOf("t1"), uiState.value.overlays.searchResults.map { it.id })
    }

    private fun message(id: String, content: String, type: MessageType) = Message(
        id = id,
        chatId = CHAT_ID,
        senderId = "u1",
        content = content,
        type = type,
        status = MessageStatus.SENT,
        timestamp = 1_000L,
    )

    private companion object {
        const val CHAT_ID = "c1"
    }
}
