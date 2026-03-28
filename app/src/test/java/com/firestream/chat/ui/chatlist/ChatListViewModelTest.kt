package com.firestream.chat.ui.chatlist

import com.firestream.chat.domain.model.Chat
import com.firestream.chat.domain.model.ChatType
import com.firestream.chat.domain.repository.AuthRepository
import com.firestream.chat.domain.repository.ChatRepository
import com.firestream.chat.domain.repository.ContactRepository
import com.firestream.chat.domain.repository.MessageRepository
import com.firestream.chat.domain.repository.UserRepository
import com.firestream.chat.domain.usecase.message.SearchMessagesUseCase
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
    private lateinit var chatRepository: ChatRepository
    private lateinit var messageRepository: MessageRepository
    private lateinit var contactRepository: ContactRepository
    private lateinit var userRepository: UserRepository
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
        chatRepository = mockk()
        messageRepository = mockk()
        contactRepository = mockk()
        userRepository = mockk()

        every { authRepository.currentUserId } returns "user1"
        every { chatRepository.getChats() } returns flowOf(listOf(chat1, chat2))
        coEvery { searchMessagesUseCase(any(), any()) } returns emptyList()
        coEvery { chatRepository.pinChat(any(), any()) } returns Result.success(Unit)
        coEvery { chatRepository.deleteChat(any()) } returns Result.success(Unit)
        coEvery { chatRepository.archiveChat(any(), any()) } returns Result.success(Unit)
        coEvery { chatRepository.muteChat(any(), any()) } returns Result.success(Unit)
        coEvery { contactRepository.syncContacts() } returns Result.success(emptyList())
        every { contactRepository.getContacts() } returns flowOf(emptyList())
        every { userRepository.observeUser(any()) } returns emptyFlow()

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
        // Setup: 3 pinned chats already
        val pinnedChats = (1..3).map { Chat(id = "pin$it", type = ChatType.INDIVIDUAL, isPinned = true) }
        every { chatRepository.getChats() } returns flowOf(pinnedChats)

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

        assertTrue(vm.uiState.value.error?.contains("pin up to 3") == true)
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
        viewModel.togglePin("newChat", false) // triggers "pin up to 3" if enough pinned

        viewModel.clearError()

        assertNull(viewModel.uiState.value.error)
    }
}
