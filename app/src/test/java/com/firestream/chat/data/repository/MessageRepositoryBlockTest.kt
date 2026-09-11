package com.firestream.chat.data.repository

import com.firestream.chat.data.local.dao.MessageDao
import com.firestream.chat.data.local.entity.MessageEntity
import com.firestream.chat.data.outbox.OutboxSender
import com.firestream.chat.data.remote.source.AuthSource
import com.firestream.chat.data.remote.source.MessageSource
import com.firestream.chat.data.remote.source.UserSource
import com.firestream.chat.domain.model.Message
import com.firestream.chat.domain.model.MessageStatus
import com.firestream.chat.domain.model.MessageType
import com.firestream.chat.domain.repository.ChatRepository
import com.firestream.chat.domain.repository.ListRepository
import io.mockk.Called
import io.mockk.Runs
import io.mockk.coEvery
import io.mockk.coVerify
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
 * The block check stays in the repository, between the optimistic insert and
 * the hand-off to [OutboxSender]: the offline outbox plan later splits "definite
 * block fails" from "fetch error enqueues" at exactly this call site.
 */
class MessageRepositoryBlockTest {

    private val messageDao = mockk<MessageDao>()
    private val messageSource = mockk<MessageSource>()
    private val authSource = mockk<AuthSource>()
    private val outboxSender = mockk<OutboxSender>(relaxed = true)
    private val chatRepository = mockk<dagger.Lazy<ChatRepository>>()
    private val listRepository = mockk<dagger.Lazy<ListRepository>>()
    private val userSource = mockk<UserSource>(relaxed = true)

    private lateinit var repository: MessageRepositoryImpl

    private val inserted = slot<MessageEntity>()

    @Before
    fun setUp() {
        every { authSource.currentUserId } returns "uid1"
        repository = messageRepository(
            messageDao = messageDao,
            messageSource = messageSource,
            authSource = authSource,
            outboxSender = outboxSender,
            chatRepository = chatRepository,
            listRepository = listRepository,
            userSource = userSource,
        )
    }

    private fun stubOptimisticRow() {
        coEvery { messageDao.insertMessage(capture(inserted)) } just Runs
        coEvery { messageDao.updateMessageStatus(any(), any()) } just Runs
    }

    /** The send left a visible, retryable bubble and nothing reached the pipeline or the backend. */
    private fun assertRowInsertedThenFailed() {
        assertEquals(MessageStatus.SENDING.name, inserted.captured.status)
        coVerify(exactly = 1) {
            messageDao.updateMessageStatus(inserted.captured.id, MessageStatus.FAILED.name)
        }
        coVerify(exactly = 0) { outboxSender.send(any(), any()) }
        verify { messageSource wasNot Called }
    }

    // ── block check cannot be answered (offline cache miss) ─────────────────
    // Regression: the check ran before the optimistic insert, so a throwing
    // fetch dropped the message with no bubble and nothing to retry.

    private fun blockCheckThrows() {
        coEvery { userSource.isUserBlocked("uid1", "recipient1") } throws
            IllegalStateException("Failed to get document because the client is offline.")
    }

    @Test
    fun `sendMessage keeps a FAILED row when the block check throws`() = runTest {
        blockCheckThrows()
        stubOptimisticRow()

        val result = repository.sendMessage("chat1", "hello", "recipient1")

        assertTrue(result.isFailure)
        assertEquals("hello", inserted.captured.content)
        assertRowInsertedThenFailed()
    }

    @Test
    fun `sendMediaMessage keeps a FAILED row when the block check throws`() = runTest {
        blockCheckThrows()
        stubOptimisticRow()

        val result = repository.sendMediaMessage(
            "chat1", "content://docs/report.pdf", "application/pdf", "recipient1", "caption", null
        )

        assertTrue(result.isFailure)
        assertEquals("content://docs/report.pdf", inserted.captured.localUri)
        assertRowInsertedThenFailed()
    }

    @Test
    fun `sendVoiceMessage keeps a FAILED row when the block check throws`() = runTest {
        blockCheckThrows()
        stubOptimisticRow()

        val result = repository.sendVoiceMessage("chat1", "file:///tmp/v.aac", "recipient1", 5)

        assertTrue(result.isFailure)
        assertRowInsertedThenFailed()
    }

    @Test
    fun `sendLocationMessage keeps a FAILED row when the block check throws`() = runTest {
        blockCheckThrows()
        stubOptimisticRow()

        val result = repository.sendLocationMessage("chat1", 1.0, 2.0, "recipient1", "")

        assertTrue(result.isFailure)
        assertRowInsertedThenFailed()
    }

    @Test
    fun `sendTimerMessage keeps a FAILED row when the block check throws`() = runTest {
        blockCheckThrows()
        stubOptimisticRow()

        val result = repository.sendTimerMessage("chat1", 30_000L, null, "recipient1")

        assertTrue(result.isFailure)
        assertRowInsertedThenFailed()
    }

    // ── sendMessage blocked ─────────────────────────────────────────────────

    @Test
    fun `sendMessage fails when recipient is blocked by sender`() = runTest {
        coEvery { userSource.isUserBlocked("uid1", "recipient1") } returns true
        stubOptimisticRow()

        val result = repository.sendMessage("chat1", "hello", "recipient1")

        assertTrue(result.isFailure)
        assertEquals("Cannot send messages to a blocked user", result.exceptionOrNull()?.message)
        assertRowInsertedThenFailed()
    }

    @Test
    fun `sendMessage succeeds when recipient is not blocked`() = runTest {
        coEvery { userSource.isUserBlocked("uid1", "recipient1") } returns false
        coEvery { messageDao.insertMessage(any()) } just Runs

        val result = repository.sendMessage("chat1", "hello", "recipient1")

        assertTrue(result.isSuccess)
        coVerify(exactly = 1) { messageDao.insertMessage(any()) }
        coVerify(exactly = 1) { outboxSender.send(any(), null) }
    }

    @Test
    fun `sendMessage skips block check for empty recipientId (group chats)`() = runTest {
        coEvery { messageDao.insertMessage(any()) } just Runs

        val result = repository.sendMessage("chat1", "hello", "")

        assertTrue(result.isSuccess)
        coVerify(exactly = 0) { userSource.isUserBlocked(any(), any()) }
    }

    // ── forwardMessage blocked ──────────────────────────────────────────────

    @Test
    fun `forwardMessage fails when recipient is blocked by sender`() = runTest {
        coEvery { userSource.isUserBlocked("uid1", "recipient1") } returns true

        val message = Message(
            id = "m1", chatId = "chat1", senderId = "uid1", content = "hi",
            type = MessageType.TEXT,
            status = MessageStatus.SENT,
            timestamp = 1000L
        )
        val result = repository.forwardMessage(message, "chat2", "recipient1")

        assertTrue(result.isFailure)
        assertEquals("Cannot send messages to a blocked user", result.exceptionOrNull()?.message)
        coVerify(exactly = 0) { messageDao.insertMessage(any()) }
    }

    // ── sendVoiceMessage blocked ────────────────────────────────────────────

    @Test
    fun `sendVoiceMessage fails when recipient is blocked by sender`() = runTest {
        coEvery { userSource.isUserBlocked("uid1", "recipient1") } returns true
        stubOptimisticRow()

        val result = repository.sendVoiceMessage("chat1", "file:///tmp/v.aac", "recipient1", 5)

        assertTrue(result.isFailure)
        assertEquals("Cannot send messages to a blocked user", result.exceptionOrNull()?.message)
        assertRowInsertedThenFailed()
    }

    // ── sendMediaMessage blocked ────────────────────────────────────────────

    @Test
    fun `sendMediaMessage fails when recipient is blocked by sender`() = runTest {
        coEvery { userSource.isUserBlocked("uid1", "recipient1") } returns true
        stubOptimisticRow()

        val result = repository.sendMediaMessage(
            "chat1", "content://docs/report.pdf", "application/pdf", "recipient1", "caption", null
        )

        assertTrue(result.isFailure)
        assertEquals("Cannot send messages to a blocked user", result.exceptionOrNull()?.message)
        assertRowInsertedThenFailed()
    }
}
