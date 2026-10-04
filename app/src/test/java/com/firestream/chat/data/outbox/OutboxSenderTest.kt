package com.firestream.chat.data.outbox

import android.net.Uri
import com.firestream.chat.data.crypto.EncryptedMessage
import com.firestream.chat.data.crypto.SignalManager
import com.firestream.chat.data.local.PreferencesDataStore
import com.firestream.chat.data.local.VideoQualityOption
import com.firestream.chat.data.local.dao.ChatDao
import com.firestream.chat.data.local.dao.MessageDao
import com.firestream.chat.data.local.dao.StickerDao
import com.firestream.chat.data.local.entity.MessageEntity
import com.firestream.chat.data.local.entity.MessageRecord
import com.firestream.chat.data.local.entity.StickerEntity
import com.firestream.chat.data.remote.source.MessageSource
import com.firestream.chat.data.remote.source.StickerObjectSource
import com.firestream.chat.data.remote.source.StickerRef
import com.firestream.chat.data.remote.source.StorageSource
import com.firestream.chat.data.sticker.StickerFiles
import com.firestream.chat.data.sticker.StickerUploads
import com.firestream.chat.data.util.ImageCompressor
import com.firestream.chat.data.util.ImageResult
import com.firestream.chat.data.util.DocumentFiles
import com.firestream.chat.data.util.MediaFileManager
import com.firestream.chat.data.util.VideoMetadata
import com.firestream.chat.data.util.VideoResult
import com.firestream.chat.data.util.VideoTranscoder
import com.firestream.chat.domain.model.Message
import com.firestream.chat.domain.model.MessageStatus
import com.firestream.chat.domain.model.MessageType
import com.firestream.chat.domain.model.StickerFormat
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
    private val outboxFiles = mockk<OutboxFiles>(relaxed = true)
    private val documentFiles = mockk<DocumentFiles>(relaxed = true)
    private val stickerDao = mockk<StickerDao>(relaxed = true)
    private val stickerFiles = mockk<StickerFiles>()
    private val stickerObjectSource = mockk<StickerObjectSource>()

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
        /** Recorded for a plaintext write only. */
        val sticker: StickerRef? = null,
    )

    /** The `stickers` table, keyed by id. */
    private val stickers = mutableMapOf<String, StickerEntity>()

    /** Every sticker id `ensureUploaded` was asked for, in order. */
    private val stickerUploads = mutableListOf<String>()
    private val stickerFile = File.createTempFile("sticker", ".webp").apply { deleteOnExit() }

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
            val current = rows[firstArg<String>()]
            // What the DAO transaction does: declined for a row gone or deleted meanwhile;
            // otherwise the record, the kept localUri, the outbox columns cleared.
            if (current == null || current.deletedAt != null) {
                false
            } else {
                rows.remove(firstArg<String>())
                val row = MessageEntity(sent, localUri = thirdArg())
                rows[row.id] = row
                persisted += row
                true
            }
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
        coEvery { messageDao.acknowledge(any(), any()) } answers {
            val id = firstArg<String>()
            rows[id] = rows.getValue(id).let {
                it.copy(
                    record = it.record.copy(status = secondArg()),
                    outboxRecipientId = null, outboxCiphertext = null, outboxSignalType = null,
                    outboxPeerIdentity = null, outboxAttempts = 0,
                )
            }
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
        every { preferencesDataStore.keepOriginalImagesFlow } returns flowOf(false)
        every { preferencesDataStore.e2eEncryptionEnabledFlow } returns flowOf(true)
        // The peer keeps its identity unless a test re-registers it.
        coEvery { signalManager.isCurrentIdentity(any(), any()) } returns true
        // Only the media dir is a place the app keeps files; a staged copy or a cache path is not.
        every { outboxFiles.isDurable(any()) } answers { firstArg<String>().startsWith("/media/") }
        every { outboxFiles.mimeTypeOf(any()) } returns null
        // A relaxed mock would answer with a mock pair, not with "no bounds".
        coEvery { documentFiles.imageBounds(any()) } returns null
        coEvery { stickerDao.getSticker(any()) } answers { stickers[firstArg()] }
        coEvery { stickerDao.setRemoteUrl(any(), any()) } answers {
            val id = firstArg<String>()
            stickers[id] = stickers.getValue(id).copy(remoteUrl = secondArg())
        }
        every { stickerFiles.fileFor(any(), any()) } returns stickerFile
        coEvery { stickerObjectSource.ensureUploaded(any(), any(), any(), any()) } answers {
            stickerUploads += firstArg<String>()
            stickerUrl(firstArg())
        }

        sender = newSender(buildEncrypts = false)
    }

    @After
    fun tearDown() {
        unmockkStatic(Uri::class)
    }

    private fun newSender(buildEncrypts: Boolean) = OutboxSender(
        messageDao, chatDao, messageSource, storageSource,
        MessageWriter(messageSource, signalManager, preferencesDataStore, buildEncrypts),
        outboxFiles, imageCompressor, videoTranscoder, mediaFileManager, preferencesDataStore, documentFiles,
        StickerUploads(stickerDao, stickerFiles, stickerObjectSource),
    )

    private fun stickerUrl(stickerId: String) = "https://storage.example/stickers/$stickerId.webp"

    /** Puts a sticker into the library, with [remoteUrl] when an earlier send uploaded it. */
    private fun librarySticker(id: String, remoteUrl: String? = null) {
        stickers[id] = StickerEntity(
            id = id, format = StickerFormat.WEBP.name, width = 512, height = 512, isAnimated = false,
            emojis = listOf("😀"), createdAt = 1L, remoteUrl = remoteUrl,
        )
    }

    /** A sticker message as the repository inserts it: the library file as `localUri`, the url only when known. */
    private fun stickerMessage(id: String, stickerId: String, mediaUrl: String? = null) =
        sending(id, MessageType.STICKER, localUri = stickerFile.path).copy(
            content = "😀", stickerId = stickerId, stickerPackId = "pack1", mediaUrl = mediaUrl, mimeType = "image/webp",
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
                sticker = arg(19),
                ifAbsent = arg(20),
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
                ifAbsent = arg(21),
                ciphertext = arg(3),
                ciphertextOnRow = rows[id]?.outboxCiphertext,
            )
            id
        }
    }

    /** Any plaintext write, positional in `MessageSource.sendPlainMessage` order. */
    private suspend fun MockKMatcherScope.anyPlainWrite() = messageSource.sendPlainMessage(
        any(), any(), any(), any(), any(), any(), any(), any(), any(), any(),
        any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(),
    )

    /** Any encrypted write, positional in `MessageSource.sendMessage` order. */
    private suspend fun MockKMatcherScope.anyEncryptedWrite() = messageSource.sendMessage(
        any(), any(), any(), any(), any(), any(), any(), any(), any(), any(),
        any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(),
    )

    /** Inserts [message] as the repository does, recording its target — and, for a re-attempt, earlier runs. */
    private fun store(message: Message, target: SendTarget = SendTarget.NoPeer, attempts: Int = 0) {
        rows[message.id] = MessageEntity.outbox(message, target).copy(outboxAttempts = attempts)
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
        coVerify(exactly = 1) { outboxFiles.delete("msg1") }
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

    // The worker decides to run outside this lock; an acknowledged echo can heal
    // the row — SENT, outbox columns cleared — in between. Refusing the row for
    // its missing target would then fail a message the backend has.
    @Test
    fun `a row the backend acknowledged meanwhile owes nothing and is returned as it is`() = runTest {
        rows["msg1"] = MessageEntity.fromDomain(sending("msg1", MessageType.TEXT).copy(status = MessageStatus.SENT))

        val result = sender.send("msg1")

        assertEquals(MessageStatus.SENT, result.status)
        assertTrue(writes.isEmpty())
        coVerify(exactly = 0) { messageDao.incrementOutboxAttempts(any()) }
        coVerify(exactly = 0) { messageDao.markSent(any(), any(), any()) }
    }

    // A delete that lands while the write is in flight must not be undone by the
    // SENT transaction: the row stays deleted and queued, and the tombstone run
    // the delete enqueued finds the document this write created.
    @Test
    fun `a write whose row was deleted meanwhile does not undelete it, and leaves the tombstone owed`() = runTest {
        store(sending("msg1", MessageType.TEXT))
        coEvery { anyPlainWrite() } answers {
            writes += Write(arg(2), arg(4), arg(7), arg(8), arg(10), ifAbsent = arg(20))
            rows["msg1"] = rows.getValue("msg1").let { it.copy(record = it.record.copy(deletedAt = 5_000L, content = "")) }
            arg<String>(2)
        }

        sender.send("msg1")

        val row = rows.getValue("msg1")
        assertEquals(5_000L, row.deletedAt)
        assertEquals(MessageStatus.SENDING.name, row.status)
        assertEquals(OutboxJob.TOMBSTONE, row.outboxJob)
        assertEquals(1, row.outboxAttempts)
        coVerify(exactly = 0) { chatDao.updateLastMessage(any(), any(), any(), any()) }
        coVerify(exactly = 0) { outboxFiles.delete(any()) }
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
        store(sending("msg1", MessageType.TEXT).copy(content = "hello"), target = SendTarget.Peer("peer1"))

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
        store(sending("msg1", MessageType.TEXT).copy(content = "hello"), target = SendTarget.Peer("peer1"))
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
        rows["msg1"] = MessageEntity.outbox(sending("msg1", MessageType.TEXT).copy(content = "hello"), SendTarget.Peer("peer1"))
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
        rows["msg1"] = MessageEntity.outbox(sending("msg1", MessageType.TEXT).copy(content = "hello"), SendTarget.Peer("peer1"))
            .copy(outboxCiphertext = "cipher-1", outboxSignalType = 3, outboxAttempts = 1)

        newSender(buildEncrypts = true).send("msg1")

        coVerify(exactly = 0) { signalManager.isCurrentIdentity(any(), any()) }
        assertEquals("cipher-2", writes.single().ciphertext)
    }

    @Test
    fun `media is encrypted after its upload, so a failed upload leaves nothing to reuse`() = runTest {
        coEvery { signalManager.encrypt(any(), any()) } returns EncryptedMessage("cipher-voice", signalType = 3)
        coEvery { storageSource.uploadMedia(any(), any(), any(), any(), any()) } throws IOException("network down")
        store(sending("voice1", MessageType.VOICE, localUri = "/cache/voice1.aac"), target = SendTarget.Peer("peer1"))

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
        // The media dir copy is a file the app keeps; the SENT row goes on showing it.
        assertEquals("/media/img1.jpg", stored("img1").localUri)
        coVerify(exactly = 1) { outboxFiles.delete("img1") }
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

    // ── keep original images ────────────────────────────────────────────────

    @Test
    fun `with keep-original on, the media dir gets the input and the backend the encoding, in one persisted step`() = runTest {
        every { preferencesDataStore.keepOriginalImagesFlow } returns flowOf(true)
        val compressed = File.createTempFile("img_", ".jpg")
        val sourceUri = mockk<Uri>(relaxed = true)
        val encodedUri = mockk<Uri>(relaxed = true)
        every { Uri.parse("content://picker/1") } returns sourceUri
        every { Uri.fromFile(compressed) } returns encodedUri
        store(sending("img1", MessageType.IMAGE, localUri = "content://picker/1").copy(isHd = true))
        coEvery { imageCompressor.processImage(sourceUri, any()) } returns
            ImageResult(compressed, width = 800, height = 600, mimeType = "image/jpeg")
        coEvery { mediaFileManager.copyToLocal("chat1", "img1", sourceUri, "jpg") } returns File("/media/img1.jpg")

        sender.send("img1")

        // The encoding goes up at the row's HD setting; the input, not the encoding, is copied.
        coVerify(exactly = 1) { imageCompressor.processImage(sourceUri, fullQuality = true) }
        coVerify(exactly = 1) { storageSource.uploadMedia("chat1", "img1", encodedUri, "image/jpeg", any()) }
        coVerify(exactly = 1) { mediaFileManager.copyToLocal("chat1", "img1", sourceUri, "jpg") }
        assertEquals(listOf(Upload("img1", "image/jpeg", reportsProgress = true)), uploads)
        // One resume point, holding the local file, the sent dimensions and the URL together, then SENT.
        assertEquals(listOf("SENDING", "SENT"), persisted.map { it.status })
        assertEquals("/media/img1.jpg", persisted[0].localUri)
        assertEquals(800, persisted[0].mediaWidth)
        assertEquals(600, persisted[0].mediaHeight)
        assertEquals("https://storage.example/img1", persisted[0].mediaUrl)
        assertEquals("https://storage.example/img1", writes.single().mediaUrl)
        assertFalse("encoder output in cacheDir is cleaned up", compressed.exists())
        assertEquals("/media/img1.jpg", stored("img1").localUri)
        assertTrue(sender.uploadProgress.value.isEmpty())
    }

    @Test
    fun `with keep-original on, an upload failure persists nothing, so the retry encodes and uploads again`() = runTest {
        every { preferencesDataStore.keepOriginalImagesFlow } returns flowOf(true)
        store(sending("img1", MessageType.IMAGE, localUri = "content://picker/1"))
        coEvery { imageCompressor.processImage(any(), any()) } answers {
            ImageResult(File.createTempFile("img_", ".jpg"), width = 800, height = 600, mimeType = "image/jpeg")
        }
        coEvery { mediaFileManager.copyToLocal(any(), any(), any(), any()) } returns File("/media/img1.jpg")
        coEvery { storageSource.uploadMedia(any(), any(), any(), any(), any()) } throws IOException("network down")

        assertTrue(runCatching { sender.send("img1") }.exceptionOrNull() is IOException)

        // Nothing half-done on the row: the plain pipeline must never find the
        // original in the media dir looking like an encoding still to upload.
        val afterFailure = stored("img1")
        assertEquals(MessageStatus.SENDING, afterFailure.status)
        assertEquals("content://picker/1", afterFailure.localUri)
        assertNull(afterFailure.mediaWidth)
        assertNull(afterFailure.mediaUrl)
        assertTrue(persisted.isEmpty())
        coVerify(exactly = 0) { mediaFileManager.copyToLocal(any(), any(), any(), any()) }
        assertTrue(sender.uploadProgress.value.isEmpty())

        coEvery { storageSource.uploadMedia(any(), any(), any(), any(), any()) } answers {
            uploads += Upload(secondArg(), arg(3), reportsProgress = args[4] != null)
            "https://storage.example/img1"
        }
        sender.send("img1")

        coVerify(exactly = 2) { imageCompressor.processImage(any(), any()) }
        assertEquals(MessageStatus.SENT, stored("img1").status)
        assertEquals("/media/img1.jpg", stored("img1").localUri)
        assertTrue(writes.single().ifAbsent)
    }

    @Test
    fun `keep-original does not apply to a row an earlier attempt already encoded into the media dir`() = runTest {
        every { preferencesDataStore.keepOriginalImagesFlow } returns flowOf(true)
        store(
            sending("img1", MessageType.IMAGE, localUri = "/media/img1.jpg")
                .copy(mediaWidth = 1024, mediaHeight = 768),
            attempts = 1,
        )

        sender.send("img1")

        // The encoding already in the media dir is what goes up; the input is long gone.
        coVerify(exactly = 0) { imageCompressor.processImage(any(), any()) }
        coVerify(exactly = 0) { mediaFileManager.copyToLocal(any(), any(), any(), any()) }
        assertEquals(listOf(Upload("img1", "image/jpeg", reportsProgress = true)), uploads)
        assertEquals(MessageStatus.SENT, stored("img1").status)
    }

    @Test
    fun `with keep-original on, a video is untouched by the setting and still transcodes into the media dir`() = runTest {
        // The setting names images; a video's local file stays the transcode
        // whatever it says, so the row is prepared exactly as before.
        every { preferencesDataStore.keepOriginalImagesFlow } returns flowOf(true)
        val transcoded = File.createTempFile("vid_", ".mp4")
        val thumb = File.createTempFile("thumb_", ".jpg")
        store(sending("vid1", MessageType.VIDEO, localUri = "content://picker/2"))
        coEvery { videoTranscoder.ensureWithinLimits(any()) } returns
            VideoMetadata(width = 1920, height = 1080, durationMs = 12_000L, rotationDegrees = 0, sizeBytes = 5_000_000L)
        coEvery { videoTranscoder.transcode(any(), any(), any()) } returns VideoResult(transcoded, 1280, 720, 12)
        coEvery { videoTranscoder.extractThumbnail(any()) } returns thumb
        coEvery { mediaFileManager.copyToLocal("chat1", "vid1", any(), "mp4") } returns File("/media/vid1.mp4")

        sender.send("vid1")

        coVerify(exactly = 1) { videoTranscoder.transcode(any(), any(), any()) }
        assertEquals("/media/vid1.mp4", stored("vid1").localUri)
        assertEquals(MessageStatus.SENT, stored("vid1").status)
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

    // A row queued before `mimeType` was a column: the picked type travels as the
    // staged copy's extension, so a retry — which has only the row — uploads it
    // under the right type too.
    @Test
    fun `a legacy document uploads under the type its staged copy carries`() = runTest {
        val staged = "/data/outbox/doc1.pdf"
        every { outboxFiles.mimeTypeOf(staged) } returns "application/pdf"
        every { outboxFiles.isStaged(staged) } returns true
        // An earlier run already moved it: nothing left to move, and SENT drops the path.
        coEvery { documentFiles.adopt("doc1", staged, null, null) } returns null
        every { outboxFiles.isDurable(staged) } returns false
        store(sending("doc1", MessageType.DOCUMENT, localUri = staged), attempts = 1)

        sender.send("doc1")

        assertEquals(listOf(Upload("doc1", "application/pdf", reportsProgress = true)), uploads)
        assertNull(stored("doc1").localUri)
        coVerify(exactly = 1) { outboxFiles.delete("doc1") }
    }

    // The sender keeps its own document: the uploaded staged copy moves into the
    // documents dir, so there is a file to open without downloading it back.
    @Test
    fun `a document uploads under its picked type and its SENT row keeps the moved copy`() = runTest {
        val staged = "/data/outbox/doc1.bin"
        val kept = "/data/documents/doc1.docx"
        val docx = "application/vnd.openxmlformats-officedocument.wordprocessingml.document"
        every { outboxFiles.isStaged(staged) } returns true
        every { outboxFiles.isDurable(kept) } returns true
        coEvery { documentFiles.adopt("doc1", staged, "Plan.docx", docx) } returns kept
        store(
            sending("doc1", MessageType.DOCUMENT, localUri = staged)
                .copy(fileName = "Plan.docx", fileSize = 2_048L, mimeType = docx),
        )

        sender.send("doc1")

        assertEquals(listOf(Upload("doc1", docx, reportsProgress = true)), uploads)
        val sent = stored("doc1")
        assertEquals(kept, sent.localUri)
        assertEquals("Plan.docx", sent.fileName)
        assertEquals(2_048L, sent.fileSize)
        coVerify(exactly = 1) { messageDao.updateLocalUri("doc1", kept) }
    }

    @Test
    fun `a document whose extension maps to no type uploads as octet-stream`() = runTest {
        store(sending("doc1", MessageType.DOCUMENT, localUri = "/data/outbox/doc1.bin"))

        sender.send("doc1")

        assertEquals(listOf(Upload("doc1", "application/octet-stream", reportsProgress = true)), uploads)
    }

    // The receiver's inline player shows an audio file's length before play; it is
    // read from the staged copy here, not from the picker's provider before the insert.
    @Test
    fun `an audio document is written with the playing time read from its staged copy`() = runTest {
        val staged = "/data/outbox/song1.mp3"
        coEvery { documentFiles.audioDurationSeconds(staged) } returns 205
        store(sending("song1", MessageType.DOCUMENT, localUri = staged).copy(fileName = "song.mp3", mimeType = "audio/mpeg"))

        sender.send("song1")

        assertEquals(205, writes.single().duration)
    }

    @Test
    fun `a non-audio document never reads a playing time`() = runTest {
        store(sending("doc1", MessageType.DOCUMENT, localUri = "/data/outbox/doc1.pdf").copy(fileName = "a.pdf", mimeType = "application/pdf"))

        sender.send("doc1")

        coVerify(exactly = 0) { documentFiles.audioDurationSeconds(any()) }
    }

    // ── stickers ────────────────────────────────────────────────────────────

    @Test
    fun `a sticker no one has sent yet gets its url from the shared object, kept on the message and the library row`() = runTest {
        librarySticker(STICKER_ID)
        every { outboxFiles.isDurable(stickerFile.path) } returns true
        store(stickerMessage("st1", STICKER_ID))

        sender.send("st1")

        assertEquals(listOf(STICKER_ID), stickerUploads)
        // Nothing went up under the message id: a sticker has no bytes of its own.
        assertTrue(uploads.isEmpty())
        val write = writes.single()
        assertEquals(stickerUrl(STICKER_ID), write.mediaUrl)
        assertEquals(StickerRef(STICKER_ID, "pack1"), write.sticker)
        assertEquals(stickerUrl(STICKER_ID), stickers.getValue(STICKER_ID).remoteUrl)
        // The library file is the app's own, so the SENT row goes on pointing at it.
        assertEquals(stickerFile.path, stored("st1").localUri)
    }

    @Test
    fun `the second send of a sticker uploads nothing`() = runTest {
        librarySticker(STICKER_ID)
        // Both rows were inserted before either send ran, so neither has a url yet.
        store(stickerMessage("st1", STICKER_ID))
        store(stickerMessage("st2", STICKER_ID))

        sender.send("st1")
        sender.send("st2")

        assertEquals(listOf(STICKER_ID), stickerUploads)
        assertEquals(listOf(stickerUrl(STICKER_ID), stickerUrl(STICKER_ID)), writes.map { it.mediaUrl })
    }

    @Test
    fun `a sticker row that already has its url reads no library row and asks no backend`() = runTest {
        store(stickerMessage("st1", STICKER_ID, mediaUrl = stickerUrl(STICKER_ID)))

        sender.send("st1")

        assertTrue(stickerUploads.isEmpty())
        coVerify(exactly = 0) { stickerDao.getSticker(any()) }
        assertEquals(stickerUrl(STICKER_ID), writes.single().mediaUrl)
    }

    @Test
    fun `a sticker whose upload failed stores no url, and the retry asks again`() = runTest {
        librarySticker(STICKER_ID)
        store(stickerMessage("st1", STICKER_ID))
        coEvery { stickerObjectSource.ensureUploaded(any(), any(), any(), any()) } throws IOException("offline")

        runCatching { sender.send("st1") }

        assertNull(stored("st1").mediaUrl)
        assertNull(stickers.getValue(STICKER_ID).remoteUrl)
        assertTrue(writes.isEmpty())

        coEvery { stickerObjectSource.ensureUploaded(any(), any(), any(), any()) } answers { stickerUrl(firstArg()) }
        sender.send("st1")

        assertEquals(stickerUrl(STICKER_ID), writes.single().mediaUrl)
        assertTrue(writes.single().ifAbsent)
    }

    @Test
    fun `a sticker message that names no library sticker is refused before any upload`() = runTest {
        store(stickerMessage("st1", STICKER_ID))
        store(stickerMessage("st2", "../../etc/passwd"))

        assertTrue(runCatching { sender.send("st1") }.exceptionOrNull() is IllegalStateException)
        assertTrue(runCatching { sender.send("st2") }.exceptionOrNull() is IllegalStateException)

        assertTrue(stickerUploads.isEmpty())
        assertTrue(writes.isEmpty())
    }

    // ── GIFs ────────────────────────────────────────────────────────────────

    // A GIF takes the document route: the staged bytes go up as they are. The
    // compressor and the media dir are strict mocks here, so a call to either
    // would fail the test by itself; the verifies say so out loud.
    @Test
    fun `a GIF is uploaded as it is under its own type, and its SENT row keeps the copy in the documents dir`() = runTest {
        val staged = "/data/outbox/gif1.gif"
        val kept = "/data/documents/gif1.gif"
        every { outboxFiles.isStaged(staged) } returns true
        every { outboxFiles.isDurable(kept) } returns true
        coEvery { documentFiles.imageBounds(staged) } returns (320 to 240)
        coEvery { documentFiles.adopt("gif1", staged, null, "image/gif") } returns kept
        // "Keep original images" is about photos. A GIF never reaches the compressor either way.
        every { preferencesDataStore.keepOriginalImagesFlow } returns flowOf(true)
        store(sending("gif1", MessageType.GIF, localUri = staged).copy(content = "look", mimeType = "image/gif"))

        sender.send("gif1")

        assertEquals(listOf(Upload("gif1", "image/gif", reportsProgress = true)), uploads)
        coVerify(exactly = 0) { imageCompressor.processImage(any(), any()) }
        coVerify(exactly = 0) { mediaFileManager.copyToLocal(any(), any(), any(), any()) }
        val sent = stored("gif1")
        assertEquals(320, sent.mediaWidth)
        assertEquals(240, sent.mediaHeight)
        assertEquals(kept, sent.localUri)
        assertEquals(MessageType.GIF, writes.single().type)
    }

    @Test
    fun `a GIF whose upload failed keeps its bounds, and the retry uploads without reading them again`() = runTest {
        val staged = "/data/outbox/gif1.gif"
        coEvery { documentFiles.imageBounds(staged) } returns (320 to 240)
        store(sending("gif1", MessageType.GIF, localUri = staged).copy(mimeType = "image/gif"))
        coEvery { storageSource.uploadMedia(any(), any(), any(), any(), any()) } throws IOException("offline")

        runCatching { sender.send("gif1") }

        assertEquals(320, stored("gif1").mediaWidth)
        assertNull(stored("gif1").mediaUrl)

        coEvery { storageSource.uploadMedia(any(), any(), any(), any(), any()) } returns "https://storage.example/gif1"
        sender.send("gif1")

        coVerify(exactly = 1) { documentFiles.imageBounds(any()) }
        assertEquals("https://storage.example/gif1", writes.single().mediaUrl)
    }

    @Test
    fun `a GIF whose bounds cannot be read is still sent`() = runTest {
        store(sending("gif1", MessageType.GIF, localUri = "/data/outbox/gif1.gif").copy(mimeType = "image/gif"))

        sender.send("gif1")

        assertNull(stored("gif1").mediaWidth)
        assertEquals(listOf(Upload("gif1", "image/gif", reportsProgress = true)), uploads)
    }

    // ── picks from Klipy ────────────────────────────────────────────────────

    // A pick points at Klipy: the row has the url and no file. Storage, the
    // sticker object and the documents dir are never asked. `stickerFiles` and
    // `storageSource` are strict mocks, so a call would fail the test by itself.
    @Test
    fun `a GIF picked from Klipy is written with Klipy's url and uploads, reads and keeps nothing`() = runTest {
        val url = "https://static.klipy.com/ii/abc/cat.webp"
        store(
            sending("gif1", MessageType.GIF).copy(mediaUrl = url, mimeType = "image/webp", mediaWidth = 320, mediaHeight = 240)
        )

        sender.send("gif1")

        assertTrue(uploads.isEmpty())
        coVerify(exactly = 0) { documentFiles.imageBounds(any()) }
        coVerify(exactly = 0) { documentFiles.adopt(any(), any(), any(), any()) }
        assertEquals(url, writes.single().mediaUrl)
        assertEquals(MessageType.GIF, writes.single().type)
        val sent = stored("gif1")
        assertEquals(MessageStatus.SENT, sent.status)
        assertNull(sent.localUri)
    }

    @Test
    fun `a sticker picked from Klipy names no library sticker and is written with Klipy's url`() = runTest {
        val url = "https://static2.klipy.com/ii/abc/wave.webp"
        store(sending("st1", MessageType.STICKER).copy(mediaUrl = url, mimeType = "image/webp"))

        sender.send("st1")

        assertTrue(stickerUploads.isEmpty())
        assertTrue(uploads.isEmpty())
        coVerify(exactly = 0) { stickerDao.getSticker(any()) }
        assertEquals(url, writes.single().mediaUrl)
        assertNull(stored("st1").localUri)
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
        // A staged recording is not a file the app keeps; the player streams mediaUrl.
        assertNull(stored("voice1").localUri)
    }

    // ── a message deleted while queued ──────────────────────────────────────

    private fun deleted(attempts: Int) = MessageEntity.outbox(sending("msg1", MessageType.TEXT), SendTarget.Peer("peer1"))
        .copy(record = MessageRecord.fromDomain(sending("msg1", MessageType.TEXT).copy(deletedAt = 5_000L, content = "")), outboxAttempts = attempts)

    @Test
    fun `a deleted row an earlier attempt may have written asks the backend for its tombstone`() = runTest {
        rows["msg1"] = deleted(attempts = 1)

        val result = sender.send("msg1")

        coVerify(exactly = 1) { messageSource.deleteIfExists("chat1", "msg1", 5_000L) }
        assertTrue("nothing is written that could create the document", writes.isEmpty())
        coVerify(exactly = 0) { messageDao.incrementOutboxAttempts(any()) }
        assertEquals(MessageStatus.SENT, result.status)
        assertEquals(MessageStatus.SENT, stored("msg1").status)
        assertNull("out of the outbox", rows.getValue("msg1").outboxRecipientId)
        coVerify(exactly = 1) { outboxFiles.delete("msg1") }
        // A document an earlier run moved out of the outbox goes too, by this id.
        coVerify(exactly = 1) { documentFiles.discard("msg1") }
    }

    @Test
    fun `a deleted row no attempt ever started has nothing pending and asks the backend nothing`() = runTest {
        rows["msg1"] = deleted(attempts = 0)

        sender.send("msg1")

        coVerify(exactly = 0) { messageSource.deleteIfExists(any(), any(), any()) }
        assertTrue(writes.isEmpty())
        assertEquals(MessageStatus.SENT, stored("msg1").status)
    }

    @Test
    fun `a tombstone the backend did not acknowledge leaves the row queued for another run`() = runTest {
        rows["msg1"] = deleted(attempts = 1)
        coEvery { messageSource.deleteIfExists(any(), any(), any()) } throws IOException("not acknowledged")

        assertTrue(runCatching { sender.send("msg1") }.exceptionOrNull() is IOException)

        assertEquals(MessageStatus.SENDING, stored("msg1").status)
        coVerify(exactly = 0) { messageDao.acknowledge(any(), any()) }
    }
}

/** A well-formed sticker id: 64 lowercase hex digits. */
private val STICKER_ID = "a".repeat(64)
