package com.firestream.chat.ui.chat

import android.webkit.MimeTypeMap
import com.firestream.chat.domain.model.Message
import com.firestream.chat.domain.model.MessageType
import com.firestream.chat.test.fakes.FakeMessageRepository
import com.firestream.chat.ui.components.ReadyFile
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [31], manifest = Config.NONE, application = android.app.Application::class)
class ChatFileActionsTest {

    private val repository = FakeMessageRepository()
    private val saved = mutableListOf<ReadyFile>()
    private val notices = mutableListOf<String>()

    private val pdf = Message(
        id = "doc1", chatId = "chat1", type = MessageType.DOCUMENT,
        mediaUrl = "https://storage.example/doc1.pdf",
        fileName = "Report.pdf", fileSize = 1_024L, mimeType = "application/pdf",
    )

    @Before
    fun setUp() {
        shadowOf(MimeTypeMap.getSingleton()).addExtensionMimeTypeMapping("txt", "text/plain")
    }

    // The test scope itself, not backgroundScope: advanceUntilIdle does not run
    // background work, and these requests finish on their own.
    private fun TestScope.actions() = ChatFileActions(
        messageRepository = repository,
        scope = this,
        saveToDownloads = { saved += it },
        notify = { notices += it },
    )

    @Test
    fun `open hands the screen the local file under its picked type and name`() = runTest {
        repository.ensureLocalFileResult = { Result.success("/files/documents/doc1.pdf") }
        val actions = actions()
        val launches = mutableListOf<FileLaunch>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { actions.launches.toList(launches) }

        actions.request(pdf, FileAction.OPEN)
        advanceUntilIdle()

        assertEquals(
            listOf(FileLaunch(ReadyFile("/files/documents/doc1.pdf", "application/pdf", "Report.pdf"), FileAction.OPEN, isRisky = false)),
            launches,
        )
        assertTrue("the spinner ends", actions.preparing.value.isEmpty())
    }

    @Test
    fun `an APK is flagged risky so the screen confirms first`() = runTest {
        repository.ensureLocalFileResult = { Result.success("/files/documents/doc1.apk") }
        val actions = actions()
        val launches = mutableListOf<FileLaunch>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { actions.launches.toList(launches) }

        actions.request(pdf.copy(fileName = "game.apk", mimeType = "application/vnd.android.package-archive"), FileAction.OPEN)
        advanceUntilIdle()

        assertTrue(launches.single().isRisky)
    }

    @Test
    fun `a generic type falls back to the name's extension, then to any`() {
        assertEquals("text/plain", ChatFileActions.mimeTypeFor(pdf.copy(fileName = "notes.txt", mimeType = "application/octet-stream")))
        assertEquals("application/octet-stream", ChatFileActions.mimeTypeFor(pdf.copy(fileName = "blob", mimeType = "application/octet-stream")))
        assertEquals("*/*", ChatFileActions.mimeTypeFor(pdf.copy(fileName = null, mimeType = null)))
        assertEquals("Document", ChatFileActions.displayNameFor(pdf.copy(fileName = null)))
    }

    @Test
    fun `a failed download tells the user and ends the spinner`() = runTest {
        val actions = actions()

        actions.request(pdf, FileAction.OPEN)
        advanceUntilIdle()

        assertEquals(listOf("Couldn't download the file"), notices)
        assertTrue(actions.preparing.value.isEmpty())
    }

    @Test
    fun `a second tap while the first is still fetching is ignored`() = runTest {
        val gate = CompletableDeferred<Unit>()
        var fetches = 0
        repository.ensureLocalFileResult = { fetches++; Result.success("/files/documents/doc1.pdf") }
        val actions = ChatFileActions(
            messageRepository = object : com.firestream.chat.domain.repository.MessageRepository by repository {
                override suspend fun ensureLocalFile(message: Message): Result<String> {
                    gate.await()
                    return repository.ensureLocalFile(message)
                }
            },
            scope = this,
            saveToDownloads = {},
            notify = {},
        )

        actions.request(pdf, FileAction.OPEN)
        advanceUntilIdle()
        assertEquals(setOf("doc1"), actions.preparing.value)
        actions.request(pdf, FileAction.OPEN)
        gate.complete(Unit)
        advanceUntilIdle()

        assertEquals(1, fetches)
    }

    @Test
    fun `save hands the file to Downloads rather than to an intent`() = runTest {
        repository.ensureLocalFileResult = { Result.success("/files/documents/doc1.pdf") }
        val actions = actions()

        actions.request(pdf, FileAction.SAVE)
        advanceUntilIdle()

        assertEquals("Report.pdf", saved.single().displayName)
    }
}
