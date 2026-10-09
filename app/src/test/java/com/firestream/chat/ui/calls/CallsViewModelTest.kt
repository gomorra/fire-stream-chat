package com.firestream.chat.ui.calls

import com.firestream.chat.domain.model.CallLogType
import com.firestream.chat.domain.model.Chat
import com.firestream.chat.domain.model.ChatType
import com.firestream.chat.domain.model.Contact
import com.firestream.chat.domain.model.Message
import com.firestream.chat.domain.model.MessageType
import com.firestream.chat.domain.model.User
import com.firestream.chat.domain.repository.AuthRepository
import com.firestream.chat.domain.repository.ChatRepository
import com.firestream.chat.domain.repository.ContactRepository
import com.firestream.chat.domain.repository.MessageRepository
import com.firestream.chat.domain.repository.UserRepository
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
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
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class CallsViewModelTest {

    private val testDispatcher = StandardTestDispatcher()

    private val messageRepository = mockk<MessageRepository>()
    private val chatRepository = mockk<ChatRepository>()
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
        messageRepository = messageRepository,
        chatRepository = chatRepository,
        authRepository = authRepository,
        contactRepository = contactRepository,
        userRepository = userRepository
    )

    @Test
    fun `entries are empty when no CALL messages`() = runTest {
        every { messageRepository.getCallLog() } returns flowOf(emptyList())
        every { chatRepository.getChats() } returns flowOf(listOf(testChat))

        val vm = buildViewModel()
        advanceUntilIdle()

        assertEquals(emptyList<Any>(), vm.uiState.value.entries)
    }

    @Test
    fun `isLoading transitions to false after first emission`() = runTest {
        every { messageRepository.getCallLog() } returns flowOf(emptyList())
        every { chatRepository.getChats() } returns flowOf(listOf(testChat))

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
        every { messageRepository.getCallLog() } returns flowOf(listOf(message))
        every { chatRepository.getChats() } returns flowOf(listOf(testChat))

        val vm = buildViewModel()
        advanceUntilIdle()

        val entry = vm.uiState.value.entries.single()
        assertEquals(CallLogType.OUTGOING, entry.type)
        assertEquals(otherUserId, entry.otherPartyId)
        assertEquals(120, entry.durationSeconds)
    }

    @Test
    fun `incoming call entry derived correctly`() = runTest {
        val message = Message(
            id = "m1", chatId = chatId, senderId = otherUserId,
            type = MessageType.CALL, content = "hangup", duration = 60
        )
        every { messageRepository.getCallLog() } returns flowOf(listOf(message))
        every { chatRepository.getChats() } returns flowOf(listOf(testChat))

        val vm = buildViewModel()
        advanceUntilIdle()

        assertEquals(CallLogType.INCOMING, vm.uiState.value.entries.single().type)
    }

    @Test
    fun `a call the caller cancelled while it rang is missed for the callee`() = runTest {
        // CallService logs a cancel during ringing as "hangup" with no connected time.
        val message = Message(
            id = "m1", chatId = chatId, senderId = otherUserId,
            type = MessageType.CALL, content = "hangup", duration = 0
        )
        every { messageRepository.getCallLog() } returns flowOf(listOf(message))
        every { chatRepository.getChats() } returns flowOf(listOf(testChat))

        val vm = buildViewModel()
        advanceUntilIdle()

        assertEquals(CallLogType.MISSED, vm.uiState.value.entries.single().type)
    }

    @Test
    fun `a connected call that ended in an error is incoming for the callee`() = runTest {
        val message = Message(
            id = "m1", chatId = chatId, senderId = otherUserId,
            type = MessageType.CALL, content = "error", duration = 95
        )
        every { messageRepository.getCallLog() } returns flowOf(listOf(message))
        every { chatRepository.getChats() } returns flowOf(listOf(testChat))

        val vm = buildViewModel()
        advanceUntilIdle()

        assertEquals(CallLogType.INCOMING, vm.uiState.value.entries.single().type)
    }

    @Test
    fun `a call I declined is declined`() = runTest {
        val message = Message(
            id = "m1", chatId = chatId, senderId = otherUserId,
            type = MessageType.CALL, content = "declined", duration = null
        )
        every { messageRepository.getCallLog() } returns flowOf(listOf(message))
        every { chatRepository.getChats() } returns flowOf(listOf(testChat))

        val vm = buildViewModel()
        advanceUntilIdle()

        assertEquals(CallLogType.DECLINED, vm.uiState.value.entries.single().type)
    }

    @Test
    fun `missed call entry derived correctly for timeout`() = runTest {
        val message = Message(
            id = "m1", chatId = chatId, senderId = otherUserId,
            type = MessageType.CALL, content = "timeout", duration = null
        )
        every { messageRepository.getCallLog() } returns flowOf(listOf(message))
        every { chatRepository.getChats() } returns flowOf(listOf(testChat))

        val vm = buildViewModel()
        advanceUntilIdle()

        assertEquals(CallLogType.MISSED, vm.uiState.value.entries.single().type)
    }

    @Test
    fun `a caller who is not a contact is named from their profile`() = runTest {
        every { contactRepository.getContacts() } returns flowOf(emptyList())
        val message = Message(
            id = "m1", chatId = chatId, senderId = otherUserId,
            type = MessageType.CALL, content = "hangup", duration = 30
        )
        every { messageRepository.getCallLog() } returns flowOf(listOf(message))
        every { chatRepository.getChats() } returns flowOf(listOf(testChat))

        val vm = buildViewModel()
        advanceUntilIdle()

        assertEquals("Alice", vm.uiState.value.entries.single().displayName)
    }

    @Test
    fun `names update when contacts finish loading after the call log`() = runTest {
        val contacts = MutableStateFlow<List<Contact>>(emptyList())
        every { contactRepository.getContacts() } returns contacts
        every { userRepository.observeUser(any()) } returns emptyFlow()
        val message = Message(
            id = "m1", chatId = chatId, senderId = otherUserId,
            type = MessageType.CALL, content = "hangup", duration = 30
        )
        every { messageRepository.getCallLog() } returns flowOf(listOf(message))
        every { chatRepository.getChats() } returns flowOf(listOf(testChat))
        val vm = buildViewModel()
        advanceUntilIdle()

        contacts.value = listOf(testContact)
        advanceUntilIdle()

        assertEquals("Alice", vm.uiState.value.entries.single().displayName)
    }

    /** A received call from Bob, who is not a contact, so only Bob's profile carries the name. */
    private fun givenCallFromNonContactBob() {
        every { userRepository.observeUser(otherUserId) } returns flowOf(
            User(uid = otherUserId, displayName = "Bob", phoneNumber = "+5678")
        )
        val message = Message(
            id = "m1", chatId = chatId, senderId = otherUserId,
            type = MessageType.CALL, content = "hangup", duration = 30
        )
        every { messageRepository.getCallLog() } returns flowOf(listOf(message))
        every { chatRepository.getChats() } returns flowOf(listOf(testChat))
    }

    @Test
    fun `a caller who is not a contact keeps their name when the contacts load again`() = runTest {
        // Room emits the contacts again on every change, a sync or a cached avatar among them.
        val contacts = MutableStateFlow<List<Contact>>(emptyList())
        every { contactRepository.getContacts() } returns contacts
        givenCallFromNonContactBob()
        val vm = buildViewModel()
        advanceUntilIdle()

        contacts.value = listOf(testContact.copy(uid = "user3", displayName = "Carol"))
        advanceUntilIdle()

        assertEquals("Bob", vm.uiState.value.entries.single().displayName)
        assertEquals("Bob", vm.uiState.value.contacts[otherUserId]?.displayName)
    }

    @Test
    fun `a caller who is not a contact keeps their name after a refresh`() = runTest {
        every { contactRepository.getContacts() } returns flowOf(emptyList())
        givenCallFromNonContactBob()
        val vm = buildViewModel()
        advanceUntilIdle()

        vm.refresh()
        advanceUntilIdle()

        assertEquals("Bob", vm.uiState.value.entries.single().displayName)
    }

    @Test
    fun `an entry carries the kind the call was started as`() = runTest {
        val video = Message(
            id = "m1", chatId = chatId, senderId = currentUserId,
            type = MessageType.CALL, content = "hangup", duration = 30, isVideoCall = true
        )
        val voice = Message(
            id = "m2", chatId = chatId, senderId = otherUserId,
            type = MessageType.CALL, content = "hangup", duration = 30
        )
        every { messageRepository.getCallLog() } returns flowOf(listOf(video, voice))
        every { chatRepository.getChats() } returns flowOf(listOf(testChat))

        val vm = buildViewModel()
        advanceUntilIdle()

        assertEquals(listOf(true, false), vm.uiState.value.entries.map { it.video })
    }

    @Test
    fun `display name resolved from contacts map`() = runTest {
        val message = Message(
            id = "m1", chatId = chatId, senderId = otherUserId,
            type = MessageType.CALL, content = "hangup"
        )
        every { messageRepository.getCallLog() } returns flowOf(listOf(message))
        every { chatRepository.getChats() } returns flowOf(listOf(testChat))

        val vm = buildViewModel()
        advanceUntilIdle()

        assertEquals("Alice", vm.uiState.value.entries.single().displayName)
    }
}
