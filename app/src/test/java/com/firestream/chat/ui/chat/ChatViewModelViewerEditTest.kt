package com.firestream.chat.ui.chat

import android.net.Uri
import androidx.core.app.NotificationManagerCompat
import androidx.lifecycle.SavedStateHandle
import com.firestream.chat.data.local.PreferencesDataStore
import com.firestream.chat.data.remote.LinkPreviewSource
import com.firestream.chat.data.remote.fcm.ActiveChatTracker
import com.firestream.chat.data.util.ImageEditRasterizer
import com.firestream.chat.data.util.MediaFileManager
import com.firestream.chat.domain.model.Chat
import com.firestream.chat.domain.model.ChatType
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
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.unmockkAll
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.io.IOException

/**
 * "Edit" from a fullscreen viewer (`.claude/plans/image-editor.md` §2.6): the
 * displayed photo is fetched if it has no readable local file, copied into the
 * edit cache, and published for the screen to open the send preview on.
 *
 * The state machine is `null → Preparing → Ready → null`, with a failure or a
 * back press returning straight to `null` — and the thing each test guards is
 * that no path can leave a spinner up or open a preview nobody asked for.
 */
class ChatViewModelViewerEditTest {

    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    @get:Rule
    val tmp = TemporaryFolder()

    private val preferencesDataStore = mockk<PreferencesDataStore>(relaxed = true)
    private val mediaFileManager = mockk<MediaFileManager>()
    private val imageEditRasterizer = mockk<ImageEditRasterizer>()
    private val linkPreviewSource = mockk<LinkPreviewSource>(relaxed = true)
    private val authRepository = mockk<AuthRepository>(relaxed = true)
    private val reminderRepository = mockk<ReminderRepository>(relaxed = true) {
        every { observePendingIdsForChat(any()) } returns flowOf(emptySet())
    }
    private val dateTimeDetector = mockk<DateTimeDetector>(relaxed = true) {
        coEvery { detect(any(), any()) } returns null
    }
    private val chatRepository = FakeChatRepository()

    private val imported = mockk<Uri>()
    private val remoteUrl = "https://example.com/photo.jpg"

    /** What a viewer with no readable local file shows, and files its bitmap under. */
    private val remoteKey = fullscreenImageCacheKey(remoteUrl)

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
        coEvery { imageEditRasterizer.importSource(any()) } returns imported
    }

    @After
    fun tearDown() {
        unmockkAll()
    }

    private fun buildViewModel(): ChatViewModel = ChatViewModel(
        savedStateHandle = SavedStateHandle(mapOf("chatId" to "chat1", "recipientId" to "recipient1")),
        checkGroupPermissionUseCase = mockk<CheckGroupPermissionUseCase>(relaxed = true),
        searchMessagesUseCase = mockk<SearchMessagesUseCase>(relaxed = true),
        linkPreviewSource = linkPreviewSource,
        authRepository = authRepository,
        chatRepository = chatRepository,
        listRepository = mockk<ListRepository>(relaxed = true),
        messageRepository = FakeMessageRepository(),
        reminderRepository = reminderRepository,
        dateTimeDetector = dateTimeDetector,
        pollRepository = mockk<PollRepository>(relaxed = true),
        userRepository = FakeUserRepository(),
        preferencesDataStore = preferencesDataStore,
        mediaFileManager = mediaFileManager,
        imageEditRasterizer = imageEditRasterizer,
        activeChatTracker = mockk<ActiveChatTracker>(relaxed = true),
        speechRecognizerManager = mockk(relaxed = true),
        callStateHolder = com.firestream.chat.data.call.CallStateHolder(),
        commandRegistry = com.firestream.chat.domain.command.CommandRegistry(emptySet()),
        timerAlarmScheduler = mockk(relaxed = true),
        connectivityObserver = FakeConnectivityObserver(),
        appScope = TestScope(mainDispatcherRule.testDispatcher),
        context = mockk(relaxed = true),
    )

    private fun ChatViewModel.viewerEdit() = uiState.value.overlays.viewerEdit

    /** A photo whose message has never been downloaded on this device. */
    private val remoteOnly = FullscreenMediaItem(
        imageUrl = remoteUrl,
        localUri = "/nowhere/never-downloaded.jpg",
        messageId = "m1",
    )

    @Test
    fun `nothing is being edited by default`() {
        assertNull(buildViewModel().viewerEdit())
    }

    @Test
    fun `a photo with a readable local file is copied without a download`() = runTest {
        val local = tmp.newFile("m1.jpg").apply { writeText("jpeg") }
        val viewModel = buildViewModel()

        viewModel.editFromViewer(FullscreenMediaItem(remoteUrl, local.path, "m1"))

        // Ready names the bitmap the viewer is showing — the local file's, not
        // the URL's — for the preview's first frame.
        assertEquals(ViewerEdit.Ready(imported, fullscreenImageCacheKey(local)), viewModel.viewerEdit())
        coVerify(exactly = 1) { imageEditRasterizer.importSource(local) }
        coVerify(exactly = 0) { mediaFileManager.downloadAndSave(any(), any(), any()) }
    }

    @Test
    fun `a photo already on this device opens without a spinner`() = runTest {
        // The copy takes milliseconds; a scrim that fades in and straight back
        // out over the photo reads as a glitch, not as progress.
        val local = tmp.newFile("m1.jpg").apply { writeText("jpeg") }
        val viewModel = buildViewModel()
        val seen = mutableListOf<ViewerEdit?>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            viewModel.uiState.map { it.overlays.viewerEdit }.distinctUntilChanged().toList(seen)
        }

        viewModel.editFromViewer(FullscreenMediaItem(remoteUrl, local.path, "m1"))

        assertEquals(listOf(null, ViewerEdit.Ready(imported, fullscreenImageCacheKey(local))), seen)
    }

    @Test
    fun `a photo with no local file is downloaded under its own message id, then copied`() = runTest {
        val downloaded = tmp.newFile("downloaded.jpg")
        coEvery { mediaFileManager.downloadAndSave("chat1", "m1", remoteUrl) } returns downloaded
        val viewModel = buildViewModel()

        viewModel.editFromViewer(remoteOnly)

        assertEquals(ViewerEdit.Ready(imported, remoteKey), viewModel.viewerEdit())
        coVerify(exactly = 1) { imageEditRasterizer.importSource(downloaded) }
    }

    @Test
    fun `an unreadable file under the message id is fetched again under a fresh name`() = runTest {
        // A previous install's MediaStore file: it exists, downloadAndSave hands
        // it back instead of overwriting, and it cannot be opened.
        val unreadable = File(tmp.root, "left-by-a-previous-install.jpg")
        val fresh = tmp.newFile("fresh.jpg")
        coEvery { mediaFileManager.downloadAndSave("chat1", "m1", remoteUrl) } returns unreadable
        coEvery {
            mediaFileManager.downloadAndSave("chat1", match { it.startsWith("download_") }, remoteUrl)
        } returns fresh
        val viewModel = buildViewModel()

        viewModel.editFromViewer(remoteOnly)

        assertEquals(ViewerEdit.Ready(imported, remoteKey), viewModel.viewerEdit())
        coVerify(exactly = 1) { imageEditRasterizer.importSource(fresh) }
    }

    @Test
    fun `the spinner stays up exactly as long as the download runs`() = runTest {
        val download = CompletableDeferred<File>()
        coEvery { mediaFileManager.downloadAndSave(any(), any(), any()) } coAnswers { download.await() }
        val viewModel = buildViewModel()

        viewModel.editFromViewer(remoteOnly)
        assertEquals(ViewerEdit.Preparing, viewModel.viewerEdit())

        download.complete(tmp.newFile("m1.jpg"))

        assertEquals(ViewerEdit.Ready(imported, remoteKey), viewModel.viewerEdit())
    }

    @Test
    fun `a failed download takes the spinner down and says so`() = runTest {
        coEvery { mediaFileManager.downloadAndSave(any(), any(), any()) } throws IOException("offline")
        val viewModel = buildViewModel()
        val events = mutableListOf<SnackbarEvent>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            viewModel.snackbarEvent.toList(events)
        }

        viewModel.editFromViewer(remoteOnly)

        assertNull(viewModel.viewerEdit())
        assertEquals("Couldn't open the photo for editing", events.single().message)
        coVerify(exactly = 0) { imageEditRasterizer.importSource(any()) }
    }

    @Test
    fun `a cancellation thrown from inside the fetch still takes the spinner down`() = runTest {
        // Not a back press: a callee throwing CancellationException on its own
        // would otherwise leave the touch-blocking scrim up, with only back to
        // escape it.
        coEvery { mediaFileManager.downloadAndSave(any(), any(), any()) } throws
            CancellationException("not a back press")
        val viewModel = buildViewModel()

        viewModel.editFromViewer(remoteOnly)

        assertNull(viewModel.viewerEdit())
    }

    @Test
    fun `a photo with neither a readable file nor a url fails rather than hanging`() = runTest {
        val viewModel = buildViewModel()

        viewModel.editFromViewer(FullscreenMediaItem(imageUrl = null, localUri = "/nowhere.jpg", messageId = "m1"))

        assertNull(viewModel.viewerEdit())
    }

    @Test
    fun `backing out while fetching leaves nothing to open when the download lands`() = runTest {
        val download = CompletableDeferred<File>()
        coEvery { mediaFileManager.downloadAndSave(any(), any(), any()) } coAnswers { download.await() }
        val viewModel = buildViewModel()

        viewModel.editFromViewer(remoteOnly)
        viewModel.cancelViewerEdit()
        download.complete(tmp.newFile("m1.jpg"))

        assertNull(viewModel.viewerEdit())
        coVerify(exactly = 0) { imageEditRasterizer.importSource(any()) }
    }

    @Test
    fun `a second tap while one is fetching does not start another`() = runTest {
        val download = CompletableDeferred<File>()
        coEvery { mediaFileManager.downloadAndSave(any(), any(), any()) } coAnswers { download.await() }
        val viewModel = buildViewModel()

        viewModel.editFromViewer(remoteOnly)
        viewModel.editFromViewer(remoteOnly)
        download.complete(tmp.newFile("m1.jpg"))

        coVerify(exactly = 1) { mediaFileManager.downloadAndSave(any(), any(), any()) }
        coVerify(exactly = 1) { imageEditRasterizer.importSource(any()) }
    }

    @Test
    fun `consuming a ready edit clears it, and never cancels one still preparing`() = runTest {
        val download = CompletableDeferred<File>()
        coEvery { mediaFileManager.downloadAndSave(any(), any(), any()) } coAnswers { download.await() }
        val viewModel = buildViewModel()

        viewModel.editFromViewer(remoteOnly)
        viewModel.consumeViewerEdit()
        assertEquals(ViewerEdit.Preparing, viewModel.viewerEdit())

        download.complete(tmp.newFile("m1.jpg"))
        viewModel.consumeViewerEdit()

        assertNull(viewModel.viewerEdit())
    }
}
