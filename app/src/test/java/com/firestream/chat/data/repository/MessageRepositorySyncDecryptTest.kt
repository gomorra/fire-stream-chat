package com.firestream.chat.data.repository

import com.firestream.chat.data.crypto.SignalManager
import com.firestream.chat.data.local.dao.MessageDao
import com.firestream.chat.data.local.entity.MessageEntity
import com.firestream.chat.data.remote.source.AuthSource
import com.firestream.chat.data.remote.source.MessageSource
import com.firestream.chat.data.remote.source.RawMessage
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.Job
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test
import org.signal.libsignal.protocol.message.CiphertextMessage
import kotlin.coroutines.coroutineContext

/**
 * The chat-list sync decrypts incoming messages like the chat listener does,
 * and a Signal decrypt is not repeatable: it advances the ratchet, so a
 * plaintext that is not written to Room is gone. The sync must therefore
 * finish decrypt-and-insert once it has started, even if the sync is cancelled
 * meanwhile (`withContext` discards the result of a block that ran while its
 * job was cancelled). [MessageRepositoryImpl.reconcileRawMessage] has had that
 * guard since the first decrypt bug; this pins it on the sync path too.
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
        val inserted = mutableListOf<MessageEntity>()
        // Room's suspend calls run under the caller's job and refuse to start
        // once it is cancelled; the mock checks the same thing.
        coEvery { messageDao.insertMessage(any()) } coAnswers {
            coroutineContext.ensureActive()
            inserted += firstArg<MessageEntity>()
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
}
