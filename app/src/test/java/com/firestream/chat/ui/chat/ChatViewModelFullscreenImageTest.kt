package com.firestream.chat.ui.chat

import androidx.core.app.NotificationManagerCompat
import androidx.lifecycle.SavedStateHandle
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
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.unmockkAll
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Rule
import org.junit.Test

/**
 * Regression test for the fullscreen-image-viewer rotation bug: the open viewer
 * used to live in screen-local compose state, so activity recreation (rotation)
 * silently closed it and dropped the user back into the chat. The state now
 * lives in [ChatUiState.overlays] — retained with the ViewModel across
 * configuration changes — and these tests pin that ownership: the viewer state
 * must round-trip through the ViewModel, not through the screen.
 */
class ChatViewModelFullscreenImageTest {

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

    private fun buildViewModel(): ChatViewModel = ChatViewModel(
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

    @Test
    fun `viewer is closed by default`() = runTest {
        assertNull(buildViewModel().uiState.value.overlays.fullscreenImage)
    }

    @Test
    fun `showFullscreenImage exposes the image in the overlays slice`() = runTest {
        val viewModel = buildViewModel()
        val image = FullscreenImage(
            imageUrl = "https://example.com/img.jpg",
            localUri = "/data/local/img.jpg",
            canSaveToDownloads = true,
        )

        viewModel.showFullscreenImage(image)

        assertEquals(image, viewModel.uiState.value.overlays.fullscreenImage)
    }

    @Test
    fun `dismissFullscreenImage clears the overlay`() = runTest {
        val viewModel = buildViewModel()
        viewModel.showFullscreenImage(FullscreenImage(imageUrl = "https://example.com/img.jpg"))

        viewModel.dismissFullscreenImage()

        assertNull(viewModel.uiState.value.overlays.fullscreenImage)
    }

    @Test
    fun `showing a second image replaces the first`() = runTest {
        val viewModel = buildViewModel()
        viewModel.showFullscreenImage(FullscreenImage(imageUrl = "https://example.com/a.jpg"))

        val second = FullscreenImage(imageUrl = "https://example.com/b.jpg")
        viewModel.showFullscreenImage(second)

        assertEquals(second, viewModel.uiState.value.overlays.fullscreenImage)
    }
}
