package com.firestream.chat.data.repository

import android.net.ConnectivityManager
import android.net.Uri
import com.firestream.chat.data.crypto.SignalManager
import com.firestream.chat.data.local.PreferencesDataStore
import com.firestream.chat.data.local.dao.ChatDao
import com.firestream.chat.data.local.dao.MessageDao
import com.firestream.chat.data.local.entity.MessageEntity
import com.firestream.chat.data.outbox.OutboxSender
import com.firestream.chat.data.remote.source.AuthSource
import com.firestream.chat.data.remote.source.MessageSource
import com.firestream.chat.data.remote.source.UserSource
import com.firestream.chat.data.util.MediaFileManager
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
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Regression tests for the "second image silently dropped" bug. The optimistic
 * Room row must be inserted before any IO so the message bubble survives a
 * compression OOM, MediaStore IO error, or upload failure — the user always
 * sees the bubble with FAILED status instead of having it disappear.
 *
 * The IO itself runs in [OutboxSender], mocked here; what it persists between
 * its steps, and how a retry resumes from that, is covered in `OutboxSenderTest`.
 */
class MessageRepositoryMediaSendFailureTest {

    private val messageDao = mockk<MessageDao>(relaxed = true)
    private val chatDao = mockk<ChatDao>(relaxed = true)
    private val messageSource = mockk<MessageSource>(relaxed = true)
    private val authSource = mockk<AuthSource>()
    private val signalManager = mockk<SignalManager>(relaxed = true)
    private val outboxSender = mockk<OutboxSender>(relaxed = true)
    private val chatRepository = mockk<dagger.Lazy<ChatRepository>>()
    private val listRepository = mockk<dagger.Lazy<ListRepository>>()
    private val mediaFileManager = mockk<MediaFileManager>(relaxed = true)
    private val videoTranscoder = mockk<VideoTranscoder>(relaxed = true)
    private val preferencesDataStore = mockk<PreferencesDataStore>(relaxed = true)
    private val connectivityManager = mockk<ConnectivityManager>(relaxed = true)
    private val userSource = mockk<UserSource>(relaxed = true)

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

        coEvery { messageDao.insertMessage(any()) } answers {
            insertedEntities += firstArg<MessageEntity>()
        }
        coEvery { messageDao.updateMessageStatus(any(), any()) } answers {
            statusUpdates += (firstArg<String>() to secondArg())
        }

        repository = MessageRepositoryImpl(
            messageDao, chatDao, messageSource, authSource, signalManager, outboxSender, chatRepository,
            listRepository, mediaFileManager, videoTranscoder, preferencesDataStore, connectivityManager,
            userSource
        )
    }

    @After
    fun tearDown() {
        unmockkStatic(Uri::class)
    }

    @Test
    fun `pipeline failure leaves message bubble visible with FAILED status`() = runTest {
        // The OOM symptom: ImageCompressor throws "Cannot decode image" when two
        // large images compress concurrently and one runs out of memory.
        coEvery { outboxSender.send(any(), any(), any(), any()) } throws
            IllegalArgumentException("Cannot decode image")

        val result = repository.sendMediaMessage(
            chatId = "chat1",
            uri = "content://media/picker/0/photo.jpg",
            mimeType = "image/jpeg",
            recipientId = "",
            caption = "look at this"
        )

        // The optimistic row was inserted BEFORE the pipeline ran — bubble appears.
        val placeholder = insertedEntities.single()
        assertEquals("SENDING", placeholder.status)
        assertEquals("IMAGE", placeholder.type)
        assertEquals("look at this", placeholder.content)
        assertEquals("content://media/picker/0/photo.jpg", placeholder.localUri)

        // Status was flipped to FAILED so the bubble shows the error indicator.
        assertEquals(listOf(placeholder.id to "FAILED"), statusUpdates)

        // The Result is a failure so the snackbar still fires.
        assertTrue(result.isFailure)
    }

    @Test
    fun `success hands the placeholder and its picked mime type to the pipeline and never marks FAILED`() = runTest {
        coEvery { outboxSender.send(any(), any(), any(), any()) } answers {
            insertedEntities.single().toDomain().copy(status = MessageStatus.SENT)
        }

        val result = repository.sendMediaMessage(
            chatId = "chat1",
            uri = "content://docs/report.pdf",
            mimeType = "application/pdf",
            recipientId = "",
            caption = ""
        )

        assertEquals(MessageStatus.SENT, result.getOrThrow().status)
        val placeholder = insertedEntities.single()
        assertEquals("DOCUMENT", placeholder.type)
        coVerify(exactly = 1) {
            outboxSender.send(placeholder.id, "", isRetry = false, sourceMimeType = "application/pdf")
        }
        assertTrue(statusUpdates.isEmpty())
    }

    @Test
    fun `video mime creates a VIDEO-typed placeholder before the pipeline, flipped FAILED when it throws`() = runTest {
        // Metadata within limits so the guard passes and the optimistic row is inserted.
        coEvery { videoTranscoder.ensureWithinLimits(any()) } returns
            VideoMetadata(width = 1920, height = 1080, durationMs = 30_000L, rotationDegrees = 0, sizeBytes = 5_000_000L)
        coEvery { outboxSender.send(any(), any(), any(), any()) } throws RuntimeException("transcode boom")

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
        coVerify(exactly = 0) { outboxSender.send(any(), any(), any(), any()) }

        // The thrown exception is a MediaLimitException that AppError.from maps to Validation.
        val error = result.exceptionOrNull()
        assertTrue(error is MediaLimitException)
        val appError = AppError.from(error!!)
        assertTrue(appError is AppError.Validation)
        assertEquals("Videos can be up to 3 minutes and 100 MB", appError.message)
    }
}
