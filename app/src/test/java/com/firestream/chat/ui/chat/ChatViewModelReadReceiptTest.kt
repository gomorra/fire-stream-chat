package com.firestream.chat.ui.chat

import androidx.core.app.NotificationManagerCompat
import androidx.lifecycle.SavedStateHandle
import com.firestream.chat.data.local.PreferencesDataStore
import com.firestream.chat.data.remote.LinkPreviewSource
import com.firestream.chat.domain.model.Chat
import com.firestream.chat.domain.model.ChatType
import com.firestream.chat.domain.model.Message
import com.firestream.chat.domain.model.MessageStatus
import com.firestream.chat.domain.model.User
import com.firestream.chat.domain.repository.AuthRepository
import com.firestream.chat.domain.repository.ChatRepository
import com.firestream.chat.domain.repository.ListRepository
import com.firestream.chat.domain.repository.MessageRepository
import com.firestream.chat.domain.repository.PollRepository
import com.firestream.chat.domain.repository.UserRepository
import com.firestream.chat.data.util.MediaFileManager
import com.firestream.chat.domain.usecase.chat.CheckGroupPermissionUseCase
import com.firestream.chat.domain.usecase.message.SearchMessagesUseCase
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.unmockkAll
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class ChatViewModelReadReceiptTest {

    private val testDispatcher = StandardTestDispatcher()

    // extraBufferCapacity=1 so emit() returns immediately without suspending
    private val messagesFlow = MutableSharedFlow<List<Message>>(replay = 0, extraBufferCapacity = 1)
    private val readReceiptsFlow = MutableStateFlow(true)
    private val recipientFlow = MutableSharedFlow<User>(replay = 1)

    private val checkGroupPermissionUseCase = mockk<CheckGroupPermissionUseCase>()
    private val searchMessagesUseCase = mockk<SearchMessagesUseCase>()
    private val linkPreviewSource = mockk<LinkPreviewSource>()
    private val authRepository = mockk<AuthRepository>()
    private val chatRepository = mockk<ChatRepository>()
    private val listRepository = mockk<ListRepository>()
    private val messageRepository = mockk<MessageRepository>()
    private val pollRepository = mockk<PollRepository>()
    private val userRepository = mockk<UserRepository>()
    private val preferencesDataStore = mockk<PreferencesDataStore>()
    private val mediaFileManager = mockk<MediaFileManager>(relaxed = true)
    private val context = mockk<android.content.Context>(relaxed = true)

    private lateinit var viewModel: ChatViewModel

    @Before
    fun setUp() {
        Dispatchers.setMain(testDispatcher)

        // Mock NotificationManagerCompat static factory so cancel() doesn't crash on JVM
        mockkStatic(NotificationManagerCompat::class)
        every { NotificationManagerCompat.from(any()) } returns mockk(relaxed = true)

        // Auth
        every { authRepository.currentUserId } returns "uid1"

        // Messages
        every { messageRepository.getMessages(any()) } returns messagesFlow
        every { linkPreviewSource.extractUrl(any()) } returns null

        // Chat
        every { chatRepository.observeTyping(any()) } returns emptyFlow()
        every { chatRepository.getChats() } returns flowOf(emptyList<Chat>())
        coEvery { chatRepository.getChatById(any()) } returns Result.success(
            Chat(id = "chat1", type = ChatType.INDIVIDUAL)
        )
        coEvery { chatRepository.resetUnreadCount(any()) } returns Result.success(Unit)

        // Preferences
        every { preferencesDataStore.readReceiptsFlow } returns readReceiptsFlow
        every { preferencesDataStore.recentEmojisFlow } returns flowOf(emptyList())

        // Recipient
        every { userRepository.observeUser(any()) } returns recipientFlow

        // Delivery/read — the calls under test
        coEvery { messageRepository.markMessagesAsDelivered(any(), any()) } returns Result.success(Unit)
        coEvery { messageRepository.markMessagesAsRead(any(), any()) } returns Result.success(Unit)

        viewModel = buildViewModel()
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
        unmockkAll()
    }

    // ── Two-phase flow ────────────────────────────────────────────────────────

    @Test
    fun `SENT messages are marked DELIVERED before READ`() = runTest {
        viewModel.setScreenVisible(true)
        runCurrent()

        messagesFlow.emit(listOf(Message(id = "m1", senderId = "other", status = MessageStatus.SENT)))
        runCurrent()

        coVerify(exactly = 1) { messageRepository.markMessagesAsDelivered("chat1", listOf("m1")) }
        coVerify(exactly = 0) { messageRepository.markMessagesAsRead(any(), any()) }
    }

    @Test
    fun `DELIVERED messages are marked READ after 1500ms delay`() = runTest {
        viewModel.setScreenVisible(true)
        runCurrent()

        messagesFlow.emit(listOf(Message(id = "m1", senderId = "other", status = MessageStatus.DELIVERED)))
        runCurrent() // collectLatest fires, readReceiptJob starts and suspends at delay(1500)

        // Delay has not fired yet — no read marking
        coVerify(exactly = 0) { messageRepository.markMessagesAsRead(any(), any()) }

        advanceTimeBy(1500)
        runCurrent() // delay fires, markMessagesAsRead runs

        coVerify(exactly = 1) { messageRepository.markMessagesAsRead("chat1", listOf("m1")) }
    }

    // ── Privacy guards ────────────────────────────────────────────────────────

    @Test
    fun `READ marking skipped when readReceiptsAllowed is false`() = runTest {
        readReceiptsFlow.value = false
        runCurrent() // let ViewModel collect the pref change → readReceiptsAllowed = false

        viewModel.setScreenVisible(true)
        messagesFlow.emit(listOf(Message(id = "m1", senderId = "other", status = MessageStatus.DELIVERED)))
        runCurrent() // code returns early at readReceiptsAllowed check — no job started

        coVerify(exactly = 0) { messageRepository.markMessagesAsRead(any(), any()) }
    }

    @Test
    fun `READ marking skipped when recipient has receipts disabled`() = runTest {
        recipientFlow.emit(User(uid = "recipient1", readReceiptsEnabled = false))
        runCurrent() // observeRecipient fires → readReceiptsAllowed = false

        viewModel.setScreenVisible(true)
        messagesFlow.emit(listOf(Message(id = "m1", senderId = "other", status = MessageStatus.DELIVERED)))
        runCurrent() // code returns early at readReceiptsAllowed check — no job started

        coVerify(exactly = 0) { messageRepository.markMessagesAsRead(any(), any()) }
    }

    // ── Visibility guard ──────────────────────────────────────────────────────

    @Test
    fun `No marking when screen is not visible`() = runTest {
        // screenVisible stays false (default) — markIncomingMessagesAsRead returns immediately
        messagesFlow.emit(listOf(Message(id = "m1", senderId = "other", status = MessageStatus.SENT)))
        runCurrent()

        coVerify(exactly = 0) { messageRepository.markMessagesAsDelivered(any(), any()) }
        coVerify(exactly = 0) { messageRepository.markMessagesAsRead(any(), any()) }
    }

    @Test
    fun `READ receipt job cancelled when screen becomes invisible`() = runTest {
        viewModel.setScreenVisible(true)
        runCurrent()

        messagesFlow.emit(listOf(Message(id = "m1", senderId = "other", status = MessageStatus.DELIVERED)))
        runCurrent() // readReceiptJob starts, suspended at delay(1500)

        advanceTimeBy(500) // halfway through delay, job still pending
        runCurrent()

        viewModel.setScreenVisible(false) // cancels readReceiptJob
        runCurrent()

        advanceTimeBy(2000) // well past 1500ms, but job was cancelled
        runCurrent()

        coVerify(exactly = 0) { messageRepository.markMessagesAsRead(any(), any()) }
    }

    // ── Sender filter ─────────────────────────────────────────────────────────

    @Test
    fun `Own messages are not marked`() = runTest {
        viewModel.setScreenVisible(true)
        runCurrent()

        val ownSent = Message(id = "m1", senderId = "uid1", status = MessageStatus.SENT)
        val ownDelivered = Message(id = "m2", senderId = "uid1", status = MessageStatus.DELIVERED)
        messagesFlow.emit(listOf(ownSent, ownDelivered))
        runCurrent() // senderId == currentUserId, so needsDelivery and needsRead are both empty

        coVerify(exactly = 0) { messageRepository.markMessagesAsDelivered(any(), any()) }
        coVerify(exactly = 0) { messageRepository.markMessagesAsRead(any(), any()) }
    }

    // ── Constructor helper ────────────────────────────────────────────────────

    private fun buildViewModel() = ChatViewModel(
        savedStateHandle = SavedStateHandle(mapOf("chatId" to "chat1", "recipientId" to "recipient1")),
        checkGroupPermissionUseCase = checkGroupPermissionUseCase,
        searchMessagesUseCase = searchMessagesUseCase,
        linkPreviewSource = linkPreviewSource,
        authRepository = authRepository,
        chatRepository = chatRepository,
        listRepository = listRepository,
        messageRepository = messageRepository,
        pollRepository = pollRepository,
        userRepository = userRepository,
        preferencesDataStore = preferencesDataStore,
        mediaFileManager = mediaFileManager,
        context = context
    )
}
