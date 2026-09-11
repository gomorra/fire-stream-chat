package com.firestream.chat.data.repository

import com.firestream.chat.data.local.dao.MessageDao
import com.firestream.chat.data.local.entity.MessageEntity
import com.firestream.chat.data.outbox.OutboxSender
import com.firestream.chat.data.remote.source.AuthSource
import com.firestream.chat.data.remote.source.UserSource
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
 * The repository's half of a send and a retry: the guards, the recipient
 * recorded on the optimistic row, the FAILED → SENDING flip, the hand-off to
 * [OutboxSender] by id, and the revert when it fails. What a re-attempt then
 * does — read its attempt count off the row, resume past finished steps, write
 * if-absent, update the newer-only preview — is covered in `OutboxSenderTest`.
 */
class MessageRepositoryRetryTest {

    private val messageDao = mockk<MessageDao>(relaxed = true)
    private val authSource = mockk<AuthSource>()
    private val outboxSender = mockk<OutboxSender>(relaxed = true)
    private val chatRepository = mockk<dagger.Lazy<ChatRepository>>()
    private val listRepository = mockk<dagger.Lazy<ListRepository>>()
    private val userSource = mockk<UserSource>(relaxed = true)

    private val statusUpdates = mutableListOf<Pair<String, String>>()

    private lateinit var repository: MessageRepositoryImpl

    @Before
    fun setUp() {
        every { authSource.currentUserId } returns "uid1"
        coEvery { messageDao.updateMessageStatus(any(), any()) } answers {
            statusUpdates += (firstArg<String>() to secondArg())
        }

        repository = messageRepository(
            messageDao = messageDao,
            authSource = authSource,
            outboxSender = outboxSender,
            chatRepository = chatRepository,
            listRepository = listRepository,
            userSource = userSource,
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
    fun `retry flips the row to SENDING, then hands the same row back to the outbox sender`() = runTest {
        val original = storedTextMessage()
        stubStored(original)
        val sent = original.copy(status = MessageStatus.SENT)
        coEvery { outboxSender.send(original.id, null) } returns sent

        val result = repository.retryFailedMessage(original.id, recipientId = "recipient1")

        assertEquals(sent, result.getOrThrow())
        coVerifyOrder {
            messageDao.updateMessageStatus(original.id, MessageStatus.SENDING.name)
            outboxSender.send(original.id)
        }
    }

    // The row records its peer at insert and OutboxSender encrypts for that peer,
    // so the block check must ask about the same one, not whatever the screen passes.
    @Test
    fun `retry checks the block list against the recipient recorded on the row`() = runTest {
        val original = storedTextMessage()
        coEvery { messageDao.getMessageById(original.id) } returns
            MessageEntity.outbox(original, recipientId = "peer-on-row")

        repository.retryFailedMessage(original.id, recipientId = "peer-from-screen")

        coVerify(exactly = 1) { userSource.isUserBlocked("uid1", "peer-on-row") }
        coVerify(exactly = 0) { userSource.isUserBlocked(any(), "peer-from-screen") }
    }

    @Test
    fun `retry that fails again reverts row to FAILED`() = runTest {
        val original = storedTextMessage()
        stubStored(original)
        coEvery { outboxSender.send(any(), any()) } throws RuntimeException("still offline")

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
        coVerify(exactly = 0) { outboxSender.send(any(), any()) }
    }

    @Test
    fun `retry of unknown message id returns failure`() = runTest {
        coEvery { messageDao.getMessageById("ghost") } returns null

        val result = repository.retryFailedMessage("ghost", recipientId = "")

        assertTrue(result.isFailure)
        assertTrue(statusUpdates.isEmpty())
        coVerify(exactly = 0) { outboxSender.send(any(), any()) }
    }

    @Test
    fun `first send records the recipient on a fresh optimistic row and hands it to the outbox sender`() = runTest {
        val inserted = slot<MessageEntity>()
        coEvery { messageDao.insertOutbox(capture(inserted)) } just Runs

        val result = repository.sendMessage("chat1", "hi", recipientId = "recipient1")

        assertTrue("send should succeed: ${result.exceptionOrNull()}", result.isSuccess)
        assertEquals(MessageStatus.SENDING.name, inserted.captured.status)
        assertEquals("recipient1", inserted.captured.outboxRecipientId)
        assertEquals(0, inserted.captured.outboxAttempts)
        coVerify(exactly = 1) { outboxSender.send(inserted.captured.id) }
    }
}
