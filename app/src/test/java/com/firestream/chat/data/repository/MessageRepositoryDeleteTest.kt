package com.firestream.chat.data.repository

import com.firestream.chat.data.local.dao.MessageDao
import com.firestream.chat.data.local.entity.MessageEntity
import com.firestream.chat.data.outbox.OutboxFiles
import com.firestream.chat.data.outbox.OutboxScheduler
import com.firestream.chat.data.outbox.SendTarget
import com.firestream.chat.data.remote.source.AuthSource
import com.firestream.chat.data.remote.source.MessageSource
import com.firestream.chat.domain.model.Message
import com.firestream.chat.domain.model.MessageStatus
import com.firestream.chat.domain.model.MessageType
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.coVerifyOrder
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.IOException

/**
 * Deleting a message the backend has not acknowledged — queued, in flight or
 * given up on — takes the outbox's tombstone path (offline outbox plan §2.5):
 * the row stays, soft-deleted, its staged input goes, and a REPLACE run asks the
 * backend to tombstone the document only if it exists. Anything else is deleted
 * on the backend first, as before.
 */
class MessageRepositoryDeleteTest {

    private val messageDao = mockk<MessageDao>(relaxed = true)
    private val messageSource = mockk<MessageSource>(relaxed = true)
    private val authSource = mockk<AuthSource>()
    private val outboxScheduler = mockk<OutboxScheduler>(relaxed = true)
    private val outboxFiles = mockk<OutboxFiles>(relaxed = true)

    private lateinit var repository: MessageRepositoryImpl

    @Before
    fun setUp() {
        every { authSource.currentUserId } returns "uid1"
        repository = messageRepository(
            messageDao = messageDao,
            messageSource = messageSource,
            authSource = authSource,
            outboxScheduler = outboxScheduler,
            outboxFiles = outboxFiles,
        )
    }

    private fun row(status: MessageStatus, senderId: String = "uid1") = MessageEntity.outbox(
        Message(
            id = "msg1",
            chatId = "chat1",
            senderId = senderId,
            content = "hi",
            type = MessageType.IMAGE,
            status = status,
            timestamp = 1_000L,
            localUri = "/data/outbox/msg1.jpg",
        ),
        SendTarget.Peer("peer1"),
    )

    @Test
    fun `a queued own message is soft-deleted locally and its tombstone handed to the worker`() = runTest {
        coEvery { messageDao.getMessageById("msg1") } returns row(MessageStatus.SENDING)

        val result = repository.deleteMessage("chat1", "msg1")

        assertTrue(result.isSuccess)
        coVerifyOrder {
            messageDao.softDeleteMessage("msg1", any())
            outboxFiles.delete("msg1")
            outboxScheduler.retryNow("msg1", uploads = false)
        }
        // Never a direct backend delete: the document may not exist yet, and a
        // persisted first attempt could still create it after this call.
        coVerify(exactly = 0) { messageSource.deleteMessage(any(), any()) }
    }

    // A given-up row is a tombstone job by OutboxJob's rule (deleted and FAILED),
    // so nothing has to flip its status for the worker to pick it up.
    @Test
    fun `a failed own message takes the tombstone path too, with its status left alone`() = runTest {
        coEvery { messageDao.getMessageById("msg1") } returns row(MessageStatus.FAILED)

        repository.deleteMessage("chat1", "msg1")

        coVerify(exactly = 1) { messageDao.softDeleteMessage("msg1", any()) }
        coVerify(exactly = 0) { messageDao.updateMessageStatus(any(), any()) }
        verify(exactly = 1) { outboxScheduler.retryNow("msg1", uploads = false) }
        coVerify(exactly = 0) { messageSource.deleteMessage(any(), any()) }
    }

    @Test
    fun `an acknowledged message is deleted on the backend first, then locally`() = runTest {
        coEvery { messageDao.getMessageById("msg1") } returns row(MessageStatus.SENT)

        repository.deleteMessage("chat1", "msg1")

        coVerifyOrder {
            messageSource.deleteMessage("chat1", "msg1")
            messageDao.softDeleteMessage("msg1", any())
        }
        verify(exactly = 0) { outboxScheduler.retryNow(any(), any()) }
        coVerify(exactly = 0) { outboxFiles.delete(any()) }
    }

    @Test
    fun `someone else's message never touches the outbox`() = runTest {
        coEvery { messageDao.getMessageById("msg1") } returns row(MessageStatus.SENDING, senderId = "peer1")

        repository.deleteMessage("chat1", "msg1")

        coVerify(exactly = 1) { messageSource.deleteMessage("chat1", "msg1") }
        verify(exactly = 0) { outboxScheduler.retryNow(any(), any()) }
    }

    @Test
    fun `a backend refusal leaves the local row as it was`() = runTest {
        coEvery { messageDao.getMessageById("msg1") } returns row(MessageStatus.SENT)
        coEvery { messageSource.deleteMessage("chat1", "msg1") } throws IOException("offline")

        val result = repository.deleteMessage("chat1", "msg1")

        assertTrue(result.isFailure)
        coVerify(exactly = 0) { messageDao.softDeleteMessage(any(), any()) }
    }
}
