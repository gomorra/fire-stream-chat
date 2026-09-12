package com.firestream.chat.data.repository

import android.net.Uri
import com.firestream.chat.data.local.PreferencesDataStore
import com.firestream.chat.data.local.dao.MessageDao
import com.firestream.chat.data.local.entity.MessageEntity
import com.firestream.chat.data.outbox.OutboxFiles
import com.firestream.chat.data.outbox.OutboxScheduler
import com.firestream.chat.data.remote.source.AuthSource
import com.firestream.chat.data.util.VideoMetadata
import com.firestream.chat.data.util.VideoTranscoder
import com.firestream.chat.domain.model.AppError
import com.firestream.chat.domain.model.MediaLimitException
import com.firestream.chat.domain.model.MessageStatus
import com.firestream.chat.domain.repository.ChatRepository
import com.firestream.chat.domain.repository.ListRepository
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.unmockkStatic
import io.mockk.verify
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.FileNotFoundException

/**
 * Regression tests for the "second image silently dropped" bug, carried over to
 * the queued send. The optimistic Room row must be inserted before any IO so
 * the bubble survives whatever fails after it — once a compression OOM in the
 * caller's scope, now the staging of the input — and the user always sees the
 * bubble, FAILED, instead of having it disappear.
 *
 * The upload and the write run in `OutboxWorker`; what the pipeline persists
 * between its steps, and how a retry resumes from that, is `OutboxSenderTest`.
 */
class MessageRepositoryMediaSendFailureTest {

    private val messageDao = mockk<MessageDao>(relaxed = true)
    private val authSource = mockk<AuthSource>()
    private val outboxScheduler = mockk<OutboxScheduler>(relaxed = true)
    private val outboxFiles = mockk<OutboxFiles>()
    private val chatRepository = mockk<dagger.Lazy<ChatRepository>>()
    private val listRepository = mockk<dagger.Lazy<ListRepository>>()
    private val videoTranscoder = mockk<VideoTranscoder>(relaxed = true)
    private val preferencesDataStore = mockk<PreferencesDataStore>(relaxed = true)

    private val insertedEntities = mutableListOf<MessageEntity>()
    private val statusUpdates = mutableListOf<Pair<String, String>>()

    private lateinit var repository: MessageRepositoryImpl

    @Before
    fun setUp() {
        // Uri.parse is an Android stub; the video limit guard reaches it.
        mockkStatic(Uri::class)
        every { Uri.parse(any()) } answers { mockk<Uri>(relaxed = true) }

        every { authSource.currentUserId } returns "uid1"
        every { preferencesDataStore.sendImagesFullQualityFlow } returns flowOf(false)

        coEvery { messageDao.insertOutbox(any()) } answers {
            insertedEntities += firstArg<MessageEntity>()
        }
        coEvery { messageDao.updateMessageStatus(any(), any()) } answers {
            statusUpdates += (firstArg<String>() to secondArg())
        }

        repository = messageRepository(
            messageDao = messageDao,
            authSource = authSource,
            outboxScheduler = outboxScheduler,
            outboxFiles = outboxFiles,
            chatRepository = chatRepository,
            listRepository = listRepository,
            videoTranscoder = videoTranscoder,
            preferencesDataStore = preferencesDataStore,
        )
    }

    @After
    fun tearDown() {
        unmockkStatic(Uri::class)
    }

    @Test
    fun `a staging failure leaves the message bubble visible with FAILED status`() = runTest {
        // The picker's grant is gone, or the cache file was purged before the copy.
        coEvery { outboxFiles.stage(any(), any(), any()) } throws FileNotFoundException("content://media/picker/0/photo.jpg")

        val result = repository.sendMediaMessage(
            chatId = "chat1",
            uri = "content://media/picker/0/photo.jpg",
            mimeType = "image/jpeg",
            recipientId = "",
            caption = "look at this"
        )

        // The optimistic row was inserted BEFORE the staging ran — bubble appears.
        val placeholder = insertedEntities.single()
        assertEquals("SENDING", placeholder.status)
        assertEquals("IMAGE", placeholder.type)
        assertEquals("look at this", placeholder.content)
        assertEquals("content://media/picker/0/photo.jpg", placeholder.localUri)

        // Status was flipped to FAILED so the bubble shows the error indicator.
        assertEquals(listOf(placeholder.id to "FAILED"), statusUpdates)
        verify(exactly = 0) { outboxScheduler.enqueue(any(), any()) }

        // The Result is a failure so the snackbar still fires.
        assertTrue(result.isFailure)
    }

    @Test
    fun `success stages the input under the picked type, points the row at the copy and queues it`() = runTest {
        coEvery { outboxFiles.stage(any(), "content://docs/report.pdf", "application/pdf") } answers {
            "/data/outbox/${firstArg<String>()}.pdf"
        }

        val result = repository.sendMediaMessage(
            chatId = "chat1",
            uri = "content://docs/report.pdf",
            mimeType = "application/pdf",
            recipientId = "",
            caption = ""
        )

        assertEquals(MessageStatus.SENDING, result.getOrThrow().status)
        val placeholder = insertedEntities.single()
        assertEquals("DOCUMENT", placeholder.type)
        assertEquals("", placeholder.outboxRecipientId)
        coVerify(exactly = 1) { messageDao.updateLocalUri(placeholder.id, "/data/outbox/${placeholder.id}.pdf") }
        verify(exactly = 1) { outboxScheduler.enqueue(placeholder.id, uploads = true) }
        assertTrue(statusUpdates.isEmpty())
    }

    @Test
    fun `video mime creates a VIDEO-typed placeholder before staging, flipped FAILED when staging throws`() = runTest {
        // Metadata within limits so the guard passes and the optimistic row is inserted.
        coEvery { videoTranscoder.ensureWithinLimits(any()) } returns
            VideoMetadata(width = 1920, height = 1080, durationMs = 30_000L, rotationDegrees = 0, sizeBytes = 5_000_000L)
        coEvery { outboxFiles.stage(any(), any(), any()) } throws FileNotFoundException("gone")

        val result = repository.sendMediaMessage(
            chatId = "chat1",
            uri = "content://media/picker/0/clip.mp4",
            mimeType = "video/mp4",
            recipientId = "",
            caption = "my clip"
        )

        assertTrue(result.isFailure)
        val placeholder = insertedEntities.single()
        assertEquals("VIDEO", placeholder.type)
        assertEquals("SENDING", placeholder.status)
        assertEquals("my clip", placeholder.content)
        assertEquals("content://media/picker/0/clip.mp4", placeholder.localUri)
        assertEquals(listOf(placeholder.id to "FAILED"), statusUpdates)
    }

    // The send runs in the chat's scope. Leaving the chat between the insert and
    // the enqueue used to be the orphan-recovery case; now nothing would own the
    // row until the next app start, when the picker's grant is gone.
    @Test
    fun `leaving the chat while the input is being staged still queues the send`() = runTest {
        lateinit var sending: Job
        coEvery { outboxFiles.stage(any(), any(), any()) } coAnswers {
            sending.cancel()
            "/data/outbox/${firstArg<String>()}.pdf"
        }

        sending = launch {
            repository.sendMediaMessage("chat1", "content://docs/report.pdf", "application/pdf", "", "")
        }
        sending.join()

        val placeholder = insertedEntities.single()
        coVerify(exactly = 1) { messageDao.updateLocalUri(placeholder.id, "/data/outbox/${placeholder.id}.pdf") }
        verify(exactly = 1) { outboxScheduler.enqueue(placeholder.id, uploads = true) }
        assertTrue(statusUpdates.isEmpty())
    }

    @Test
    fun `over-limit video is rejected before any row is inserted`() = runTest {
        // The limit check lives inside VideoTranscoder.ensureWithinLimits; here we only
        // verify the repository's ordering contract (guard rejects → nothing inserted)
        // and the AppError mapping of the thrown MediaLimitException.
        coEvery { videoTranscoder.ensureWithinLimits(any()) } throws
            MediaLimitException("Videos can be up to 3 minutes and 100 MB")

        val result = repository.sendMediaMessage(
            chatId = "chat1",
            uri = "content://media/picker/0/long.mp4",
            mimeType = "video/mp4",
            recipientId = "",
            caption = "too long"
        )

        assertTrue(result.isFailure)
        // No placeholder row was ever written — guard runs before the insert.
        assertTrue(insertedEntities.isEmpty())
        assertTrue(statusUpdates.isEmpty())
        verify(exactly = 0) { outboxScheduler.enqueue(any(), any()) }

        // The thrown exception is a MediaLimitException that AppError.from maps to Validation.
        val error = result.exceptionOrNull()
        assertTrue(error is MediaLimitException)
        val appError = AppError.from(error!!)
        assertTrue(appError is AppError.Validation)
        assertEquals("Videos can be up to 3 minutes and 100 MB", appError.message)
    }
}
