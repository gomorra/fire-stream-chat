package com.firestream.chat.ui.chat

import androidx.core.app.NotificationManagerCompat
import androidx.lifecycle.SavedStateHandle
import com.firestream.chat.data.local.PreferencesDataStore
import com.firestream.chat.data.local.ScrollPos
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
import io.mockk.coVerify
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
 * Exercises the cross-process scroll-restore path in [ChatViewModel]. The SavedStateHandle
 * branch is covered by the existing config-change path; this test locks in the chatId fence
 * so a persisted offset for chat A is never applied when chat B is opened.
 */
class ChatViewModelScrollRestoreTest {

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
        appScope = TestScope(mainDispatcherRule.testDispatcher),
        context = context,
    )

    @Test
    fun `readPersistedScroll returns position when chatId matches`() = runTest {
        every { preferencesDataStore.lastChatScrollFlow } returns flowOf(
            ScrollPos(chatId = "chat1", index = 12, offset = 34)
        )

        val result = buildViewModel().readPersistedScroll()

        assertEquals(ScrollPos("chat1", 12, 34), result)
    }

    @Test
    fun `readPersistedScroll returns null when chatId does not match`() = runTest {
        // Persisted scroll belongs to a different chat — the fence must reject it
        // so we don't apply chat A's offset to chat B.
        every { preferencesDataStore.lastChatScrollFlow } returns flowOf(
            ScrollPos(chatId = "someOtherChat", index = 12, offset = 34)
        )

        val result = buildViewModel().readPersistedScroll()

        assertNull(result)
    }

    @Test
    fun `readPersistedScroll returns null when nothing is persisted`() = runTest {
        every { preferencesDataStore.lastChatScrollFlow } returns flowOf(null)

        val result = buildViewModel().readPersistedScroll()

        assertNull(result)
    }

    @Test
    fun `persistScrollPosition forwards to DataStore with current chatId`() = runTest {
        buildViewModel().persistScrollPosition(index = 7, offset = 99)
        // appScope uses the test dispatcher; runTest's scheduler drains pending work.
        mainDispatcherRule.testDispatcher.scheduler.advanceUntilIdle()

        coVerify { preferencesDataStore.setLastChatScroll("chat1", 7, 99) }
    }
}
