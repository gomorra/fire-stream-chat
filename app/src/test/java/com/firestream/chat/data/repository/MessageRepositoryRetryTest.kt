package com.firestream.chat.data.repository

import android.net.ConnectivityManager
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
import com.firestream.chat.data.util.VideoTranscoder
import com.firestream.chat.domain.model.Message
import com.firestream.chat.domain.model.MessageStatus
import com.firestream.chat.domain.model.MessageType
import com.firestream.chat.domain.repository.ChatRepository
import com.firestream.chat.domain.repository.ListRepository
import io.mockk.Runs
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.coVerifyOrder
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.slot
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * The repository's half of a retry: the guards, the FAILED → SENDING flip, the
 * hand-off to [OutboxSender] as a re-attempt, and the revert when it fails.
 * What a re-attempt then does — resume past finished steps, write if-absent,
 * rebind the preview — is covered in `OutboxSenderTest`.
 */
class MessageRepositoryRetryTest {

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

    private val statusUpdates = mutableListOf<Pair<String, String>>()

    private lateinit var repository: MessageRepositoryImpl

    @Before
    fun setUp() {
        every { authSource.currentUserId } returns "uid1"
        coEvery { messageDao.updateMessageStatus(any(), any()) } answers {
            statusUpdates += (firstArg<String>() to secondArg())
        }

        repository = MessageRepositoryImpl(
            messageDao, chatDao, messageSource, authSource, signalManager, outboxSender, chatRepository,
            listRepository, mediaFileManager, videoTranscoder, preferencesDataStore, connectivityManager,
            userSource
        )
    }

    private fun storedTextMessage(status: MessageStatus = MessageStatus.FAILED): Message = Message(
        id = "failed-msg-1",
        chatId = "chat1",
        senderId = "uid1",
        content = "hi there",
        type = MessageType.TEXT,
        status = status,
        timestamp = 1_000L,
    )

    private fun stubStored(message: Message) {
        coEvery { messageDao.getMessageById(message.id) } returns MessageEntity.fromDomain(message)
    }

    @Test
    fun `retry flips the row to SENDING, then re-runs it through the outbox sender as a retry`() = runTest {
        val original = storedTextMessage()
        stubStored(original)
        val sent = original.copy(status = MessageStatus.SENT)
        coEvery { outboxSender.send(original.id, "recipient1", true, null) } returns sent

        val result = repository.retryFailedMessage(original.id, recipientId = "recipient1")

        assertEquals(sent, result.getOrThrow())
        coVerifyOrder {
            messageDao.updateMessageStatus(original.id, MessageStatus.SENDING.name)
            outboxSender.send(original.id, "recipient1", isRetry = true, sourceMimeType = null)
        }
    }

    @Test
    fun `retry that fails again reverts row to FAILED`() = runTest {
        val original = storedTextMessage()
        stubStored(original)
        coEvery { outboxSender.send(any(), any(), any(), any()) } throws RuntimeException("still offline")

        val result = repository.retryFailedMessage(original.id, recipientId = "")

        assertTrue(result.isFailure)
        assertEquals(
            listOf(original.id to MessageStatus.SENDING.name, original.id to MessageStatus.FAILED.name),
            statusUpdates,
        )
    }

    @Test
    fun `retry of non-FAILED message returns failure without IO`() = runTest {
        stubStored(storedTextMessage(status = MessageStatus.SENT))

        val result = repository.retryFailedMessage("failed-msg-1", recipientId = "")

        assertTrue(result.isFailure)
        assertTrue(statusUpdates.isEmpty())
        coVerify(exactly = 0) { outboxSender.send(any(), any(), any(), any()) }
    }

    @Test
    fun `retry of unknown message id returns failure`() = runTest {
        coEvery { messageDao.getMessageById("ghost") } returns null

        val result = repository.retryFailedMessage("ghost", recipientId = "")

        assertTrue(result.isFailure)
        assertTrue(statusUpdates.isEmpty())
        coVerify(exactly = 0) { outboxSender.send(any(), any(), any(), any()) }
    }

    @Test
    fun `first send hands the optimistic row to the outbox sender as a first attempt`() = runTest {
        val inserted = slot<MessageEntity>()
        coEvery { messageDao.insertMessage(capture(inserted)) } just Runs

        val result = repository.sendMessage("chat1", "hi", recipientId = "")

        assertTrue("send should succeed: ${result.exceptionOrNull()}", result.isSuccess)
        assertEquals(MessageStatus.SENDING.name, inserted.captured.status)
        coVerify(exactly = 1) {
            outboxSender.send(inserted.captured.id, "", isRetry = false, sourceMimeType = null)
        }
    }
}
