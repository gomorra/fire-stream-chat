package com.firestream.chat.data.repository

import com.firestream.chat.data.crypto.MessageEnvelope
import com.firestream.chat.data.crypto.SignalManager
import com.firestream.chat.data.local.dao.MessageDao
import com.firestream.chat.data.local.entity.MessageEntity
import com.firestream.chat.data.local.entity.MessageRecord
import com.firestream.chat.data.remote.source.AuthSource
import com.firestream.chat.data.remote.source.MessageSource
import com.firestream.chat.data.remote.source.RawMessage
import com.firestream.chat.domain.model.Message
import com.firestream.chat.domain.model.MessageStatus
import com.firestream.chat.domain.model.MessageType
import com.firestream.chat.domain.model.PushReconcileResult
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Test
import org.signal.libsignal.protocol.message.CiphertextMessage
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import kotlin.coroutines.coroutineContext

/**
 * Verifies the unified locked receive path in [MessageRepositoryImpl]:
 * - A sync cancelled mid-decrypt finishes writing the plaintext to Room.
 * - Concurrent arrivals from listener, chat-list sync, and push reconcile for
 *   the same encrypted message decrypt it exactly once and never race on the
 *   ratchet.
 * - A decrypt failure never overwrites an already-decrypted row with the error
 *   placeholder.
 */
class MessageRepositorySyncDecryptTest {

    private val messageDao = mockk<MessageDao>(relaxed = true)
    private val messageSource = mockk<MessageSource>(relaxed = true)
    private val authSource = mockk<AuthSource>()
    private val signalManager = mockk<SignalManager>(relaxed = true)

    private val incoming = RawMessage(
        id = "in1",
        chatId = "chat1",
        senderId = "peer1",
        content = null,
        ciphertext = "cipher-1",
        signalType = CiphertextMessage.WHISPER_TYPE,
        type = "TEXT",
        mediaUrl = null,
        mediaThumbnailUrl = null,
        status = "SENT",
        replyToId = null,
        timestamp = 1_000L,
        editedAt = null,
    )

    @Test
    fun `a sync cancelled during a decrypt still writes the plaintext to Room`() = runTest {
        every { authSource.currentUserId } returns "uid1"
        coEvery { messageSource.fetchMessages("chat1") } returns listOf(incoming)
        coEvery { messageDao.getMessageById("in1") } returns null
        val inserted = mutableListOf<MessageRecord>()
        // Room's suspend calls run under the caller's job and refuse to start
        // once it is cancelled; the mock checks the same thing.
        coEvery { messageDao.upsertRecord(any()) } coAnswers {
            coroutineContext.ensureActive()
            inserted += firstArg<MessageRecord>()
        }
        lateinit var sync: Job
        coEvery { signalManager.decrypt("peer1", any()) } coAnswers {
            sync.cancel()  // the user logs out, say, the instant the ratchet advanced
            "hello"
        }
        val repository = messageRepository(
            messageDao = messageDao,
            messageSource = messageSource,
            authSource = authSource,
            signalManager = signalManager,
        )

        sync = launch { repository.syncAllChatMessages(listOf("chat1")) }
        sync.join()

        assertEquals(listOf("hello"), inserted.map { it.content })
    }

    @Test
    fun `concurrent listener, sync, and push arrivals decrypt an encrypted message exactly once`() = runTest {
        every { authSource.currentUserId } returns "uid1"
        val store = ConcurrentHashMap<String, MessageEntity>()
        val roomRows = MutableStateFlow<List<MessageEntity>>(emptyList())
        coEvery { messageDao.getMessageById(any()) } answers { store[firstArg()] }
        coEvery { messageDao.upsertRecord(any()) } answers {
            val rec = firstArg<MessageRecord>()
            store[rec.id] = MessageEntity(record = rec)
            roomRows.value = store.values.toList()
        }
        coEvery { messageDao.updateRecord(any()) } answers {
            val rec = firstArg<MessageRecord>()
            store[rec.id] = MessageEntity(record = rec)
            roomRows.value = store.values.toList()
        }
        every { messageDao.getMessagesByChatId("chat1") } returns roomRows
        every { messageSource.observeMessages("chat1") } returns flowOf(listOf(incoming))
        coEvery { messageSource.fetchMessages("chat1") } returns listOf(incoming)
        coEvery { messageSource.fetchMessage("chat1", "in1") } returns incoming

        val decryptEntered = CompletableDeferred<Unit>()
        val releaseDecrypt = CompletableDeferred<Unit>()
        val decryptCalls = AtomicInteger(0)
        coEvery { signalManager.decrypt("peer1", any()) } coAnswers {
            val callNum = decryptCalls.incrementAndGet()
            decryptEntered.complete(Unit)
            releaseDecrypt.await()
            if (callNum == 1) "secret hello" else throw IllegalStateException("ratchet already advanced")
        }

        val repository = messageRepository(
            messageDao = messageDao,
            messageSource = messageSource,
            authSource = authSource,
            signalManager = signalManager,
        )

        val syncJob = async(Dispatchers.IO) { repository.syncAllChatMessages(listOf("chat1")) }
        decryptEntered.await()

        // While sync holds the per-message lock mid-decrypt, push and open-chat listener arrive.
        // The listener stays active until Room actually emits the decrypted row.
        val pushJob = async(Dispatchers.IO) { repository.reconcileFromPush("chat1", "in1") }
        val listenerJob = async(Dispatchers.IO) { repository.getMessages("chat1").first { it.isNotEmpty() } }

        // Wait deterministically until all 3 callers (sync, push, listener) are holding/waiting on receiveLocks["in1"].
        withContext(Dispatchers.IO) {
            withTimeout(2_000L) {
                while (repository.receiveLocks.userCount("in1") < 3) {
                    delay(5)
                }
            }
        }
        releaseDecrypt.complete(Unit)

        syncJob.await()
        val pushResult = pushJob.await()
        val listenerMessages = listenerJob.await()

        assertEquals(1, decryptCalls.get())
        assertEquals("secret hello", store["in1"]?.content)
        assertEquals("secret hello", (pushResult as PushReconcileResult.Reconciled).message.content)
        assertEquals(listOf("secret hello"), listenerMessages.map { it.content })
    }

    @Test
    fun `decrypt failure never overwrites an already-decrypted row with the error placeholder`() = runTest {
        every { authSource.currentUserId } returns "uid1"
        val existingRow = MessageEntity.fromDomain(
            Message(
                id = "in1",
                chatId = "chat1",
                senderId = "peer1",
                content = "original decrypted text",
                type = MessageType.TEXT,
                status = MessageStatus.SENT,
                timestamp = 1_000L,
            )
        )
        coEvery { messageDao.getMessageById("in1") } returns existingRow
        val updated = mutableListOf<MessageRecord>()
        coEvery { messageDao.updateRecord(any()) } answers { updated += firstArg<MessageRecord>() }
        coEvery { messageSource.fetchMessages("chat1") } returns listOf(
            incoming.copy(editedAt = 2_000L, content = null)
        )
        coEvery { signalManager.decrypt("peer1", any()) } throws IllegalStateException("duplicate message")

        val repository = messageRepository(
            messageDao = messageDao,
            messageSource = messageSource,
            authSource = authSource,
            signalManager = signalManager,
        )

        repository.syncAllChatMessages(listOf("chat1"))

        assertEquals(1, updated.size)
        assertEquals("original decrypted text", updated.single().content)
        coVerify(exactly = 1) { signalManager.decrypt("peer1", any()) }
    }

    @Test
    fun `reconcileRawMessage decodes v1 MessageEnvelope metadata and media keys into Room`() = runTest {
        every { authSource.currentUserId } returns "uid1"
        val envelopeJson = MessageEnvelope.fromMessage(
            Message(
                id = "vid1",
                chatId = "chat1",
                senderId = "peer1",
                content = "clip caption",
                type = MessageType.VIDEO,
                replyToId = "reply-0",
                mentions = listOf("uid1"),
                emojiSizes = mapOf(0 to 1.5f),
                mediaUrl = "https://storage.example/media/chat1/vid1.enc",
                mediaKey = "mKey",
                mediaIv = "mIv",
                mediaSha256 = "mSha",
                mediaWidth = 1280,
                mediaHeight = 720,
                duration = 15,
                mediaThumbnailUrl = "https://storage.example/media/chat1/vid1_thumb.enc",
                thumbKey = "tKey",
                thumbIv = "tIv",
                thumbSha256 = "tSha",
                fileName = "clip.mp4",
                fileSize = 4096L,
                mimeType = "video/mp4",
            )
        ).encode()

        val rawFromFirestore = incoming.copy(
            id = "vid1",
            type = "VIDEO",
            // All plaintext metadata fields omitted on Firestore
            mediaUrl = null,
            mediaThumbnailUrl = null,
            replyToId = null,
            mentions = emptyList(),
            emojiSizes = emptyMap(),
        )
        coEvery { messageSource.fetchMessages("chat1") } returns listOf(rawFromFirestore)
        coEvery { messageDao.getMessageById("vid1") } returns null
        val inserted = mutableListOf<MessageRecord>()
        coEvery { messageDao.upsertRecord(any()) } answers { inserted += firstArg<MessageRecord>() }
        coEvery { signalManager.decrypt("peer1", any()) } returns envelopeJson

        val repository = messageRepository(
            messageDao = messageDao,
            messageSource = messageSource,
            authSource = authSource,
            signalManager = signalManager,
        )

        repository.syncAllChatMessages(listOf("chat1"))

        val record = inserted.single()
        assertEquals("clip caption", record.content)
        assertEquals("reply-0", record.replyToId)
        assertEquals(listOf("uid1"), record.mentions)
        assertEquals(1.5f, record.emojiSizes.getValue(0), 0.001f)
        assertEquals("https://storage.example/media/chat1/vid1.enc", record.mediaUrl)
        assertEquals("mKey", record.mediaKey)
        assertEquals("mIv", record.mediaIv)
        assertEquals("mSha", record.mediaSha256)
        assertEquals("https://storage.example/media/chat1/vid1_thumb.enc", record.mediaThumbnailUrl)
        assertEquals("tKey", record.thumbKey)
        assertEquals("tIv", record.thumbIv)
        assertEquals("tSha", record.thumbSha256)
        assertEquals(1280, record.mediaWidth)
        assertEquals(720, record.mediaHeight)
        assertEquals(15, record.duration)
        assertEquals("clip.mp4", record.fileName)
        assertEquals(4096L, record.fileSize)
        assertEquals("video/mp4", record.mimeType)
    }
}
