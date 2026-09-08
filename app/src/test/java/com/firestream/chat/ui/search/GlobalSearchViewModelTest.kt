package com.firestream.chat.ui.search

import com.firestream.chat.domain.model.Chat
import com.firestream.chat.domain.model.ChatType
import com.firestream.chat.domain.model.Contact
import com.firestream.chat.domain.model.Message
import com.firestream.chat.domain.model.MessageFilterType
import com.firestream.chat.domain.model.MessageSearchFilter
import com.firestream.chat.domain.model.MessageStatus
import com.firestream.chat.domain.model.MessageType
import com.firestream.chat.domain.repository.AuthRepository
import com.firestream.chat.domain.repository.ContactRepository
import com.firestream.chat.domain.usecase.message.SearchMessagesUseCase
import com.firestream.chat.test.MainDispatcherRule
import com.firestream.chat.test.fakes.FakeChatRepository
import com.firestream.chat.test.fakes.FakeMessageRepository
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.StandardTestDispatcher
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
 * The global search view model: the same debounce asymmetry `ChatSearchManager`
 * has (typing waits, a chip tap does not), plus the two things only the global
 * scope has to get right — a filter that reaches the repository even with a
 * blank query, and a chat/contact map good enough to say where a hit came from.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class GlobalSearchViewModelTest {

    @get:Rule
    val mainDispatcherRule = MainDispatcherRule(StandardTestDispatcher())

    private val messageRepository = FakeMessageRepository()
    private val chatRepository = FakeChatRepository()
    private val contactRepository = mockk<ContactRepository>()
    private val authRepository = mockk<AuthRepository>()

    private val oneToOne = Chat(id = "c1", type = ChatType.INDIVIDUAL, participants = listOf("me", "alice"))
    private val group = Chat(id = "c2", type = ChatType.GROUP, name = "Weekend Trip", participants = listOf("me", "bob"))

    private val hereText = message(id = "t1", chatId = "c1", senderId = "alice", content = "hello harbour")
    private val therePhoto = message(id = "p1", chatId = "c2", senderId = "bob", content = "", type = MessageType.IMAGE)

    @Before
    fun setUp() {
        every { authRepository.currentUserId } returns "me"
        every { contactRepository.getContacts() } returns flowOf(
            listOf(
                Contact(uid = "alice", displayName = "Alice"),
                Contact(uid = "bob", displayName = "Bob"),
            )
        )
        chatRepository.emit(listOf(oneToOne, group))
        messageRepository.emit("c1", listOf(hereText))
        messageRepository.emit("c2", listOf(therePhoto))
    }

    private fun newViewModel() = GlobalSearchViewModel(
        searchMessagesUseCase = SearchMessagesUseCase(messageRepository),
        linkPreviewSource = mockk(relaxed = true),
        authRepository = authRepository,
        chatRepository = chatRepository,
        contactRepository = contactRepository,
    )

    // ── Debounce asymmetry ───────────────────────────────────────────────────

    @Test
    fun `typing debounces before querying`() = runTest {
        val vm = newViewModel()

        vm.onQueryChange("harbour")
        advanceTimeBy(200)
        runCurrent()
        assertTrue("query must not fire before the debounce elapses", vm.uiState.value.results.isEmpty())

        advanceUntilIdle()
        assertEquals(listOf("t1"), vm.uiState.value.results.map { it.id })
    }

    @Test
    fun `the last query typed wins`() = runTest {
        val vm = newViewModel()

        vm.onQueryChange("harbour")
        advanceTimeBy(100)
        runCurrent()
        vm.onQueryChange("nothing-matches-this")
        advanceUntilIdle()

        assertTrue(
            "a superseded query must not write its results",
            vm.uiState.value.results.isEmpty(),
        )
        assertEquals("nothing-matches-this", vm.uiState.value.query)
    }

    @Test
    fun `a chip tap queries immediately`() = runTest {
        val vm = newViewModel()

        vm.onFilterChange(MessageSearchFilter(type = MessageFilterType.PHOTOS))
        runCurrent()

        assertEquals(listOf("p1"), vm.uiState.value.results.map { it.id })
    }

    // ── Selection gating ─────────────────────────────────────────────────────

    @Test
    fun `a blank query with no filter clears without a round trip`() = runTest {
        val vm = newViewModel()
        vm.onQueryChange("harbour")
        advanceUntilIdle()
        assertFalse(vm.uiState.value.results.isEmpty())

        messageRepository.lastSearchFilter = null
        vm.onQueryChange("")
        advanceUntilIdle()

        assertTrue(vm.uiState.value.results.isEmpty())
        assertFalse(vm.uiState.value.isSelecting)
        assertNull("the repository must not be asked at all", messageRepository.lastSearchFilter)
    }

    @Test
    fun `browse mode - a blank query with a chip does query`() = runTest {
        val vm = newViewModel()

        vm.onFilterChange(MessageSearchFilter(type = MessageFilterType.PHOTOS))
        advanceUntilIdle()

        assertTrue(vm.uiState.value.isSelecting)
        assertEquals(listOf("p1"), vm.uiState.value.results.map { it.id })
        assertEquals(MessageFilterType.PHOTOS, messageRepository.lastSearchFilter?.type)
    }

    @Test
    fun `the search spans every chat`() = runTest {
        messageRepository.emit("c2", listOf(therePhoto, message(id = "t2", chatId = "c2", senderId = "bob", content = "the harbour again")))
        val vm = newViewModel()

        vm.onQueryChange("harbour")
        advanceUntilIdle()

        assertEquals(setOf("t1", "t2"), vm.uiState.value.results.map { it.id }.toSet())
    }

    @Test
    fun `a thrown search leaves empty results rather than crashing`() = runTest {
        val vm = newViewModel()
        messageRepository.nextFailure = IllegalStateException("db gone")

        vm.onQueryChange("harbour")
        advanceUntilIdle()

        assertTrue(vm.uiState.value.results.isEmpty())
        assertFalse(vm.uiState.value.truncated)
    }

    @Test
    fun `truncation is carried through to the state`() = runTest {
        messageRepository.searchTruncated = true
        val vm = newViewModel()

        vm.onQueryChange("harbour")
        advanceUntilIdle()

        assertTrue(vm.uiState.value.truncated)
    }

    // ── Result labelling and routing ─────────────────────────────────────────
    //
    // Both are extensions on the state rather than view-model methods, so what
    // these pin is that the chat and contact maps actually reach the state —
    // the labelling rules themselves are pinned in GlobalSearchLabelTest.

    @Test
    fun `a result is labelled with where it came from`() = runTest {
        val vm = newViewModel()
        advanceUntilIdle()

        assertEquals("Alice", vm.uiState.value.resultLabel(hereText))
        assertEquals("Bob · Weekend Trip", vm.uiState.value.resultLabel(therePhoto))
    }

    @Test
    fun `recipientIdFor resolves a 1-to-1 partner and is empty for a group`() = runTest {
        val vm = newViewModel()
        advanceUntilIdle()

        assertEquals("alice", vm.uiState.value.recipientIdFor("c1"))
        assertEquals("", vm.uiState.value.recipientIdFor("c2"))
        assertEquals("", vm.uiState.value.recipientIdFor("unknown"))
    }

    private fun message(
        id: String,
        chatId: String,
        senderId: String,
        content: String,
        type: MessageType = MessageType.TEXT,
    ) = Message(
        id = id,
        chatId = chatId,
        senderId = senderId,
        content = content,
        type = type,
        status = MessageStatus.SENT,
    )
}
