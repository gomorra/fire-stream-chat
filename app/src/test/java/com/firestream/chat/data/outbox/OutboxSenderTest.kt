package com.firestream.chat.data.outbox

import android.net.Uri
import com.firestream.chat.data.crypto.SignalManager
import com.firestream.chat.data.local.PreferencesDataStore
import com.firestream.chat.data.local.VideoQualityOption
import com.firestream.chat.data.local.dao.ChatDao
import com.firestream.chat.data.local.dao.MessageDao
import com.firestream.chat.data.local.entity.ChatEntity
import com.firestream.chat.data.local.entity.MessageEntity
import com.firestream.chat.data.remote.source.MessageSource
import com.firestream.chat.data.remote.source.StorageSource
import com.firestream.chat.data.util.ImageCompressor
import com.firestream.chat.data.util.ImageResult
import com.firestream.chat.data.util.MediaFileManager
import com.firestream.chat.data.util.VideoMetadata
import com.firestream.chat.data.util.VideoResult
import com.firestream.chat.data.util.VideoTranscoder
import com.firestream.chat.domain.model.Message
import com.firestream.chat.domain.model.MessageStatus
import com.firestream.chat.domain.model.MessageType
import io.mockk.MockKMatcherScope
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
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import java.io.IOException

/**
 * The send pipeline against an in-memory messages table. [rows] stands in for
 * Room, so each test sees both what reached the backend ([uploads], [writes])
 * and what was persisted between steps — the state a retry resumes from.
 *
 * Every send passes an empty recipient, which selects the plaintext branch in
 * any build type: nothing here depends on the debug-only encryption guard.
 */
class OutboxSenderTest {

    private val messageDao = mockk<MessageDao>(relaxed = true)
    private val chatDao = mockk<ChatDao>(relaxed = true)
    private val messageSource = mockk<MessageSource>(relaxed = true)
    private val storageSource = mockk<StorageSource>()
    private val signalManager = mockk<SignalManager>(relaxed = true)
    private val imageCompressor = mockk<ImageCompressor>()
    private val videoTranscoder = mockk<VideoTranscoder>()
    private val mediaFileManager = mockk<MediaFileManager>()
    private val preferencesDataStore = mockk<PreferencesDataStore>(relaxed = true)

    /** The messages table, keyed by id. */
    private val rows = mutableMapOf<String, MessageEntity>()

    /** Every row written back to [rows], in order. */
    private val persisted = mutableListOf<MessageEntity>()
    private val uploads = mutableListOf<Upload>()
    private val writes = mutableListOf<Write>()

    private data class Upload(val storageId: String, val mimeType: String, val reportsProgress: Boolean)

    private data class Write(
        val messageId: String,
        val type: MessageType,
        val mediaUrl: String?,
        val mediaThumbnailUrl: String?,
        val duration: Int?,
        val ifAbsent: Boolean,
    )

    private lateinit var sender: OutboxSender

    @Before
    fun setUp() {
        // Uri is an Android stub; relaxed mocks report a non-null scheme, so every
        // localUri is treated as a URI rather than a bare path.
        mockkStatic(Uri::class)
        every { Uri.parse(any()) } answers { mockk(relaxed = true) }
        every { Uri.fromFile(any()) } answers { mockk(relaxed = true) }

        coEvery { messageDao.getMessageById(any()) } answers { rows[firstArg()] }
        coEvery { messageDao.replaceMessage(any(), any()) } answers {
            val row = secondArg<MessageEntity>()
            rows.remove(firstArg<String>())
            rows[row.id] = row
            persisted += row
        }
        coEvery { storageSource.uploadMedia(any(), any(), any(), any(), any()) } answers {
            val storageId = secondArg<String>()
            uploads += Upload(storageId, arg(3), reportsProgress = args[4] != null)
            "https://storage.example/$storageId"
        }
        coEvery { anyPlainWrite() } answers {
            writes += Write(
                messageId = arg(2),
                type = arg(4),
                mediaUrl = arg(7),
                mediaThumbnailUrl = arg(8),
                duration = arg(10),
                ifAbsent = arg(18),
            )
            arg<String>(2)
        }
        every { messageSource.lastContentFor(any(), any()) } returns "preview"
        every { preferencesDataStore.videoQualityFlow } returns flowOf(VideoQualityOption.STANDARD)

        sender = OutboxSender(
            messageDao, chatDao, messageSource, storageSource, signalManager,
            imageCompressor, videoTranscoder, mediaFileManager, preferencesDataStore,
        )
    }

    @After
    fun tearDown() {
        unmockkStatic(Uri::class)
    }

    /** Any plaintext write, positional in `MessageSource.sendPlainMessage` order. */
    private suspend fun MockKMatcherScope.anyPlainWrite() = messageSource.sendPlainMessage(
        any(), any(), any(), any(), any(), any(), any(), any(), any(), any(),
        any(), any(), any(), any(), any(), any(), any(), any(), any(),
    )

    private fun store(message: Message) {
        rows[message.id] = MessageEntity.fromDomain(message)
    }

    private fun sending(id: String, type: MessageType, localUri: String? = null) = Message(
        id = id,
        chatId = "chat1",
        senderId = "uid1",
        content = "",
        type = type,
        status = MessageStatus.SENDING,
        timestamp = 1_000L,
        localUri = localUri,
    )

    private fun stored(id: String): Message = rows.getValue(id).toDomain()

    private fun chatWithLastMessage(lastMessageId: String) = ChatEntity(
        id = "chat1",
        type = "INDIVIDUAL",
        name = null,
        avatarUrl = null,
        participants = listOf("uid1", "uid2"),
        unreadCount = 0,
        createdAt = 0L,
        createdBy = "uid1",
        admins = emptyList(),
        lastMessageId = lastMessageId,
        lastMessageContent = null,
        lastMessageTimestamp = null,
    )

    // ── the write ───────────────────────────────────────────────────────────

    @Test
    fun `a first attempt writes under the row id without the if-absent guard, then marks it SENT`() = runTest {
        store(sending("msg1", MessageType.TEXT))

        val sent = sender.send("msg1", recipientId = "")

        assertEquals(listOf(Write("msg1", MessageType.TEXT, null, null, null, ifAbsent = false)), writes)
        assertEquals("msg1", sent.id)
        assertEquals(MessageStatus.SENT, stored("msg1").status)
        coVerify(exactly = 1) { chatDao.updateLastMessage("chat1", "msg1", "preview", 1_000L) }
    }

    // Regression for the duplicate-on-retry bug: a retry used to `add()` under a
    // new auto-id, so a first write that landed after its await was cancelled
    // reached the recipient twice. A retry re-writes the row's own id and asks
    // the source to create the document only if it is absent.
    @Test
    fun `a retry re-writes the same id, only if absent`() = runTest {
        store(sending("msg1", MessageType.TEXT))

        sender.send("msg1", recipientId = "", isRetry = true)

        assertEquals(listOf(Write("msg1", MessageType.TEXT, null, null, null, ifAbsent = true)), writes)
    }

    @Test
    fun `a retry rebinds the chat preview only while it still points at the row`() = runTest {
        // A backend that mints its own ids (PocketBase) answers with a different one.
        coEvery { anyPlainWrite() } answers { "server-${arg<String>(2)}" }
        coEvery { chatDao.getChatById("chat1") } returns chatWithLastMessage("msg1")
        store(sending("msg1", MessageType.TEXT))
        store(sending("msg2", MessageType.TEXT))

        sender.send("msg1", recipientId = "", isRetry = true)
        sender.send("msg2", recipientId = "", isRetry = true)

        assertFalse("msg1" in rows)
        assertEquals(MessageStatus.SENT, stored("server-msg1").status)
        coVerify(exactly = 1) { chatDao.updateLastMessage(any(), any(), any(), any()) }
        coVerify { chatDao.updateLastMessage("chat1", "server-msg1", "preview", 1_000L) }
    }

    @Test
    fun `a failing write rethrows and leaves the row SENDING for the caller to mark`() = runTest {
        coEvery { anyPlainWrite() } throws IOException("offline")
        store(sending("msg1", MessageType.TEXT))

        val error = runCatching { sender.send("msg1", recipientId = "") }.exceptionOrNull()

        assertTrue(error is IOException)
        assertEquals(MessageStatus.SENDING, stored("msg1").status)
        coVerify(exactly = 0) { chatDao.updateLastMessage(any(), any(), any(), any()) }
    }

    @Test
    fun `a type the outbox does not send is refused before any IO`() = runTest {
        store(sending("timer1", MessageType.TIMER))

        val error = runCatching { sender.send("timer1", recipientId = "") }.exceptionOrNull()

        assertTrue(error is IllegalStateException)
        assertTrue(persisted.isEmpty())
        assertTrue(writes.isEmpty())
    }

    @Test
    fun `an unknown id is refused`() = runTest {
        val error = runCatching { sender.send("ghost", recipientId = "") }.exceptionOrNull()

        assertTrue(error is IllegalStateException)
    }

    // ── images ──────────────────────────────────────────────────────────────

    @Test
    fun `an image is compressed at the row's HD setting, then each step persists before the next`() = runTest {
        val compressed = File.createTempFile("img_", ".jpg")
        store(sending("img1", MessageType.IMAGE, localUri = "content://picker/1").copy(isHd = true))
        coEvery { imageCompressor.processImage(any(), any()) } returns
            ImageResult(compressed, width = 800, height = 600, mimeType = "image/jpeg")
        coEvery { mediaFileManager.copyToLocal("chat1", "img1", any(), "jpg") } returns File("/media/img1.jpg")

        sender.send("img1", recipientId = "")

        coVerify(exactly = 1) { imageCompressor.processImage(any(), fullQuality = true) }
        // Resume points in order: encoded file + dimensions, then the upload URL, then SENT.
        assertEquals(listOf("SENDING", "SENDING", "SENT"), persisted.map { it.status })
        assertEquals("/media/img1.jpg", persisted[0].localUri)
        assertEquals(800, persisted[0].mediaWidth)
        assertNull(persisted[0].mediaUrl)
        assertEquals("https://storage.example/img1", persisted[1].mediaUrl)
        assertEquals(listOf(Upload("img1", "image/jpeg", reportsProgress = true)), uploads)
        assertEquals("https://storage.example/img1", writes.single().mediaUrl)
        assertFalse("encoder output in cacheDir is cleaned up", compressed.exists())
    }

    @Test
    fun `an upload failure keeps the compressed row, so the retry resumes without compressing again`() = runTest {
        store(sending("img1", MessageType.IMAGE, localUri = "content://picker/1"))
        coEvery { imageCompressor.processImage(any(), any()) } answers {
            ImageResult(File.createTempFile("img_", ".jpg"), width = 800, height = 600, mimeType = "image/jpeg")
        }
        coEvery { mediaFileManager.copyToLocal(any(), any(), any(), any()) } returns File("/media/img1.jpg")
        coEvery { storageSource.uploadMedia(any(), any(), any(), any(), any()) } throws IOException("network down")

        assertTrue(runCatching { sender.send("img1", recipientId = "") }.exceptionOrNull() is IOException)

        val afterFailure = stored("img1")
        assertEquals(MessageStatus.SENDING, afterFailure.status)
        assertEquals("/media/img1.jpg", afterFailure.localUri)
        assertEquals(800, afterFailure.mediaWidth)
        assertNull(afterFailure.mediaUrl)
        assertTrue(writes.isEmpty())
        assertTrue(sender.uploadProgress.value.isEmpty())

        coEvery { storageSource.uploadMedia(any(), any(), any(), any(), any()) } returns "https://storage.example/img1"
        sender.send("img1", recipientId = "", isRetry = true)

        coVerify(exactly = 1) { imageCompressor.processImage(any(), any()) }
        assertEquals(MessageStatus.SENT, stored("img1").status)
    }

    @Test
    fun `an image whose dimensions are known is not compressed again`() = runTest {
        store(
            sending("img1", MessageType.IMAGE, localUri = "/media/img1.jpg")
                .copy(mediaWidth = 1024, mediaHeight = 768)
        )

        sender.send("img1", recipientId = "", isRetry = true)

        coVerify(exactly = 0) { imageCompressor.processImage(any(), any()) }
        assertEquals(listOf(Upload("img1", "image/jpeg", reportsProgress = true)), uploads)
    }

    @Test
    fun `media whose upload already finished is written without uploading again`() = runTest {
        store(
            sending("img1", MessageType.IMAGE, localUri = "/media/img1.jpg")
                .copy(mediaWidth = 1024, mediaHeight = 768, mediaUrl = "https://storage.example/earlier")
        )

        sender.send("img1", recipientId = "", isRetry = true)

        assertTrue(uploads.isEmpty())
        assertEquals("https://storage.example/earlier", writes.single().mediaUrl)
    }

    @Test
    fun `upload progress is published while uploading and cleared afterwards`() = runTest {
        store(sending("doc1", MessageType.DOCUMENT, localUri = "content://docs/a.pdf"))
        var duringUpload: Map<String, Float>? = null
        coEvery { storageSource.uploadMedia(any(), any(), any(), any(), any()) } answers {
            arg<(Float) -> Unit>(4).invoke(0.5f)
            duringUpload = sender.uploadProgress.value
            "https://storage.example/doc1"
        }

        sender.send("doc1", recipientId = "")

        assertEquals(mapOf("doc1" to 0.5f), duringUpload)
        assertTrue(sender.uploadProgress.value.isEmpty())
    }

    // ── videos ──────────────────────────────────────────────────────────────

    @Test
    fun `a first video attempt transcodes, uploads the thumbnail, then the video, persisting after each`() = runTest {
        val transcoded = File.createTempFile("vid_", ".mp4")
        val thumb = File.createTempFile("thumb_", ".jpg")
        val metadata = VideoMetadata(width = 1920, height = 1080, durationMs = 12_000L, rotationDegrees = 0, sizeBytes = 5_000_000L)
        store(sending("vid1", MessageType.VIDEO, localUri = "content://picker/v"))
        coEvery { videoTranscoder.ensureWithinLimits(any()) } returns metadata
        coEvery { videoTranscoder.transcode(any(), VideoQualityOption.STANDARD.targetHeight, metadata) } returns
            VideoResult(transcoded, width = 1280, height = 720, durationSec = 12)
        coEvery { mediaFileManager.copyToLocal("chat1", "vid1", any(), "mp4") } returns File("/media/vid1.mp4")
        coEvery { videoTranscoder.extractThumbnail(any()) } returns thumb

        sender.send("vid1", recipientId = "")

        assertEquals(
            listOf(
                Upload("vid1_thumb", "image/jpeg", reportsProgress = false),
                Upload("vid1", "video/mp4", reportsProgress = true),
            ),
            uploads,
        )
        // Transcode, thumbnail and upload each leave a resume point before SENT.
        assertEquals(listOf("SENDING", "SENDING", "SENDING", "SENT"), persisted.map { it.status })
        assertEquals(12, persisted[0].duration)
        assertNull(persisted[0].mediaThumbnailUrl)
        assertEquals("https://storage.example/vid1_thumb", persisted[1].mediaThumbnailUrl)
        assertEquals(
            Write("vid1", MessageType.VIDEO, "https://storage.example/vid1", "https://storage.example/vid1_thumb", 12, ifAbsent = false),
            writes.single(),
        )
        assertFalse(transcoded.exists())
        assertFalse(thumb.exists())
    }

    @Test
    fun `a transcoded video with its thumbnail re-uploads as mp4 and re-sends thumbnail and duration`() = runTest {
        store(
            sending("vid1", MessageType.VIDEO, localUri = "/media/vid1.mp4").copy(
                mediaWidth = 1280,
                mediaHeight = 720,
                duration = 12,
                mediaThumbnailUrl = "https://storage.example/vid1_thumb",
            )
        )

        sender.send("vid1", recipientId = "", isRetry = true)

        coVerify(exactly = 0) { videoTranscoder.transcode(any(), any(), any()) }
        coVerify(exactly = 0) { videoTranscoder.extractThumbnail(any()) }
        assertEquals(listOf(Upload("vid1", "video/mp4", reportsProgress = true)), uploads)
        assertEquals(
            Write("vid1", MessageType.VIDEO, "https://storage.example/vid1", "https://storage.example/vid1_thumb", 12, ifAbsent = true),
            writes.single(),
        )
    }

    @Test
    fun `a transcode failure writes nothing back`() = runTest {
        store(sending("vid1", MessageType.VIDEO, localUri = "content://picker/v"))
        coEvery { videoTranscoder.ensureWithinLimits(any()) } returns
            VideoMetadata(width = 1280, height = 720, durationMs = 20_000L, rotationDegrees = 0, sizeBytes = 3_000_000L)
        coEvery { videoTranscoder.transcode(any(), any(), any()) } throws RuntimeException("transcode boom")

        assertTrue(runCatching { sender.send("vid1", recipientId = "") }.isFailure)

        assertTrue(persisted.isEmpty())
        assertTrue(uploads.isEmpty())
        assertTrue(writes.isEmpty())
    }

    // ── documents and voice ─────────────────────────────────────────────────

    @Test
    fun `a document uploads under the picked mime type and its SENT row drops the picked uri`() = runTest {
        store(sending("doc1", MessageType.DOCUMENT, localUri = "content://docs/report.pdf"))

        sender.send("doc1", recipientId = "", sourceMimeType = "application/pdf")

        assertEquals(listOf(Upload("doc1", "application/pdf", reportsProgress = true)), uploads)
        assertNull(stored("doc1").localUri)
    }

    @Test
    fun `a document retry has no mime type and uploads as octet-stream`() = runTest {
        store(sending("doc1", MessageType.DOCUMENT, localUri = "content://docs/report.pdf"))

        sender.send("doc1", recipientId = "", isRetry = true)

        assertEquals(listOf(Upload("doc1", "application/octet-stream", reportsProgress = true)), uploads)
    }

    @Test
    fun `a voice message uploads as aac without progress and is written with its duration`() = runTest {
        store(sending("voice1", MessageType.VOICE, localUri = "/cache/voice1.aac").copy(duration = 5))

        sender.send("voice1", recipientId = "")

        assertEquals(listOf(Upload("voice1", "audio/aac", reportsProgress = false)), uploads)
        assertEquals(
            Write("voice1", MessageType.VOICE, "https://storage.example/voice1", null, 5, ifAbsent = false),
            writes.single(),
        )
        assertEquals("/cache/voice1.aac", stored("voice1").localUri)
    }
}
