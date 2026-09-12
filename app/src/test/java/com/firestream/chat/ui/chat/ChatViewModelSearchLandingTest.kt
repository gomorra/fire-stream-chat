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
import com.firestream.chat.domain.model.MessageFilterType
import com.firestream.chat.domain.model.MessageSearchFilter
import com.firestream.chat.domain.model.MessageType
import com.firestream.chat.domain.reminder.DateTimeDetector
import com.firestream.chat.domain.repository.AuthRepository
import com.firestream.chat.domain.repository.ListRepository
import com.firestream.chat.domain.repository.PollRepository
import com.firestream.chat.domain.repository.ReminderRepository
import com.firestream.chat.domain.usecase.chat.CheckGroupPermissionUseCase
import com.firestream.chat.domain.usecase.message.SearchMessagesUseCase
import com.firestream.chat.test.MainDispatcherRule
import com.firestream.chat.test.fakes.FakeChatRepository
import com.firestream.chat.test.fakes.FakeConnectivityObserver
import com.firestream.chat.test.fakes.FakeMessageRepository
import com.firestream.chat.test.fakes.FakeUserRepository
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.unmockkAll
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test

/**
 * The landing path for a tapped search result.
 *
 * Search is the only caller that jumps into the message list with a target from
 * a *different* query: `getMessagesByChatId` has no `LIMIT` while the search
 * query is capped, so the day message paging lands, search is the caller whose
 * targets can be absent. Today the failure is reachable via a message deleted
 * between the query and the tap. Either way the tap must not be silently inert,
 * and the results the user paid a query for must survive it.
 */
class ChatViewModelSearchLandingTest {

    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    private val checkGroupPermissionUseCase = mockk<CheckGroupPermissionUseCase>(relaxed = true)
    private val linkPreviewSource = mockk<LinkPreviewSource>(relaxed = true)
    private val authRepository = mockk<AuthRepository>(relaxed = true)
    private val listRepository = mockk<ListRepository>(relaxed = true)
    private val pollRepository = mockk<PollRepository>(relaxed = true)
    private val reminderRepository = mockk<ReminderRepository>(relaxed = true) {
        every { observePendingIdsForChat(any()) } returns flowOf(emptySet())
    }
    private val dateTimeDetector = mockk<DateTimeDetector>(relaxed = true) {
        coEvery { detect(any(), any()) } returns null
    }
    private val preferencesDataStore = mockk<PreferencesDataStore>(relaxed = true)
    private val mediaFileManager = mockk<MediaFileManager>(relaxed = true)
    private val activeChatTracker = mockk<ActiveChatTracker>(relaxed = true)
    private val speechRecognizerManager =
        mockk<com.firestream.chat.data.util.SpeechRecognizerManager>(relaxed = true)
    private val callStateHolder = com.firestream.chat.data.call.CallStateHolder()
    private val context = mockk<android.content.Context>(relaxed = true)

    private val chatRepository = FakeChatRepository()
    private val messageRepository = FakeMessageRepository()
    private val userRepository = FakeUserRepository()
    private val searchMessagesUseCase = SearchMessagesUseCase(messageRepository)

    @Before
    fun setUp() {
        mockkStatic(NotificationManagerCompat::class)
        every { NotificationManagerCompat.from(any()) } returns mockk(relaxed = true)
        every { authRepository.currentUserId } returns "uid1"
        every { linkPreviewSource.extractUrl(any()) } returns null
        every { preferencesDataStore.readReceiptsFlow } returns flowOf(true)
        every { preferencesDataStore.recentEmojisFlow } returns flowOf(emptyList())
        every { preferencesDataStore.lastChatScrollFlow } returns flowOf(null)
        chatRepository.chatByIdResult = Result.success(Chat(id = "chat1", type = ChatType.INDIVIDUAL))
        messageRepository.emit(
            "chat1",
            listOf(
                Message(id = "m1", chatId = "chat1", senderId = "uid1", content = "hello harbour"),
                Message(
                    id = "p1",
                    chatId = "chat1",
                    senderId = "uid1",
                    content = "",
                    type = MessageType.IMAGE,
                    mediaUrl = "https://x/1.jpg",
                ),
            ),
        )
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
        reminderRepository = reminderRepository,
        dateTimeDetector = dateTimeDetector,
        pollRepository = pollRepository,
        userRepository = userRepository,
        preferencesDataStore = preferencesDataStore,
        mediaFileManager = mediaFileManager,
        imageEditRasterizer = mockk(relaxed = true),
        activeChatTracker = activeChatTracker,
        speechRecognizerManager = speechRecognizerManager,
        callStateHolder = callStateHolder,
        commandRegistry = com.firestream.chat.domain.command.CommandRegistry(emptySet()),
        timerAlarmScheduler = mockk(relaxed = true),
        connectivityObserver = FakeConnectivityObserver(),
        appScope = TestScope(mainDispatcherRule.testDispatcher),
        context = context,
    )

    @Test
    fun `a result that cannot be reached leaves the search open with its results`() = runTest {
        val viewModel = buildViewModel()
        viewModel.toggleSearch()
        viewModel.onSearchQueryChange("harbour")
        advanceUntilIdle()
        val resultsBefore = viewModel.uiState.value.overlays.searchResults
        assertTrue("precondition: the search produced results", resultsBefore.isNotEmpty())

        viewModel.onSearchResultOpened(reached = false)
        advanceUntilIdle()

        val overlays = viewModel.uiState.value.overlays
        assertTrue("the overlay must survive a dead tap", overlays.isSearchActive)
        assertEquals("harbour", overlays.searchQuery)
        assertEquals(resultsBefore, overlays.searchResults)
    }

    @Test
    fun `a result that cannot be reached says so instead of doing nothing`() = runTest {
        val viewModel = buildViewModel()
        val events = mutableListOf<SnackbarEvent>()
        val collector = launch { viewModel.snackbarEvent.collect { events += it } }
        advanceUntilIdle()

        viewModel.onSearchResultOpened(reached = false)
        advanceUntilIdle()

        assertEquals(listOf("Message no longer available"), events.map { it.message })
        collector.cancel()
    }

    @Test
    fun `a reached result closes the search as before`() = runTest {
        val viewModel = buildViewModel()
        viewModel.toggleSearch()
        viewModel.onSearchQueryChange("harbour")
        advanceUntilIdle()

        viewModel.onSearchResultOpened(reached = true)
        advanceUntilIdle()

        val overlays = viewModel.uiState.value.overlays
        assertFalse(overlays.isSearchActive)
        assertEquals("", overlays.searchQuery)
        assertTrue(overlays.searchResults.isEmpty())
    }

    @Test
    fun `a reached result also drops the active filter`() = runTest {
        val viewModel = buildViewModel()
        viewModel.openSharedMedia()
        advanceUntilIdle()
        assertEquals(MessageFilterType.PHOTOS, viewModel.uiState.value.overlays.searchFilter.type)

        viewModel.onSearchResultOpened(reached = true)
        advanceUntilIdle()

        assertEquals(MessageSearchFilter.NONE, viewModel.uiState.value.overlays.searchFilter)
    }

    @Test
    fun `opening shared media browses photos and backfills local copies once`() = runTest {
        val viewModel = buildViewModel()

        viewModel.openSharedMedia()
        advanceUntilIdle()

        assertTrue(viewModel.uiState.value.overlays.isSearchActive)
        assertEquals(listOf("p1"), viewModel.uiState.value.overlays.searchResults.map { it.id })
        assertEquals(listOf("chat1"), messageRepository.ensureLocalCopiesCalls)

        // Re-selecting a media filter must not re-run the backfill.
        viewModel.onSearchFilterChange(MessageSearchFilter(type = MessageFilterType.VIDEOS))
        advanceUntilIdle()

        assertEquals(listOf("chat1"), messageRepository.ensureLocalCopiesCalls)
    }

    @Test
    fun `a non-media filter does not trigger the local-copy backfill`() = runTest {
        val viewModel = buildViewModel()

        viewModel.onSearchFilterChange(MessageSearchFilter(isStarred = true))
        advanceUntilIdle()

        assertTrue(messageRepository.ensureLocalCopiesCalls.isEmpty())
    }
}
