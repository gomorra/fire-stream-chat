package com.firestream.chat.ui.chat

import androidx.core.app.NotificationManagerCompat
import androidx.lifecycle.SavedStateHandle
import app.cash.turbine.test
import com.firestream.chat.data.local.PreferencesDataStore
import com.firestream.chat.data.remote.LinkPreviewSource
import com.firestream.chat.data.remote.fcm.ActiveChatTracker
import com.firestream.chat.data.util.MediaFileManager
import com.firestream.chat.domain.model.Chat
import com.firestream.chat.domain.model.ChatType
import com.firestream.chat.domain.repository.AuthRepository
import com.firestream.chat.domain.repository.ListRepository
import com.firestream.chat.domain.repository.PollRepository
import com.firestream.chat.domain.usecase.chat.CheckGroupPermissionUseCase
import com.firestream.chat.domain.usecase.message.SearchMessagesUseCase
import com.firestream.chat.test.MainDispatcherRule
import com.firestream.chat.test.fakes.FakeChatRepository
import com.firestream.chat.test.fakes.FakeMessageRepository
import com.firestream.chat.test.fakes.FakeUserRepository
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.unmockkAll
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test

class ChatViewModelBlockedStateTest {

    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    private val checkGroupPermissionUseCase = mockk<CheckGroupPermissionUseCase>(relaxed = true)
    private val searchMessagesUseCase = mockk<SearchMessagesUseCase>(relaxed = true)
    private val linkPreviewSource = mockk<LinkPreviewSource>(relaxed = true)
    private val authRepository = mockk<AuthRepository>(relaxed = true)
    private val listRepository = mockk<ListRepository>(relaxed = true)
    private val pollRepository = mockk<PollRepository>(relaxed = true)
    private val preferencesDataStore = mockk<PreferencesDataStore>(relaxed = true)
    private val mediaFileManager = mockk<MediaFileManager>(relaxed = true)
    private val activeChatTracker = mockk<ActiveChatTracker>(relaxed = true)
    private val speechRecognizerManager = mockk<com.firestream.chat.data.util.SpeechRecognizerManager>(relaxed = true)
    private val callStateHolder = com.firestream.chat.data.call.CallStateHolder()
    private val context = mockk<android.content.Context>(relaxed = true)

    private val chatRepository = FakeChatRepository()
    private val messageRepository = FakeMessageRepository()
    private val userRepository = FakeUserRepository()

    @Before
    fun setUp() {
        // Block ART/Robolectric crash from NotificationManagerCompat on JVM.
        mockkStatic(NotificationManagerCompat::class)
        every { NotificationManagerCompat.from(any()) } returns mockk(relaxed = true)

        every { authRepository.currentUserId } returns "uid1"
        every { linkPreviewSource.extractUrl(any()) } returns null
        every { preferencesDataStore.readReceiptsFlow } returns flowOf(true)
        every { preferencesDataStore.recentEmojisFlow } returns flowOf(emptyList())

        chatRepository.chatByIdResult = Result.success(Chat(id = "chat1", type = ChatType.INDIVIDUAL))
    }

    @After
    fun tearDown() {
        unmockkAll()
    }

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
        speechRecognizerManager = speechRecognizerManager,
        callStateHolder = callStateHolder,
        commandRegistry = com.firestream.chat.domain.command.CommandRegistry(emptySet()),
        timerAlarmScheduler = mockk(relaxed = true),
        appScope = TestScope(mainDispatcherRule.testDispatcher),
        context = context,
    )

    // ── isRecipientBlocked ────────────────────────────────────────────────

    @Test
    fun `uiState reflects blocked recipient after init`() = runTest {
        userRepository.setBlocked("recipient1", true)

        val vm = buildViewModel()

        // ChatInfoManager.start() launches refreshBlockState() into viewModelScope
        // backed by Dispatchers.Main (UnconfinedTestDispatcher) → already complete.
        assertTrue(vm.uiState.value.session.isRecipientBlocked)
    }

    @Test
    fun `uiState reflects unblocked recipient after init`() = runTest {
        userRepository.setBlocked("recipient1", false)

        val vm = buildViewModel()

        assertFalse(vm.uiState.value.session.isRecipientBlocked)
    }

    @Test
    fun `refreshBlockState picks up unblock toggled from elsewhere`() = runTest {
        userRepository.setBlocked("recipient1", true)
        val vm = buildViewModel()
        assertTrue(vm.uiState.value.session.isRecipientBlocked)

        userRepository.setBlocked("recipient1", false)
        vm.refreshBlockState()

        assertFalse(vm.uiState.value.session.isRecipientBlocked)
    }

    // ── clearError ────────────────────────────────────────────────────────

    @Test
    fun `clearError resets uiState error to null`() = runTest {
        val vm = buildViewModel()
        vm.clearError()
        assertNull(vm.uiState.value.session.error)
    }

    // ── saveImageToDownloads snackbar (TC-28) ─────────────────────────────

    @Test
    fun `saveImageToDownloads emits success snackbar when MediaFileManager succeeds`() = runTest {
        val vm = buildViewModel()
        coEvery { mediaFileManager.downloadAndSave(any(), any(), any()) } returns
            java.io.File("/tmp/fake.jpg")
        // Uri.parse is an Android stub on the JVM — return a mocked Uri rather
        // than parsing one. The ViewModel only checks emit success, not value.
        coEvery { mediaFileManager.saveToDownloads(any(), any()) } returns mockk(relaxed = true)

        vm.snackbarEvent.test {
            vm.saveImageToDownloads(localUri = null, mediaUrl = "https://cdn/img.jpg")
            assertEquals("Image saved to Downloads", awaitItem().message)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `saveImageToDownloads emits error snackbar when no source available`() = runTest {
        val vm = buildViewModel()

        vm.snackbarEvent.test {
            vm.saveImageToDownloads(localUri = null, mediaUrl = null)
            val event = awaitItem()
            assertTrue(
                "expected error snackbar, got '${event.message}'",
                event.message.startsWith("Failed to save"),
            )
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `saveImageToDownloads emits error snackbar when MediaFileManager throws`() = runTest {
        val vm = buildViewModel()
        coEvery { mediaFileManager.downloadAndSave(any(), any(), any()) } throws
            java.io.IOException("disk full")

        vm.snackbarEvent.test {
            vm.saveImageToDownloads(localUri = null, mediaUrl = "https://cdn/img.jpg")
            val event = awaitItem()
            assertTrue(
                "expected error snackbar, got '${event.message}'",
                event.message.contains("Failed to save") && event.message.contains("disk full"),
            )
            cancelAndIgnoreRemainingEvents()
        }
    }
}
