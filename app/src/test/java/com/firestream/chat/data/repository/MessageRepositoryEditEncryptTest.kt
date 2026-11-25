package com.firestream.chat.data.repository

import com.firestream.chat.data.crypto.EncryptedMessage
import com.firestream.chat.data.crypto.MessageEnvelope
import com.firestream.chat.data.crypto.SignalManager
import com.firestream.chat.data.local.PreferencesDataStore
import com.firestream.chat.data.local.dao.MessageDao
import com.firestream.chat.data.local.entity.MessageEntity
import com.firestream.chat.data.local.entity.MessageRecord
import com.firestream.chat.data.outbox.MessageWriter
import com.firestream.chat.data.remote.source.AuthSource
import com.firestream.chat.data.remote.source.MessageSource
import com.firestream.chat.data.remote.source.RawMessage
import com.firestream.chat.domain.model.ChatType
import com.firestream.chat.domain.model.Message
import com.firestream.chat.domain.model.MessageStatus
import com.firestream.chat.domain.model.MessageType
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.signal.libsignal.protocol.message.CiphertextMessage

class MessageRepositoryEditEncryptTest {

    private val messageDao = mockk<MessageDao>(relaxed = true)
    private val messageSource = mockk<MessageSource>(relaxed = true)
    private val authSource = mockk<AuthSource>()
    private val signalManager = mockk<SignalManager>(relaxed = true)
    private val preferencesDataStore = mockk<PreferencesDataStore>()

    private fun repo(buildEncrypts: Boolean = true): MessageRepositoryImpl {
        every { authSource.currentUserId } returns "uid1"
        val writer = MessageWriter(messageSource, signalManager, buildEncrypts)
        return messageRepository(
            messageDao = messageDao,
            chats = listOf(
                testChat("dm1", ChatType.INDIVIDUAL.name, listOf("uid1", "peer1")),
                testChat("grp1", ChatType.GROUP.name, listOf("uid1", "peer1", "peer2")),
            ),
            messageSource = messageSource,
            authSource = authSource,
            signalManager = signalManager,
            messageWriter = writer,
            preferencesDataStore = preferencesDataStore,
        )
    }

    @Test
    fun `1-to-1 edit encrypts an EDIT envelope and calls editEncryptedMessage`() = runTest {
        val capturedJson = slot<String>()
        coEvery { signalManager.encrypt("peer1", capture(capturedJson)) } returns
            EncryptedMessage("edit-cipher-1", CiphertextMessage.WHISPER_TYPE)

        val result = repo(buildEncrypts = true).editMessage("dm1", "m1", "updated body", mapOf(0 to 1.5f))

        assertTrue(result.isSuccess)
        val env = MessageEnvelope.decode(capturedJson.captured)
        assertEquals(MessageEnvelope.Kind.EDIT, env.kind)
        assertEquals("updated body", env.body)
        assertEquals(1.5f, env.emojiSizes.getValue(0), 0.001f)
        coVerify(exactly = 1) {
            messageSource.editEncryptedMessage("dm1", "m1", "edit-cipher-1", CiphertextMessage.WHISPER_TYPE, any())
        }
        coVerify(exactly = 0) { messageSource.editMessage(any(), any(), any(), any(), any()) }
        coVerify(exactly = 1) { messageDao.editMessage("m1", "updated body", any(), mapOf(0 to 1.5f)) }
    }

    @Test
    fun `group edit writes plaintext editMessage`() = runTest {
        val result = repo(buildEncrypts = true).editMessage("grp1", "m1", "group edit", emptyMap())

        assertTrue(result.isSuccess)
        coVerify(exactly = 1) { messageSource.editMessage("grp1", "m1", "group edit", any(), emptyMap()) }
        coVerify(exactly = 0) { messageSource.editEncryptedMessage(any(), any(), any(), any(), any()) }
    }

    @Test
    fun `receiving encrypted edit on existing row decrypts only editCiphertext and preserves original metadata`() = runTest {
        val existing = MessageEntity.fromDomain(
            Message(
                id = "m1",
                chatId = "dm1",
                senderId = "peer1",
                content = "initial caption",
                type = MessageType.IMAGE,
                status = MessageStatus.SENT,
                timestamp = 1_000L,
                replyToId = "reply-0",
                mediaUrl = "https://storage.example/media/dm1/m1.enc",
                mediaKey = "k1",
                mediaIv = "iv1",
                mediaSha256 = "sha1",
            )
        )
        coEvery { messageDao.getMessageById("m1") } returns existing
        val updated = mutableListOf<MessageRecord>()
        coEvery { messageDao.updateRecord(any()) } answers { updated += firstArg<MessageRecord>() }

        val editPayload = EncryptedMessage("edit-c1", CiphertextMessage.WHISPER_TYPE)
        coEvery { signalManager.decrypt("peer1", editPayload) } returns
            MessageEnvelope.forEdit("edited caption", mapOf(1 to 2.0f)).encode()

        val raw = RawMessage(
            id = "m1",
            chatId = "dm1",
            senderId = "peer1",
            content = null,
            ciphertext = "orig-c0",
            signalType = CiphertextMessage.WHISPER_TYPE,
            editCiphertext = "edit-c1",
            editSignalType = CiphertextMessage.WHISPER_TYPE,
            type = "IMAGE",
            mediaUrl = null,
            mediaThumbnailUrl = null,
            status = "SENT",
            replyToId = null,
            timestamp = 1_000L,
            editedAt = 2_000L,
        )
        coEvery { messageSource.fetchMessages("dm1") } returns listOf(raw)

        repo().syncAllChatMessages(listOf("dm1"))

        val record = updated.single()
        assertEquals("edited caption", record.content)
        assertEquals(2_000L, record.editedAt)
        assertEquals(2.0f, record.emojiSizes.getValue(1), 0.001f)
        assertEquals("reply-0", record.replyToId)
        assertEquals("k1", record.mediaKey)
        assertEquals("https://storage.example/media/dm1/m1.enc", record.mediaUrl)
        // Original ciphertext was never re-decrypted.
        coVerify(exactly = 0) {
            signalManager.decrypt("peer1", EncryptedMessage("orig-c0", CiphertextMessage.WHISPER_TYPE))
        }
        coVerify(exactly = 1) { signalManager.decrypt("peer1", editPayload) }
    }

    @Test
    fun `receiving encrypted edit before original sync decrypts original then edit`() = runTest {
        coEvery { messageDao.getMessageById("m2") } returns null
        val inserted = mutableListOf<MessageRecord>()
        coEvery { messageDao.upsertRecord(any()) } answers { inserted += firstArg<MessageRecord>() }

        val origMsg = Message(
            id = "m2",
            chatId = "dm1",
            senderId = "peer1",
            content = "first",
            type = MessageType.IMAGE,
            replyToId = "orig-reply",
            mediaUrl = "https://storage.example/media/dm1/m2.enc",
            mediaKey = "mk2",
            mediaIv = "miv2",
            mediaSha256 = "msha2",
        )
        coEvery {
            signalManager.decrypt("peer1", EncryptedMessage("orig-c2", CiphertextMessage.WHISPER_TYPE))
        } returns MessageEnvelope.fromMessage(origMsg).encode()
        coEvery {
            signalManager.decrypt("peer1", EncryptedMessage("edit-c2", CiphertextMessage.WHISPER_TYPE))
        } returns MessageEnvelope.forEdit("second", mapOf(0 to 1.8f)).encode()

        val raw = RawMessage(
            id = "m2",
            chatId = "dm1",
            senderId = "peer1",
            content = null,
            ciphertext = "orig-c2",
            signalType = CiphertextMessage.WHISPER_TYPE,
            editCiphertext = "edit-c2",
            editSignalType = CiphertextMessage.WHISPER_TYPE,
            type = "IMAGE",
            mediaUrl = null,
            mediaThumbnailUrl = null,
            status = "SENT",
            replyToId = null,
            timestamp = 1_000L,
            editedAt = 3_000L,
        )
        coEvery { messageSource.fetchMessages("dm1") } returns listOf(raw)

        repo().syncAllChatMessages(listOf("dm1"))

        val record = inserted.single()
        assertEquals("second", record.content)
        assertEquals(3_000L, record.editedAt)
        assertEquals("orig-reply", record.replyToId)
        assertEquals("mk2", record.mediaKey)
        assertEquals("https://storage.example/media/dm1/m2.enc", record.mediaUrl)
    }

    @Test
    fun `sequential encrypted edits each apply cleanly without re-decrypting original`() = runTest {
        var stored: MessageEntity? = MessageEntity.fromDomain(
            Message(
                id = "m3",
                chatId = "dm1",
                senderId = "peer1",
                content = "v0",
                type = MessageType.TEXT,
                status = MessageStatus.SENT,
                timestamp = 1_000L,
            )
        )
        coEvery { messageDao.getMessageById("m3") } answers { stored }
        coEvery { messageDao.updateRecord(any()) } answers {
            stored = MessageEntity(record = firstArg())
        }
        coEvery {
            signalManager.decrypt("peer1", EncryptedMessage("e1", CiphertextMessage.WHISPER_TYPE))
        } returns MessageEnvelope.forEdit("v1").encode()
        coEvery {
            signalManager.decrypt("peer1", EncryptedMessage("e2", CiphertextMessage.WHISPER_TYPE))
        } returns MessageEnvelope.forEdit("v2").encode()

        val baseRaw = RawMessage(
            id = "m3", chatId = "dm1", senderId = "peer1",
            content = null, ciphertext = "c0", signalType = CiphertextMessage.WHISPER_TYPE,
            type = "TEXT", mediaUrl = null, mediaThumbnailUrl = null,
            status = "SENT", replyToId = null, timestamp = 1_000L, editedAt = null,
        )
        val r = repo()

        coEvery { messageSource.fetchMessage("dm1", "m3") } returns
            baseRaw.copy(editCiphertext = "e1", editSignalType = CiphertextMessage.WHISPER_TYPE, editedAt = 2_000L)
        r.reconcileFromPush("dm1", "m3")
        assertEquals("v1", stored?.content)
        assertEquals(2_000L, stored?.editedAt)

        coEvery { messageSource.fetchMessage("dm1", "m3") } returns
            baseRaw.copy(editCiphertext = "e2", editSignalType = CiphertextMessage.WHISPER_TYPE, editedAt = 3_000L)
        r.reconcileFromPush("dm1", "m3")
        assertEquals("v2", stored?.content)
        assertEquals(3_000L, stored?.editedAt)
    }

    @Test
    fun `when existing is null and edit decrypt fails after original succeeds, original content is preserved and edit remains retryable`() = runTest {
        var stored: MessageEntity? = null
        coEvery { messageDao.getMessageById("m4") } answers { stored }
        coEvery { messageDao.upsertRecord(any()) } answers {
            stored = MessageEntity(record = firstArg())
        }
        coEvery { messageDao.updateRecord(any()) } answers {
            stored = MessageEntity(record = firstArg())
        }
        coEvery {
            signalManager.decrypt("peer1", EncryptedMessage("orig-c4", CiphertextMessage.WHISPER_TYPE))
        } returns MessageEnvelope(body = "original decrypted body").encode()
        coEvery {
            signalManager.decrypt("peer1", EncryptedMessage("edit-c4", CiphertextMessage.WHISPER_TYPE))
        } throws IllegalStateException("transient error") andThen MessageEnvelope.forEdit("recovered edit").encode()

        val raw = RawMessage(
            id = "m4", chatId = "dm1", senderId = "peer1",
            content = null, ciphertext = "orig-c4", signalType = CiphertextMessage.WHISPER_TYPE,
            editCiphertext = "edit-c4", editSignalType = CiphertextMessage.WHISPER_TYPE,
            type = "TEXT", mediaUrl = null, mediaThumbnailUrl = null,
            status = "SENT", replyToId = null, timestamp = 1_000L, editedAt = 5_000L,
        )
        coEvery { messageSource.fetchMessage("dm1", "m4") } returns raw

        val r = repo()
        r.reconcileFromPush("dm1", "m4")
        assertEquals("original decrypted body", stored?.content)
        assertEquals(null, stored?.editedAt)

        // Retry reconciles editCiphertext without re-decrypting orig-c4.
        r.reconcileFromPush("dm1", "m4")
        assertEquals("recovered edit", stored?.content)
        assertEquals(5_000L, stored?.editedAt)
        coVerify(exactly = 1) {
            signalManager.decrypt("peer1", EncryptedMessage("orig-c4", CiphertextMessage.WHISPER_TYPE))
        }
    }
}
