package com.firestream.chat.ui.chat

import androidx.core.app.NotificationManagerCompat
import androidx.lifecycle.SavedStateHandle
import com.firestream.chat.data.local.PreferencesDataStore
import com.firestream.chat.data.remote.LinkPreviewSource
import com.firestream.chat.data.remote.fcm.ActiveChatTracker
import com.firestream.chat.data.util.MediaFileManager
import com.firestream.chat.domain.model.Chat
import com.firestream.chat.domain.model.ChatType
import com.firestream.chat.domain.model.Message
import com.firestream.chat.domain.model.MessageStatus
import com.firestream.chat.domain.model.User
import com.firestream.chat.domain.repository.AuthRepository
import com.firestream.chat.domain.repository.ListRepository
import com.firestream.chat.domain.repository.PollRepository
import com.firestream.chat.domain.usecase.chat.CheckGroupPermissionUseCase
import com.firestream.chat.domain.usecase.message.SearchMessagesUseCase
import com.firestream.chat.test.fakes.FakeChatRepository
import com.firestream.chat.test.fakes.FakeMessageRepository
import com.firestream.chat.test.fakes.FakeUserRepository
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.unmockkAll
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class ChatViewModelReadReceiptTest {

    private val testDispatcher = StandardTestDispatcher()

    private val chatRepository = FakeChatRepository()
    private val messageRepository = FakeMessageRepository()
    private val userRepository = FakeUserRepository()

    private val checkGroupPermissionUseCase = mockk<CheckGroupPermissionUseCase>()
    private val searchMessagesUseCase = mockk<SearchMessagesUseCase>()
    private val linkPreviewSource = mockk<LinkPreviewSource>()
    private val authRepository = mockk<AuthRepository>()
    private val listRepository = mockk<ListRepository>()
    private val pollRepository = mockk<PollRepository>()
    private val preferencesDataStore = mockk<PreferencesDataStore>()
    private val mediaFileManager = mockk<MediaFileManager>(relaxed = true)
    private val activeChatTracker = mockk<ActiveChatTracker>(relaxed = true)
    private val context = mockk<android.content.Context>(relaxed = true)

    private lateinit var viewModel: ChatViewModel

    @Before
    fun setUp() {
        Dispatchers.setMain(testDispatcher)

        mockkStatic(NotificationManagerCompat::class)
        every { NotificationManagerCompat.from(any()) } returns mockk(relaxed = true)

        every { authRepository.currentUserId } returns "uid1"
        every { linkPreviewSource.extractUrl(any()) } returns null
        every { preferencesDataStore.readReceiptsFlow } returns flowOf(true)
        every { preferencesDataStore.recentEmojisFlow } returns flowOf(emptyList())

        chatRepository.chatByIdResult = Result.success(Chat(id = "chat1", type = ChatType.INDIVIDUAL))

        viewModel = buildViewModel()
    }

    @After
    fun tearDown() {
        chatRepository.reset()
        messageRepository.reset()
        unmockkAll()
        Dispatchers.resetMain()
    }

    // ── Two-phase flow ────────────────────────────────────────────────────────────

    @Test
    fun `SENT messages are marked DELIVERED before READ`() = runTest {
        viewModel.setScreenVisible(true)
        runCurrent()

        messageRepository.emit("chat1", listOf(Message(id = "m1", senderId = "other", status = MessageStatus.SENT)))
        runCurrent()

        assertEquals(1, messageRepository.markAsDeliveredCalls.size)
        assertEquals("chat1" to listOf("m1"), messageRepository.markAsDeliveredCalls.last())
        assertTrue(messageRepository.markAsReadCalls.isEmpty())
    }

    @Test
    fun `DELIVERED messages are marked READ after 1500ms delay`() = runTest {
        viewModel.setScreenVisible(true)
        runCurrent()

        messageRepository.emit("chat1", listOf(Message(id = "m1", senderId = "other", status = MessageStatus.DELIVERED)))
        runCurrent()

        assertTrue(messageRepository.markAsReadCalls.isEmpty())

        advanceTimeBy(1500)
        runCurrent()

        assertEquals(1, messageRepository.markAsReadCalls.size)
        assertEquals("chat1" to listOf("m1"), messageRepository.markAsReadCalls.last())
    }

    // ── Privacy guards ────────────────────────────────────────────────────────────

    @Test
    fun `READ marking skipped when readReceiptsAllowed is false`() = runTest {
        every { preferencesDataStore.readReceiptsFlow } returns flowOf(false)
        val vm = buildViewModel()

        vm.setScreenVisible(true)
        messageRepository.emit("chat1", listOf(Message(id = "m1", senderId = "other", status = MessageStatus.DELIVERED)))
        runCurrent()

        assertTrue(messageRepository.markAsReadCalls.isEmpty())
    }

    @Test
    fun `READ marking skipped when recipient has receipts disabled`() = runTest {
        userRepository.emitUser(User(uid = "recipient1", readReceiptsEnabled = false))
        runCurrent()

        viewModel.setScreenVisible(true)
        messageRepository.emit("chat1", listOf(Message(id = "m1", senderId = "other", status = MessageStatus.DELIVERED)))
        runCurrent()

        assertTrue(messageRepository.markAsReadCalls.isEmpty())
    }

    // ── Visibility guard ──────────────────────────────────────────────────────────

    @Test
    fun `No marking when screen is not visible`() = runTest {
        messageRepository.emit("chat1", listOf(Message(id = "m1", senderId = "other", status = MessageStatus.SENT)))
        runCurrent()

        assertTrue(messageRepository.markAsDeliveredCalls.isEmpty())
        assertTrue(messageRepository.markAsReadCalls.isEmpty())
    }

    @Test
    fun `READ receipt job cancelled when screen becomes invisible`() = runTest {
        viewModel.setScreenVisible(true)
        runCurrent()

        messageRepository.emit("chat1", listOf(Message(id = "m1", senderId = "other", status = MessageStatus.DELIVERED)))
        runCurrent()

        advanceTimeBy(500)
        runCurrent()

        viewModel.setScreenVisible(false)
        runCurrent()

        advanceTimeBy(2000)
        runCurrent()

        assertTrue(messageRepository.markAsReadCalls.isEmpty())
    }

    // ── Sender filter ─────────────────────────────────────────────────────────────

    @Test
    fun `Own messages are not marked`() = runTest {
        viewModel.setScreenVisible(true)
        runCurrent()

        messageRepository.emit(
            "chat1",
            listOf(
                Message(id = "m1", senderId = "uid1", status = MessageStatus.SENT),
                Message(id = "m2", senderId = "uid1", status = MessageStatus.DELIVERED),
            )
        )
        runCurrent()

        assertTrue(messageRepository.markAsDeliveredCalls.isEmpty())
        assertTrue(messageRepository.markAsReadCalls.isEmpty())
    }

    // ── Constructor helper ────────────────────────────────────────────────────────

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
        activeChatTracker = activeChatTracker,
        appScope = TestScope(testDispatcher),
        context = context
    )
}
