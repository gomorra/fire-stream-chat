package com.firestream.chat.ui.chatlist

import com.firestream.chat.domain.model.Chat
import com.firestream.chat.domain.model.ChatType
import com.firestream.chat.domain.model.Message
import com.firestream.chat.domain.repository.AuthRepository
import com.firestream.chat.domain.repository.ContactRepository
import com.firestream.chat.domain.repository.UserRepository
import com.firestream.chat.domain.usecase.message.SearchMessagesUseCase
import com.firestream.chat.test.fakes.FakeChatRepository
import com.firestream.chat.test.fakes.FakeMessageRepository
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class ChatListViewModelTest {

    private val testDispatcher = StandardTestDispatcher()

    private lateinit var searchMessagesUseCase: SearchMessagesUseCase
    private lateinit var authRepository: AuthRepository
    private lateinit var contactRepository: ContactRepository
    private lateinit var userRepository: UserRepository

    private val chatRepository = FakeChatRepository()
    private val messageRepository = FakeMessageRepository()

    private lateinit var viewModel: ChatListViewModel

    private val chat1 = Chat(
        id = "c1",
        type = ChatType.INDIVIDUAL,
        participants = listOf("user1", "user2")
    )
    private val chat2 = Chat(
        id = "c2",
        type = ChatType.INDIVIDUAL,
        participants = listOf("user1", "user3"),
        isPinned = true
    )

    @Before
    fun setUp() {
        Dispatchers.setMain(testDispatcher)
        searchMessagesUseCase = mockk()
        authRepository = mockk()
        contactRepository = mockk()
        userRepository = mockk()

        every { authRepository.currentUserId } returns "user1"
        coEvery { searchMessagesUseCase(any(), any()) } returns emptyList()
        coEvery { contactRepository.syncContacts() } returns Result.success(emptyList())
        every { contactRepository.getContacts() } returns flowOf(emptyList())
        every { userRepository.observeUser(any()) } returns emptyFlow()

        chatRepository.emit(listOf(chat1, chat2))

        viewModel = ChatListViewModel(
            searchMessagesUseCase = searchMessagesUseCase,
            authRepository = authRepository,
            chatRepository = chatRepository,
            messageRepository = messageRepository,
            contactRepository = contactRepository,
            userRepository = userRepository
        )
    }

    @After
    fun tearDown() {
        chatRepository.reset()
        messageRepository.reset()
        Dispatchers.resetMain()
    }

    @Test
    fun `init loads chats and sets currentUserId`() = runTest {
        advanceUntilIdle()

        val state = viewModel.uiState.value
        assertEquals("user1", state.currentUserId)
        assertEquals(2, state.chats.size)
        assertFalse(state.isLoading)
    }

    @Test
    fun `requestDeleteChat sets pendingDeleteChatId`() = runTest {
        advanceUntilIdle()

        viewModel.requestDeleteChat("c1")

        assertEquals("c1", viewModel.uiState.value.pendingDeleteChatId)
    }

    @Test
    fun `cancelDeleteChat clears pendingDeleteChatId`() = runTest {
        advanceUntilIdle()
        viewModel.requestDeleteChat("c1")

        viewModel.cancelDeleteChat()

        assertNull(viewModel.uiState.value.pendingDeleteChatId)
    }

    @Test
    fun `togglePin shows error when trying to pin more than 3 chats`() = runTest {
        val pinnedChats = (1..3).map { Chat(id = "pin$it", type = ChatType.INDIVIDUAL, isPinned = true) }
        chatRepository.reset()
        chatRepository.emit(pinnedChats)

        val vm = ChatListViewModel(
            searchMessagesUseCase = searchMessagesUseCase,
            authRepository = authRepository,
            chatRepository = chatRepository,
            messageRepository = messageRepository,
            contactRepository = contactRepository,
            userRepository = userRepository
        )
        advanceUntilIdle()

        vm.togglePin("new_chat", currentlyPinned = false)

        assertTrue(vm.uiState.value.error?.message?.contains("pin up to 3") == true)
        assertTrue(vm.uiState.value.error is com.firestream.chat.domain.model.AppError.Validation)
    }

    @Test
    fun `requestMuteChat and cancelMuteChat work correctly`() = runTest {
        advanceUntilIdle()

        viewModel.requestMuteChat("c1")
        assertEquals("c1", viewModel.uiState.value.pendingMuteChatId)

        viewModel.cancelMuteChat()
        assertNull(viewModel.uiState.value.pendingMuteChatId)
    }

    @Test
    fun `toggleShowArchived toggles flag`() = runTest {
        advanceUntilIdle()

        assertFalse(viewModel.uiState.value.showArchived)
        viewModel.toggleShowArchived()
        assertTrue(viewModel.uiState.value.showArchived)
        viewModel.toggleShowArchived()
        assertFalse(viewModel.uiState.value.showArchived)
    }

    @Test
    fun `clearSearch resets search state`() = runTest {
        advanceUntilIdle()
        viewModel.onSearchQueryChange("hello")
        advanceUntilIdle()

        viewModel.clearSearch()

        val state = viewModel.uiState.value
        assertEquals("", state.searchQuery)
        assertTrue(state.searchResults.isEmpty())
        assertFalse(state.isSearchActive)
    }

    @Test
    fun `clearError clears error message`() = runTest {
        advanceUntilIdle()
        viewModel.togglePin("newChat", false)

        viewModel.clearError()

        assertNull(viewModel.uiState.value.error)
    }

    @Test
    fun `chats are sorted by latest communication regardless of repository emission order`() = runTest {
        // Emit chats out of order — the chat with the newest lastMessage should
        // surface at the top of uiState.chats, not the position it was emitted in.
        val older = Chat(
            id = "older",
            type = ChatType.INDIVIDUAL,
            participants = listOf("user1", "u2"),
            createdAt = 100L,
            lastMessage = Message(id = "m1", chatId = "older", senderId = "u2", content = "old", timestamp = 1_000L),
        )
        val newer = Chat(
            id = "newer",
            type = ChatType.INDIVIDUAL,
            participants = listOf("user1", "u3"),
            createdAt = 100L,
            lastMessage = Message(id = "m2", chatId = "newer", senderId = "u3", content = "new", timestamp = 5_000L),
        )
        chatRepository.reset()
        chatRepository.emit(listOf(older, newer))

        val vm = ChatListViewModel(
            searchMessagesUseCase = searchMessagesUseCase,
            authRepository = authRepository,
            chatRepository = chatRepository,
            messageRepository = messageRepository,
            contactRepository = contactRepository,
            userRepository = userRepository
        )
        advanceUntilIdle()

        assertEquals(listOf("newer", "older"), vm.uiState.value.chats.map { it.id })
    }

    @Test
    fun `incoming message bumps its chat to the top of the list`() = runTest {
        val chatA = Chat(
            id = "a",
            type = ChatType.INDIVIDUAL,
            participants = listOf("user1", "u2"),
            createdAt = 100L,
            lastMessage = Message(id = "ma", chatId = "a", senderId = "u2", content = "hi", timestamp = 2_000L),
        )
        val chatB = Chat(
            id = "b",
            type = ChatType.INDIVIDUAL,
            participants = listOf("user1", "u3"),
            createdAt = 100L,
            lastMessage = Message(id = "mb", chatId = "b", senderId = "u3", content = "hey", timestamp = 3_000L),
        )
        chatRepository.reset()
        chatRepository.emit(listOf(chatA, chatB))

        val vm = ChatListViewModel(
            searchMessagesUseCase = searchMessagesUseCase,
            authRepository = authRepository,
            chatRepository = chatRepository,
            messageRepository = messageRepository,
            contactRepository = contactRepository,
            userRepository = userRepository
        )
        advanceUntilIdle()
        assertEquals(listOf("b", "a"), vm.uiState.value.chats.map { it.id })

        // A new message lands in chat A — it must move above chat B.
        val chatANewer = chatA.copy(
            lastMessage = Message(id = "ma2", chatId = "a", senderId = "u2", content = "newer", timestamp = 9_000L),
        )
        chatRepository.emit(listOf(chatANewer, chatB))
        advanceUntilIdle()

        assertEquals(listOf("a", "b"), vm.uiState.value.chats.map { it.id })
    }

    @Test
    fun `chat with no messages falls back to createdAt for ordering`() = runTest {
        val emptyRecent = Chat(
            id = "empty-recent",
            type = ChatType.INDIVIDUAL,
            participants = listOf("user1", "u2"),
            createdAt = 5_000L,
            lastMessage = null,
        )
        val olderWithMessage = Chat(
            id = "old-msg",
            type = ChatType.INDIVIDUAL,
            participants = listOf("user1", "u3"),
            createdAt = 100L,
            lastMessage = Message(id = "m", chatId = "old-msg", senderId = "u3", content = "hi", timestamp = 1_000L),
        )
        chatRepository.reset()
        chatRepository.emit(listOf(olderWithMessage, emptyRecent))

        val vm = ChatListViewModel(
            searchMessagesUseCase = searchMessagesUseCase,
            authRepository = authRepository,
            chatRepository = chatRepository,
            messageRepository = messageRepository,
            contactRepository = contactRepository,
            userRepository = userRepository
        )
        advanceUntilIdle()

        // emptyRecent has createdAt 5000 > olderWithMessage's lastMessage 1000,
        // so it sorts above per the "mix by recency" rule.
        assertEquals(listOf("empty-recent", "old-msg"), vm.uiState.value.chats.map { it.id })
    }
}
