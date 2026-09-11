package com.firestream.chat.data.outbox

import android.net.Uri
import com.firestream.chat.data.crypto.EncryptedMessage
import com.firestream.chat.data.crypto.SignalManager
import com.firestream.chat.data.local.PreferencesDataStore
import com.firestream.chat.data.local.VideoQualityOption
import com.firestream.chat.data.local.dao.ChatDao
import com.firestream.chat.data.local.dao.MessageDao
import com.firestream.chat.data.local.entity.MessageEntity
import com.firestream.chat.data.local.entity.MessageRecord
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
 * The [MessageWriter] is real. Unless a test builds an encrypting sender, it
 * runs as a build that never encrypts, so nothing depends on the build type.
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

    /** Every step result or SENT row written back to [rows], in order. */
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
        /** Null for a plaintext write. */
        val ciphertext: String? = null,
        /** What the row held in `outboxCiphertext` at the moment of the write. */
        val ciphertextOnRow: String? = null,
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
        coEvery { messageDao.markSent(any(), any(), any()) } answers {
            val sent = secondArg<MessageRecord>()
            rows.remove(firstArg<String>())
            // What the DAO transaction does: the record, the kept localUri, the outbox columns cleared.
            val row = MessageEntity(sent, localUri = thirdArg())
            rows[row.id] = row
            persisted += row
        }
        coEvery { messageDao.updateSendProgress(any(), any(), any(), any(), any(), any(), any()) } answers {
            val current = rows.getValue(firstArg())
            val row = current.copy(
                localUri = arg(1),
                record = current.record.copy(
                    mediaWidth = arg(2),
                    mediaHeight = arg(3),
                    duration = arg(4),
                    mediaThumbnailUrl = arg(5),
                    mediaUrl = arg(6),
                ),
            )
            rows[row.id] = row
            persisted += row
        }
        coEvery { messageDao.incrementOutboxAttempts(any()) } answers {
            val id = firstArg<String>()
            rows[id] = rows.getValue(id).let { it.copy(outboxAttempts = it.outboxAttempts + 1) }
        }
        coEvery { messageDao.storeOutboxCiphertext(any(), any(), any(), any()) } answers {
            val id = firstArg<String>()
            rows[id] = rows.getValue(id).copy(
                outboxCiphertext = secondArg(), outboxSignalType = thirdArg(), outboxPeerIdentity = arg(3),
            )
        }
        coEvery { storageSource.uploadMedia(any(), any(), any(), any(), any()) } answers {
            val storageId = secondArg<String>()
            uploads += Upload(storageId, arg(3), reportsProgress = args[4] != null)
            "https://storage.example/$storageId"
        }
        recordPlainWrites()
        recordEncryptedWrites()
        every { messageSource.lastContentFor(any(), any()) } returns "preview"
        every { preferencesDataStore.videoQualityFlow } returns flowOf(VideoQualityOption.STANDARD)
        every { preferencesDataStore.e2eEncryptionEnabledFlow } returns flowOf(true)
        // The peer keeps its identity unless a test re-registers it.
        coEvery { signalManager.isCurrentIdentity(any(), any()) } returns true

        sender = newSender(buildEncrypts = false)
    }

    @After
    fun tearDown() {
        unmockkStatic(Uri::class)
    }

    private fun newSender(buildEncrypts: Boolean) = OutboxSender(
        messageDao, chatDao, messageSource, storageSource,
        MessageWriter(messageSource, signalManager, preferencesDataStore, buildEncrypts),
        imageCompressor, videoTranscoder, mediaFileManager, preferencesDataStore,
    )

    /** Records every plaintext write in [writes]. A test that stubs a failing write calls it again to recover. */
    private fun recordPlainWrites() {
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
    }

    /** Records every encrypted write in [writes], with the ciphertext the row held at that moment. */
    private fun recordEncryptedWrites() {
        coEvery { anyEncryptedWrite() } answers {
            val id = arg<String>(2)
            writes += Write(
                messageId = id,
                type = arg(5),
                mediaUrl = arg(8),
                mediaThumbnailUrl = arg(9),
                duration = arg(11),
                ifAbsent = arg(19),
                ciphertext = arg(3),
                ciphertextOnRow = rows[id]?.outboxCiphertext,
            )
            id
        }
    }

    /** Any plaintext write, positional in `MessageSource.sendPlainMessage` order. */
    private suspend fun MockKMatcherScope.anyPlainWrite() = messageSource.sendPlainMessage(
        any(), any(), any(), any(), any(), any(), any(), any(), any(), any(),
        any(), any(), any(), any(), any(), any(), any(), any(), any(),
    )

    /** Any encrypted write, positional in `MessageSource.sendMessage` order. */
    private suspend fun MockKMatcherScope.anyEncryptedWrite() = messageSource.sendMessage(
        any(), any(), any(), any(), any(), any(), any(), any(), any(), any(),
        any(), any(), any(), any(), any(), any(), any(), any(), any(), any(),
    )

    /** Inserts [message] as the repository does, recording its peer — and, for a re-attempt, earlier runs. */
    private fun store(message: Message, recipientId: String = "", attempts: Int = 0) {
        rows[message.id] = MessageEntity.outbox(message, recipientId).copy(outboxAttempts = attempts)
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

    // ── the write ───────────────────────────────────────────────────────────

    @Test
    fun `a first attempt writes under the row id without the if-absent guard, then marks it SENT`() = runTest {
        store(sending("msg1", MessageType.TEXT))

        val sent = sender.send("msg1")

        assertEquals(listOf(Write("msg1", MessageType.TEXT, null, null, null, ifAbsent = false)), writes)
        assertEquals("msg1", sent.id)
        assertEquals(MessageStatus.SENT, stored("msg1").status)
        coVerify(exactly = 1) { chatDao.updateLastMessage("chat1", "msg1", "preview", 1_000L) }
    }

    // Regression for the duplicate-on-retry bug: a retry used to `add()` under a
    // new auto-id, so a first write that landed after its await was cancelled
    // reached the recipient twice. A re-attempt re-writes the row's own id and
    // asks the source to create the document only if it is absent.
    @Test
    fun `a failed write counts as an attempt, so the next run re-writes the same id only if absent`() = runTest {
        coEvery { anyPlainWrite() } throws IOException("offline")
        store(sending("msg1", MessageType.TEXT))

        val error = runCatching { sender.send("msg1") }.exceptionOrNull()

        assertTrue(error is IOException)
        assertEquals(MessageStatus.SENDING, stored("msg1").status)
        assertEquals(1, rows.getValue("msg1").outboxAttempts)
        coVerify(exactly = 0) { chatDao.updateLastMessage(any(), any(), any(), any()) }

        recordPlainWrites()
        sender.send("msg1")

        assertEquals(listOf(Write("msg1", MessageType.TEXT, null, null, null, ifAbsent = true)), writes)
        assertEquals(0, rows.getValue("msg1").outboxAttempts)
    }

    // The newer-only rule is ChatDao's (ChatDaoLastMessageTest), so a re-attempt
    // updates the preview exactly as a first attempt does — including a retry whose
    // earlier run never reached its write, which used to be treated as a first send.
    @Test
    fun `a re-attempt points the chat preview at the sent row like a first attempt`() = runTest {
        // A backend that mints its own ids (PocketBase) answers with a different one.
        coEvery { anyPlainWrite() } answers { "server-${arg<String>(2)}" }
        store(sending("msg1", MessageType.TEXT), attempts = 1)

        sender.send("msg1")

        assertFalse("msg1" in rows)
        assertEquals(MessageStatus.SENT, stored("server-msg1").status)
        coVerify(exactly = 1) { chatDao.updateLastMessage("chat1", "server-msg1", "preview", 1_000L) }
        coVerify(exactly = 0) { chatDao.getChatById(any()) }
    }

    @Test
    fun `a type the outbox does not send is refused before any IO`() = runTest {
        store(sending("timer1", MessageType.TIMER))

        val error = runCatching { sender.send("timer1") }.exceptionOrNull()

        assertTrue(error is IllegalStateException)
        assertTrue(persisted.isEmpty())
        assertTrue(writes.isEmpty())
        coVerify(exactly = 0) { messageDao.incrementOutboxAttempts(any()) }
    }

    @Test
    fun `an unknown id is refused`() = runTest {
        val error = runCatching { sender.send("ghost") }.exceptionOrNull()

        assertTrue(error is IllegalStateException)
    }

    // A row whose outbox columns were reset by a whole-row replace has lost the
    // peer. Treating that like a group chat would send a 1:1 message in plaintext.
    @Test
    fun `a row with no recorded recipient is refused rather than sent in plaintext`() = runTest {
        rows["msg1"] = MessageEntity.fromDomain(sending("msg1", MessageType.TEXT))

        val error = runCatching { newSender(buildEncrypts = true).send("msg1") }.exceptionOrNull()

        assertTrue(error is IllegalStateException)
        assertTrue(writes.isEmpty())
        coVerify(exactly = 0) { signalManager.encrypt(any(), any()) }
    }

    // ── encryption ──────────────────────────────────────────────────────────

    @Test
    fun `an encrypted first attempt keeps the ciphertext on the row before writing it`() = runTest {
        coEvery { signalManager.encrypt("peer1", "hello") } returns EncryptedMessage("cipher-1", signalType = 3, peerIdentity = "id-1")
        store(sending("msg1", MessageType.TEXT).copy(content = "hello"), recipientId = "peer1")

        newSender(buildEncrypts = true).send("msg1")

        assertEquals(
            Write("msg1", MessageType.TEXT, null, null, null, ifAbsent = false, "cipher-1", ciphertextOnRow = "cipher-1"),
            writes.single(),
        )
        val sent = rows.getValue("msg1")
        assertEquals(MessageStatus.SENT.name, sent.status)
        assertNull("the SENT row sheds the ciphertext", sent.outboxCiphertext)
        assertNull(sent.outboxPeerIdentity)
        assertNull(sent.outboxRecipientId)
    }

    @Test
    fun `a second attempt reuses the stored ciphertext and never encrypts again`() = runTest {
        coEvery { signalManager.encrypt("peer1", "hello") } returns EncryptedMessage("cipher-1", signalType = 3, peerIdentity = "id-1")
        coEvery { anyEncryptedWrite() } throws IOException("ack timed out")
        store(sending("msg1", MessageType.TEXT).copy(content = "hello"), recipientId = "peer1")
        val encrypting = newSender(buildEncrypts = true)

        assertTrue(runCatching { encrypting.send("msg1") }.exceptionOrNull() is IOException)
        assertEquals("cipher-1", rows.getValue("msg1").outboxCiphertext)
        assertEquals("id-1", rows.getValue("msg1").outboxPeerIdentity)

        recordEncryptedWrites()
        encrypting.send("msg1")

        coVerify(exactly = 1) { signalManager.encrypt(any(), any()) }
        coVerify(exactly = 1) { signalManager.isCurrentIdentity("peer1", "id-1") }
        assertEquals(
            listOf(Write("msg1", MessageType.TEXT, null, null, null, ifAbsent = true, "cipher-1", ciphertextOnRow = "cipher-1")),
            writes,
        )
    }

    // A ciphertext belongs to the session it was encrypted under. A peer who
    // reinstalled between the attempts has a new identity and no such session,
    // so writing the stored bytes would land a message they can never read while
    // this side shows it SENT.
    @Test
    fun `a stored ciphertext is encrypted again once the peer has re-registered`() = runTest {
        coEvery { signalManager.encrypt("peer1", "hello") } returns EncryptedMessage("cipher-2", signalType = 3, peerIdentity = "id-2")
        coEvery { signalManager.isCurrentIdentity("peer1", "id-1") } returns false
        rows["msg1"] = MessageEntity.outbox(sending("msg1", MessageType.TEXT).copy(content = "hello"), "peer1")
            .copy(outboxCiphertext = "cipher-1", outboxSignalType = 3, outboxPeerIdentity = "id-1", outboxAttempts = 1)

        newSender(buildEncrypts = true).send("msg1")

        coVerify(exactly = 1) { signalManager.encrypt("peer1", "hello") }
        assertEquals(
            listOf(Write("msg1", MessageType.TEXT, null, null, null, ifAbsent = true, "cipher-2", ciphertextOnRow = "cipher-2")),
            writes,
        )
    }

    @Test
    fun `a stored ciphertext without a recorded peer identity is not reused`() = runTest {
        coEvery { signalManager.encrypt("peer1", "hello") } returns EncryptedMessage("cipher-2", signalType = 3, peerIdentity = "id-2")
        rows["msg1"] = MessageEntity.outbox(sending("msg1", MessageType.TEXT).copy(content = "hello"), "peer1")
            .copy(outboxCiphertext = "cipher-1", outboxSignalType = 3, outboxAttempts = 1)

        newSender(buildEncrypts = true).send("msg1")

        coVerify(exactly = 0) { signalManager.isCurrentIdentity(any(), any()) }
        assertEquals("cipher-2", writes.single().ciphertext)
    }

    @Test
    fun `media is encrypted after its upload, so a failed upload leaves nothing to reuse`() = runTest {
        coEvery { signalManager.encrypt(any(), any()) } returns EncryptedMessage("cipher-voice", signalType = 3)
        coEvery { storageSource.uploadMedia(any(), any(), any(), any(), any()) } throws IOException("network down")
        store(sending("voice1", MessageType.VOICE, localUri = "/cache/voice1.aac"), recipientId = "peer1")

        assertTrue(runCatching { newSender(buildEncrypts = true).send("voice1") }.exceptionOrNull() is IOException)

        coVerify(exactly = 0) { signalManager.encrypt(any(), any()) }
        assertNull(rows.getValue("voice1").outboxCiphertext)
        assertEquals(1, rows.getValue("voice1").outboxAttempts)
    }

    // ── images ──────────────────────────────────────────────────────────────

    @Test
    fun `an image is compressed at the row's HD setting, then each step persists before the next`() = runTest {
        val compressed = File.createTempFile("img_", ".jpg")
        store(sending("img1", MessageType.IMAGE, localUri = "content://picker/1").copy(isHd = true))
        coEvery { imageCompressor.processImage(any(), any()) } returns
            ImageResult(compressed, width = 800, height = 600, mimeType = "image/jpeg")
        coEvery { mediaFileManager.copyToLocal("chat1", "img1", any(), "jpg") } returns File("/media/img1.jpg")

        sender.send("img1")

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

        assertTrue(runCatching { sender.send("img1") }.exceptionOrNull() is IOException)

        val afterFailure = stored("img1")
        assertEquals(MessageStatus.SENDING, afterFailure.status)
        assertEquals("/media/img1.jpg", afterFailure.localUri)
        assertEquals(800, afterFailure.mediaWidth)
        assertNull(afterFailure.mediaUrl)
        assertTrue(writes.isEmpty())
        assertTrue(sender.uploadProgress.value.isEmpty())

        coEvery { storageSource.uploadMedia(any(), any(), any(), any(), any()) } returns "https://storage.example/img1"
        sender.send("img1")

        coVerify(exactly = 1) { imageCompressor.processImage(any(), any()) }
        assertEquals(MessageStatus.SENT, stored("img1").status)
        assertTrue(writes.single().ifAbsent)
    }

    @Test
    fun `an image whose dimensions are known is not compressed again`() = runTest {
        store(
            sending("img1", MessageType.IMAGE, localUri = "/media/img1.jpg")
                .copy(mediaWidth = 1024, mediaHeight = 768),
            attempts = 1,
        )

        sender.send("img1")

        coVerify(exactly = 0) { imageCompressor.processImage(any(), any()) }
        assertEquals(listOf(Upload("img1", "image/jpeg", reportsProgress = true)), uploads)
    }

    // A forwarded photo carries the source message's upload, and may carry none
    // of its dimensions or local file (a photo received from an older client).
    // Its retry must not try to compress a file it does not have.
    @Test
    fun `a forwarded image whose media is already uploaded skips every media step on retry`() = runTest {
        store(
            sending("fwd1", MessageType.IMAGE, localUri = null)
                .copy(mediaUrl = "https://storage.example/source", isForwarded = true),
            attempts = 1,
        )

        sender.send("fwd1")

        coVerify(exactly = 0) { imageCompressor.processImage(any(), any()) }
        assertTrue(uploads.isEmpty())
        assertEquals("https://storage.example/source", writes.single().mediaUrl)
        assertEquals(MessageStatus.SENT, stored("fwd1").status)
    }

    @Test
    fun `a forwarded video without a thumbnail is written as it is rather than re-thumbnailed`() = runTest {
        store(
            sending("fwd1", MessageType.VIDEO, localUri = null)
                .copy(mediaUrl = "https://storage.example/source", duration = 12, isForwarded = true),
            attempts = 1,
        )

        sender.send("fwd1")

        coVerify(exactly = 0) { videoTranscoder.extractThumbnail(any()) }
        coVerify(exactly = 0) { videoTranscoder.transcode(any(), any(), any()) }
        assertEquals(
            Write("fwd1", MessageType.VIDEO, "https://storage.example/source", null, 12, ifAbsent = true),
            writes.single(),
        )
    }

    @Test
    fun `media whose upload already finished is written without uploading again`() = runTest {
        store(
            sending("img1", MessageType.IMAGE, localUri = "/media/img1.jpg")
                .copy(mediaWidth = 1024, mediaHeight = 768, mediaUrl = "https://storage.example/earlier"),
            attempts = 1,
        )

        sender.send("img1")

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

        sender.send("doc1")

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

        sender.send("vid1")

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
            ),
            attempts = 1,
        )

        sender.send("vid1")

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

        assertTrue(runCatching { sender.send("vid1") }.isFailure)

        assertTrue(persisted.isEmpty())
        assertTrue(uploads.isEmpty())
        assertTrue(writes.isEmpty())
    }

    // ── documents and voice ─────────────────────────────────────────────────

    @Test
    fun `a document uploads under the picked mime type and its SENT row drops the picked uri`() = runTest {
        store(sending("doc1", MessageType.DOCUMENT, localUri = "content://docs/report.pdf"))

        sender.send("doc1", sourceMimeType = "application/pdf")

        assertEquals(listOf(Upload("doc1", "application/pdf", reportsProgress = true)), uploads)
        assertNull(stored("doc1").localUri)
    }

    @Test
    fun `a document retry has no mime type and uploads as octet-stream`() = runTest {
        store(sending("doc1", MessageType.DOCUMENT, localUri = "content://docs/report.pdf"), attempts = 1)

        sender.send("doc1")

        assertEquals(listOf(Upload("doc1", "application/octet-stream", reportsProgress = true)), uploads)
    }

    @Test
    fun `a voice message uploads as aac without progress and is written with its duration`() = runTest {
        store(sending("voice1", MessageType.VOICE, localUri = "/cache/voice1.aac").copy(duration = 5))

        sender.send("voice1")

        assertEquals(listOf(Upload("voice1", "audio/aac", reportsProgress = false)), uploads)
        assertEquals(
            Write("voice1", MessageType.VOICE, "https://storage.example/voice1", null, 5, ifAbsent = false),
            writes.single(),
        )
        assertEquals("/cache/voice1.aac", stored("voice1").localUri)
    }
}
