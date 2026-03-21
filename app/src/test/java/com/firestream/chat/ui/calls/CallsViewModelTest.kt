package com.firestream.chat.ui.calls

import com.firestream.chat.domain.model.CallDirection
import com.firestream.chat.domain.model.Chat
import com.firestream.chat.domain.model.ChatType
import com.firestream.chat.domain.model.Contact
import com.firestream.chat.domain.model.Message
import com.firestream.chat.domain.model.MessageType
import com.firestream.chat.domain.model.User
import com.firestream.chat.domain.repository.AuthRepository
import com.firestream.chat.domain.repository.ContactRepository
import com.firestream.chat.domain.repository.UserRepository
import com.firestream.chat.domain.usecase.call.GetCallLogUseCase
import com.firestream.chat.domain.usecase.chat.GetChatsUseCase
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class CallsViewModelTest {

    private val testDispatcher = StandardTestDispatcher()

    private val getCallLogUseCase = mockk<GetCallLogUseCase>()
    private val getChatsUseCase = mockk<GetChatsUseCase>()
    private val authRepository = mockk<AuthRepository>()
    private val contactRepository = mockk<ContactRepository>()
    private val userRepository = mockk<UserRepository>()

    private val currentUserId = "user1"
    private val otherUserId = "user2"
    private val chatId = "chat123"

    private val testChat = Chat(
        id = chatId,
        type = ChatType.INDIVIDUAL,
        participants = listOf(currentUserId, otherUserId)
    )
    private val testContact = Contact(
        uid = otherUserId,
        displayName = "Alice",
        phoneNumber = "+1234",
        avatarUrl = null,
        isRegistered = true
    )

    @Before
    fun setUp() {
        Dispatchers.setMain(testDispatcher)
        every { authRepository.currentUserId } returns currentUserId
        every { contactRepository.getContacts() } returns flowOf(listOf(testContact))
        every { userRepository.observeUser(any()) } returns flowOf(
            User(uid = otherUserId, displayName = "Alice", phoneNumber = "+1234")
        )
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    private fun buildViewModel() = CallsViewModel(
        getCallLogUseCase = getCallLogUseCase,
        getChatsUseCase = getChatsUseCase,
        authRepository = authRepository,
        contactRepository = contactRepository,
        userRepository = userRepository
    )

    @Test
    fun `entries are empty when no CALL messages`() = runTest {
        every { getCallLogUseCase() } returns flowOf(emptyList())
        every { getChatsUseCase() } returns flowOf(listOf(testChat))

        val vm = buildViewModel()
        advanceUntilIdle()

        assertEquals(emptyList<Any>(), vm.uiState.value.entries)
    }

    @Test
    fun `isLoading transitions to false after first emission`() = runTest {
        every { getCallLogUseCase() } returns flowOf(emptyList())
        every { getChatsUseCase() } returns flowOf(listOf(testChat))

        val vm = buildViewModel()
        advanceUntilIdle()

        assertFalse(vm.uiState.value.isLoading)
    }

    @Test
    fun `outgoing call entry derived correctly`() = runTest {
        val message = Message(
            id = "m1", chatId = chatId, senderId = currentUserId,
            type = MessageType.CALL, content = "remote_hangup", duration = 120
        )
        every { getCallLogUseCase() } returns flowOf(listOf(message))
        every { getChatsUseCase() } returns flowOf(listOf(testChat))

        val vm = buildViewModel()
        advanceUntilIdle()

        val entry = vm.uiState.value.entries.single()
        assertEquals(CallDirection.OUTGOING, entry.direction)
        assertEquals(otherUserId, entry.otherPartyId)
        assertEquals(120, entry.durationSeconds)
    }

    @Test
    fun `incoming call entry derived correctly`() = runTest {
        val message = Message(
            id = "m1", chatId = chatId, senderId = otherUserId,
            type = MessageType.CALL, content = "hangup", duration = 60
        )
        every { getCallLogUseCase() } returns flowOf(listOf(message))
        every { getChatsUseCase() } returns flowOf(listOf(testChat))

        val vm = buildViewModel()
        advanceUntilIdle()

        assertEquals(CallDirection.INCOMING, vm.uiState.value.entries.single().direction)
    }

    @Test
    fun `missed call entry derived correctly for declined`() = runTest {
        val message = Message(
            id = "m1", chatId = chatId, senderId = otherUserId,
            type = MessageType.CALL, content = "declined", duration = null
        )
        every { getCallLogUseCase() } returns flowOf(listOf(message))
        every { getChatsUseCase() } returns flowOf(listOf(testChat))

        val vm = buildViewModel()
        advanceUntilIdle()

        assertEquals(CallDirection.MISSED, vm.uiState.value.entries.single().direction)
    }

    @Test
    fun `missed call entry derived correctly for timeout`() = runTest {
        val message = Message(
            id = "m1", chatId = chatId, senderId = otherUserId,
            type = MessageType.CALL, content = "timeout", duration = null
        )
        every { getCallLogUseCase() } returns flowOf(listOf(message))
        every { getChatsUseCase() } returns flowOf(listOf(testChat))

        val vm = buildViewModel()
        advanceUntilIdle()

        assertEquals(CallDirection.MISSED, vm.uiState.value.entries.single().direction)
    }

    @Test
    fun `display name resolved from contacts map`() = runTest {
        val message = Message(
            id = "m1", chatId = chatId, senderId = otherUserId,
            type = MessageType.CALL, content = "hangup"
        )
        every { getCallLogUseCase() } returns flowOf(listOf(message))
        every { getChatsUseCase() } returns flowOf(listOf(testChat))

        val vm = buildViewModel()
        advanceUntilIdle()

        assertEquals("Alice", vm.uiState.value.entries.single().displayName)
    }

    @Test
    fun `deriveDirection returns OUTGOING when sender is current user`() {
        assertEquals(
            CallDirection.OUTGOING,
            CallsViewModel.deriveDirection("me", "timeout", "me")
        )
    }

    @Test
    fun `deriveDirection returns INCOMING for hangup from other user`() {
        assertEquals(
            CallDirection.INCOMING,
            CallsViewModel.deriveDirection("other", "hangup", "me")
        )
    }

    @Test
    fun `deriveDirection returns MISSED for error from other user`() {
        assertEquals(
            CallDirection.MISSED,
            CallsViewModel.deriveDirection("other", "error", "me")
        )
    }
}
