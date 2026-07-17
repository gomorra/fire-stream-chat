package com.firestream.chat.data.repository

import android.content.ContentResolver
import android.net.ConnectivityManager
import android.net.Uri
import com.firestream.chat.data.crypto.SignalManager
import com.firestream.chat.data.local.PreferencesDataStore
import com.firestream.chat.data.local.dao.ChatDao
import com.firestream.chat.data.local.dao.MessageDao
import com.firestream.chat.data.local.entity.MessageEntity
import com.firestream.chat.data.remote.source.AuthSource
import com.firestream.chat.data.remote.source.MessageSource
import com.firestream.chat.data.remote.source.StorageSource
import com.firestream.chat.data.remote.source.UserSource
import com.firestream.chat.data.util.ImageCompressor
import com.firestream.chat.data.util.ImageResult
import com.firestream.chat.data.util.MediaFileManager
import com.firestream.chat.data.util.ThumbResult
import com.firestream.chat.data.util.VideoMetadata
import com.firestream.chat.data.util.VideoResult
import com.firestream.chat.data.util.VideoTranscoder
import com.firestream.chat.data.local.VideoQualityOption
import com.firestream.chat.domain.model.AppError
import com.firestream.chat.domain.model.MediaLimitException
import com.firestream.chat.domain.model.MessageType
import com.firestream.chat.domain.repository.ChatRepository
import com.firestream.chat.domain.repository.ListRepository
import io.mockk.Runs
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.slot
import io.mockk.unmockkStatic
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File

/**
 * Regression tests for the "second image silently dropped" bug. The optimistic
 * Room row must be inserted before any IO so the message bubble survives a
 * compression OOM, MediaStore IO error, or upload failure — the user always
 * sees the bubble with FAILED status instead of having it disappear.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class MessageRepositoryMediaSendFailureTest {

    private val testDispatcher = StandardTestDispatcher()

    private val messageDao = mockk<MessageDao>(relaxed = true)
    private val chatDao = mockk<ChatDao>(relaxed = true)
    private val messageSource = mockk<MessageSource>(relaxed = true)
    private val authSource = mockk<AuthSource>()
    private val signalManager = mockk<SignalManager>(relaxed = true)
    private val storageSource = mockk<StorageSource>(relaxed = true)
    private val chatRepository = mockk<dagger.Lazy<ChatRepository>>()
    private val mediaFileManager = mockk<MediaFileManager>(relaxed = true)
    private val imageCompressor = mockk<ImageCompressor>()
    private val videoTranscoder = mockk<VideoTranscoder>(relaxed = true)
    private val preferencesDataStore = mockk<PreferencesDataStore>(relaxed = true)
    private val connectivityManager = mockk<ConnectivityManager>(relaxed = true)
    private val listRepository = mockk<dagger.Lazy<ListRepository>>()
    private val userSource = mockk<UserSource>(relaxed = true)

    private val insertedEntities = mutableListOf<MessageEntity>()
    private val replaceArgs = mutableListOf<Pair<String, MessageEntity>>()
    private val statusUpdates = mutableListOf<Pair<String, String>>()

    private lateinit var repository: MessageRepositoryImpl

    @Before
    fun setUp() {
        Dispatchers.setMain(testDispatcher)

        // Uri.parse is an Android stub; mock it so the JVM unit test can call it.
        mockkStatic(Uri::class)
        every { Uri.parse(any()) } answers { mockk<Uri>(relaxed = true) }
        every { Uri.fromFile(any()) } answers { mockk<Uri>(relaxed = true) }

        every { authSource.currentUserId } returns "uid1"
        every { preferencesDataStore.sendImagesFullQualityFlow } returns flowOf(false)
        every { preferencesDataStore.videoQualityFlow } returns flowOf(VideoQualityOption.STANDARD)

        coEvery { messageDao.insertMessage(any()) } answers {
            insertedEntities += firstArg<MessageEntity>()
        }
        coEvery { messageDao.replaceMessage(any(), any()) } answers {
            replaceArgs += (firstArg<String>() to secondArg())
        }
        coEvery { messageDao.updateMessageStatus(any(), any()) } answers {
            statusUpdates += (firstArg<String>() to secondArg())
        }
        coEvery { messageDao.updateLocalUri(any(), any()) } just Runs

        repository = MessageRepositoryImpl(
            messageDao, chatDao, messageSource, authSource, signalManager, storageSource, chatRepository,
            listRepository, mediaFileManager, imageCompressor, videoTranscoder, preferencesDataStore, connectivityManager,
            userSource
        )
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
        unmockkStatic(Uri::class)
    }

    @Test
    fun `compression failure leaves message bubble visible with FAILED status`() = runTest {
        // Simulate the OOM symptom: BitmapFactory.decodeStream returns null → ImageCompressor
        // throws IllegalArgumentException("Cannot decode image"). This is what happens when
        // two large images compress concurrently and one runs out of memory.
        coEvery { imageCompressor.processImage(any(), any()) } throws
            IllegalArgumentException("Cannot decode image")

        val result = repository.sendMediaMessage(
            chatId = "chat1",
            uri = "content://media/picker/0/photo.jpg",
            mimeType = "image/jpeg",
            recipientId = "",
            caption = "look at this"
        )

        // The optimistic row was inserted BEFORE compression — bubble appears.
        assertEquals(1, insertedEntities.size)
        val placeholder = insertedEntities.single()
        assertEquals("SENDING", placeholder.status)
        assertEquals("IMAGE", placeholder.type)
        assertEquals("look at this", placeholder.content)
        assertEquals("content://media/picker/0/photo.jpg", placeholder.localUri)

        // Status was flipped to FAILED so the bubble shows the error indicator.
        val failedUpdate = statusUpdates.single()
        assertEquals(placeholder.id, failedUpdate.first)
        assertEquals("FAILED", failedUpdate.second)

        // The post-compression replaceMessage was never reached.
        assertTrue(replaceArgs.isEmpty())

        // The Result is a failure so the snackbar still fires.
        assertTrue(result.isFailure)
    }

    @Test
    fun `happy path replaces placeholder twice and never marks FAILED`() = runTest {
        val compressedFile = File.createTempFile("img_", ".jpg")
        try {
            val localFile = File("/tmp/local-${compressedFile.name}")
            coEvery { imageCompressor.processImage(any(), any()) } returns
                ImageResult(compressedFile, width = 800, height = 600, mimeType = "image/jpeg")
            coEvery { mediaFileManager.copyToLocal(any(), any(), any(), any()) } returns localFile
            every { mediaFileManager.getLocalFile(any(), any(), any()) } returns
                File("/tmp/renamed.jpg")
            coEvery {
                storageSource.uploadMedia(any(), any(), any(), any(), any())
            } returns "https://example/firebase/img.jpg"
            coEvery {
                messageSource.sendPlainMessage(
                    chatId = any(), senderId = any(), content = any(), type = any(),
                    replyToId = any(), timestamp = any(), mediaUrl = any(),
                    mediaThumbnailUrl = any(), isForwarded = any(), duration = any(), mentions = any(),
                    emojiSizes = any(), mediaWidth = any(), mediaHeight = any(),
                    latitude = any(), longitude = any(), isHd = any()
                )
            } returns "remote-id-1"
            every { messageSource.lastContentFor(any(), any()) } returns "📷 Photo"

            val result = repository.sendMediaMessage(
                chatId = "chat1",
                uri = "content://media/picker/0/photo.jpg",
                mimeType = "image/jpeg",
                recipientId = "",
                caption = "hi"
            )

            assertTrue(result.isSuccess)

            // Optimistic row inserted FIRST (before compression).
            assertEquals(1, insertedEntities.size)
            val placeholder = insertedEntities.single()
            assertEquals("SENDING", placeholder.status)
            assertNull(placeholder.mediaWidth)
            assertNull(placeholder.mediaHeight)

            // Two replaceMessage calls: post-compression (real dimensions, still SENDING)
            // and post-upload (status SENT).
            assertEquals(2, replaceArgs.size)

            val (firstOldId, firstReplacement) = replaceArgs[0]
            assertEquals(placeholder.id, firstOldId)
            assertEquals("SENDING", firstReplacement.status)
            assertEquals(800, firstReplacement.mediaWidth)
            assertEquals(600, firstReplacement.mediaHeight)

            val (secondOldId, secondReplacement) = replaceArgs[1]
            assertEquals(placeholder.id, secondOldId)
            assertEquals("SENT", secondReplacement.status)
            assertEquals("remote-id-1", secondReplacement.id)
            assertNotNull(secondReplacement.mediaUrl)

            // Never marked FAILED on the happy path.
            assertTrue(statusUpdates.none { it.second == "FAILED" })
        } finally {
            compressedFile.delete()
        }
    }

    @Test
    fun `video mime creates a VIDEO-typed placeholder before transcode`() = runTest {
        // Metadata within limits so the guard passes and the optimistic row is inserted.
        every { videoTranscoder.readMetadata(any()) } returns
            VideoMetadata(width = 1920, height = 1080, durationMs = 30_000L, rotationDegrees = 0, sizeBytes = 5_000_000L)
        // Fail the transcode so the test ends quickly — the placeholder is already inserted.
        coEvery { videoTranscoder.transcode(any(), any()) } throws RuntimeException("transcode boom")

        val result = repository.sendMediaMessage(
            chatId = "chat1",
            uri = "content://media/picker/0/clip.mp4",
            mimeType = "video/mp4",
            recipientId = "",
            caption = "my clip"
        )

        assertTrue(result.isFailure)
        // Optimistic row inserted BEFORE transcode, typed VIDEO.
        assertEquals(1, insertedEntities.size)
        val placeholder = insertedEntities.single()
        assertEquals("VIDEO", placeholder.type)
        assertEquals("SENDING", placeholder.status)
        assertEquals("my clip", placeholder.content)
        assertEquals("content://media/picker/0/clip.mp4", placeholder.localUri)
    }

    @Test
    fun `transcode failure flips row to FAILED`() = runTest {
        every { videoTranscoder.readMetadata(any()) } returns
            VideoMetadata(width = 1280, height = 720, durationMs = 20_000L, rotationDegrees = 0, sizeBytes = 3_000_000L)
        coEvery { videoTranscoder.transcode(any(), any()) } throws RuntimeException("transcode boom")

        val result = repository.sendMediaMessage(
            chatId = "chat1",
            uri = "content://media/picker/0/clip.mp4",
            mimeType = "video/mp4",
            recipientId = "",
            caption = ""
        )

        assertTrue(result.isFailure)
        // Transcode threw before the post-processing replace ran.
        assertTrue(replaceArgs.isEmpty())
        val placeholder = insertedEntities.single()
        val failedUpdate = statusUpdates.single()
        assertEquals(placeholder.id, failedUpdate.first)
        assertEquals("FAILED", failedUpdate.second)
    }

    @Test
    fun `over-limit video is rejected before any row is inserted`() = runTest {
        // Duration over the 3-minute guard (180_000 ms).
        every { videoTranscoder.readMetadata(any()) } returns
            VideoMetadata(width = 1920, height = 1080, durationMs = 200_000L, rotationDegrees = 0, sizeBytes = 1_000L)

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
        coVerify(exactly = 0) { messageDao.insertMessage(any()) }
        assertTrue(statusUpdates.isEmpty())

        // The thrown exception is a MediaLimitException that AppError.from maps to Validation.
        val error = result.exceptionOrNull()
        assertTrue(error is MediaLimitException)
        val appError = AppError.from(error!!)
        assertTrue(appError is AppError.Validation)
        assertEquals("Videos can be up to 3 minutes and 100 MB", appError.message)
    }

    @Test
    fun `upload failure leaves message bubble visible with FAILED status`() = runTest {
        val compressedFile = File.createTempFile("img_", ".jpg")
        try {
            val localFile = File("/tmp/local-${compressedFile.name}")
            coEvery { imageCompressor.processImage(any(), any()) } returns
                ImageResult(compressedFile, width = 800, height = 600, mimeType = "image/jpeg")
            coEvery { mediaFileManager.copyToLocal(any(), any(), any(), any()) } returns localFile
            coEvery {
                storageSource.uploadMedia(any(), any(), any(), any(), any())
            } throws RuntimeException("network down")

            val result = repository.sendMediaMessage(
                chatId = "chat1",
                uri = "content://media/picker/0/photo.jpg",
                mimeType = "image/jpeg",
                recipientId = "",
                caption = ""
            )

            assertTrue(result.isFailure)

            // Compression succeeded, so the post-compression replace ran (1 call).
            // The post-upload replace did not (upload threw).
            assertEquals(1, replaceArgs.size)

            // Status was flipped to FAILED on the surviving optimistic row.
            val placeholder = insertedEntities.single()
            val failedUpdate = statusUpdates.single()
            assertEquals(placeholder.id, failedUpdate.first)
            assertEquals("FAILED", failedUpdate.second)
        } finally {
            compressedFile.delete()
        }
    }
}
