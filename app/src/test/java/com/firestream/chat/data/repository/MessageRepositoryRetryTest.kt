package com.firestream.chat.data.repository

import com.firestream.chat.data.local.dao.MessageDao
import com.firestream.chat.data.local.entity.MessageEntity
import com.firestream.chat.data.outbox.OutboxScheduler
import com.firestream.chat.data.outbox.SendTarget
import com.firestream.chat.data.remote.source.AuthSource
import com.firestream.chat.data.remote.source.UserSource
import com.firestream.chat.domain.model.Message
import com.firestream.chat.domain.model.MessageStatus
import com.firestream.chat.domain.model.MessageType
import io.mockk.Runs
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.coVerifyOrder
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * The repository's half of a send and a retry: the guards, the target recorded
 * on the optimistic row, the one-statement FAILED → SENDING flip with a fresh attempt budget,
 * and the hand-off to [OutboxScheduler]. What the worker then does with the row
 * — resume past finished steps, write if-absent, give up — is covered in
 * `OutboxWorkerTest` and `OutboxSenderTest`.
 */
class MessageRepositoryRetryTest {

    private val messageDao = mockk<MessageDao>(relaxed = true)
    private val authSource = mockk<AuthSource>()
    private val outboxScheduler = mockk<OutboxScheduler>(relaxed = true)
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
            outboxScheduler = outboxScheduler,
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

    private fun stubStored(entity: MessageEntity) {
        coEvery { messageDao.getMessageById(entity.id) } returns entity
    }

    @Test
    fun `retry flips the row to SENDING, restores its attempt budget and replaces its work`() = runTest {
        val original = MessageEntity.outbox(storedTextMessage(), SendTarget.NoPeer).copy(outboxAttempts = 8)
        stubStored(original)

        val result = repository.retryFailedMessage(original.id, recipientId = "")

        assertEquals(MessageStatus.SENDING, result.getOrThrow().status)
        coVerifyOrder {
            messageDao.requeueForRetry(original.id)
            outboxScheduler.retryNow(original.id, uploads = false)
        }
        coVerify(exactly = 0) { messageDao.updateMessageStatus(original.id, MessageStatus.SENDING.name) }
    }

    // The row records its target at insert and OutboxSender encrypts for it,
    // so the block check must ask about the same one, not whatever the screen passes.
    @Test
    fun `retry checks the block list against the target recorded on the row`() = runTest {
        stubStored(MessageEntity.outbox(storedTextMessage(), SendTarget.Peer("peer-on-row")))

        repository.retryFailedMessage("failed-msg-1", recipientId = "peer-from-screen")

        coVerify(exactly = 1) { userSource.isUserBlocked("uid1", "peer-on-row") }
        coVerify(exactly = 0) { userSource.isUserBlocked(any(), "peer-from-screen") }
    }

    @Test
    fun `retry of a row that recorded no target asks about the screen's recipient`() = runTest {
        stubStored(MessageEntity.fromDomain(storedTextMessage()))

        repository.retryFailedMessage("failed-msg-1", recipientId = "peer-from-screen")

        coVerify(exactly = 1) { userSource.isUserBlocked("uid1", "peer-from-screen") }
    }

    @Test
    fun `retry of a blocked peer reverts the row to FAILED without queuing it`() = runTest {
        stubStored(MessageEntity.outbox(storedTextMessage(), SendTarget.Peer("peer1")))
        coEvery { userSource.isUserBlocked("uid1", "peer1") } returns true

        val result = repository.retryFailedMessage("failed-msg-1", recipientId = "peer1")

        assertTrue(result.isFailure)
        coVerify(exactly = 1) { messageDao.requeueForRetry("failed-msg-1") }
        assertEquals(listOf("failed-msg-1" to MessageStatus.FAILED.name), statusUpdates)
        verify(exactly = 0) { outboxScheduler.retryNow(any(), any()) }
    }

    @Test
    fun `retry that cannot be queued reverts the row to FAILED`() = runTest {
        stubStored(MessageEntity.outbox(storedTextMessage(), SendTarget.NoPeer))
        every { outboxScheduler.retryNow(any(), any()) } throws IllegalStateException("WorkManager is not initialized")

        val result = repository.retryFailedMessage("failed-msg-1", recipientId = "")

        assertTrue(result.isFailure)
        coVerify(exactly = 1) { messageDao.requeueForRetry("failed-msg-1") }
        assertEquals(listOf("failed-msg-1" to MessageStatus.FAILED.name), statusUpdates)
    }

    @Test
    fun `retry of non-FAILED message returns failure without IO`() = runTest {
        stubStored(MessageEntity.fromDomain(storedTextMessage(status = MessageStatus.SENT)))

        val result = repository.retryFailedMessage("failed-msg-1", recipientId = "")

        assertTrue(result.isFailure)
        assertTrue(statusUpdates.isEmpty())
        coVerify(exactly = 0) { messageDao.requeueForRetry(any()) }
        verify(exactly = 0) { outboxScheduler.retryNow(any(), any()) }
    }

    @Test
    fun `retry of unknown message id returns failure`() = runTest {
        coEvery { messageDao.getMessageById("ghost") } returns null

        val result = repository.retryFailedMessage("ghost", recipientId = "")

        assertTrue(result.isFailure)
        assertTrue(statusUpdates.isEmpty())
        coVerify(exactly = 0) { messageDao.requeueForRetry(any()) }
        verify(exactly = 0) { outboxScheduler.retryNow(any(), any()) }
    }

    @Test
    fun `first send records the target on a fresh optimistic row and hands it to the scheduler`() = runTest {
        val inserted = slot<MessageEntity>()
        coEvery { messageDao.insertOutbox(capture(inserted)) } just Runs

        val result = repository.sendMessage("chat1", "hi", recipientId = "recipient1")

        assertTrue("send should succeed: ${result.exceptionOrNull()}", result.isSuccess)
        assertEquals(MessageStatus.SENDING, result.getOrThrow().status)
        assertEquals(MessageStatus.SENDING.name, inserted.captured.status)
        assertEquals("recipient1", inserted.captured.outboxRecipientId)
        assertEquals(0, inserted.captured.outboxAttempts)
        verify(exactly = 1) { outboxScheduler.enqueue(inserted.captured.id, uploads = false) }
        verify(exactly = 0) { outboxScheduler.retryNow(any(), any()) }
        assertTrue("the send returns before any attempt, so nothing marks the row", statusUpdates.isEmpty())
    }
}
