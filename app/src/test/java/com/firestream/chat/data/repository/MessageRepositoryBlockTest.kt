package com.firestream.chat.data.repository

import com.firestream.chat.data.local.dao.MessageDao
import com.firestream.chat.data.local.entity.MessageEntity
import com.firestream.chat.data.local.entity.MessageRecord
import com.firestream.chat.data.outbox.OutboxScheduler
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
 * The block check in front of a queued send, between the optimistic insert and
 * the hand-off to [OutboxScheduler]. A definite block refuses the send: the row
 * lands FAILED, with the retry affordance and the banner. A check that cannot be
 * answered — offline, the block list not cached — no longer refuses: the row
 * queues, and `OutboxWorker` asks again once it is online (`OutboxWorkerTest`).
 * A timer is written directly and keeps the strict rule.
 */
class MessageRepositoryBlockTest {

    private val messageDao = mockk<MessageDao>()
    private val messageSource = mockk<MessageSource>()
    private val authSource = mockk<AuthSource>()
    private val outboxScheduler = mockk<OutboxScheduler>(relaxed = true)
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
            outboxScheduler = outboxScheduler,
            chatRepository = chatRepository,
            listRepository = listRepository,
            userSource = userSource,
        )
    }

    private fun stubOptimisticRow() {
        coEvery { messageDao.insertOutbox(capture(inserted)) } just Runs
        coEvery { messageDao.updateMessageStatus(any(), any()) } just Runs
    }

    /** The send left a visible, retryable bubble and nothing reached the queue or the backend. */
    private fun assertRowInsertedThenFailed() {
        assertEquals(MessageStatus.SENDING.name, inserted.captured.status)
        coVerify(exactly = 1) {
            messageDao.updateMessageStatus(inserted.captured.id, MessageStatus.FAILED.name)
        }
        verify(exactly = 0) { outboxScheduler.enqueue(any(), any()) }
        verify { messageSource wasNot Called }
    }

    /** The send left a SENDING bubble that the worker now owns. */
    private fun assertRowInsertedThenQueued() {
        assertEquals(MessageStatus.SENDING.name, inserted.captured.status)
        coVerify(exactly = 0) { messageDao.updateMessageStatus(any(), any()) }
        verify(exactly = 1) { outboxScheduler.enqueue(inserted.captured.id, any()) }
        verify { messageSource wasNot Called }
    }

    // ── block check cannot be answered (offline cache miss) ─────────────────
    // Once, the check ran before the optimistic insert and a throwing fetch
    // dropped the message with no bubble; then the row landed FAILED. Now it
    // queues: the worker runs the authoritative check when it is online.

    private fun blockCheckThrows() {
        coEvery { userSource.isUserBlocked("uid1", "recipient1") } throws
            IllegalStateException("Failed to get document because the client is offline.")
    }

    @Test
    fun `sendMessage queues the row when the block check cannot be answered`() = runTest {
        blockCheckThrows()
        stubOptimisticRow()

        val result = repository.sendMessage("chat1", "hello", "recipient1")

        assertTrue("queued: ${result.exceptionOrNull()}", result.isSuccess)
        assertEquals("hello", inserted.captured.content)
        assertRowInsertedThenQueued()
    }

    @Test
    fun `sendMediaMessage queues the row when the block check cannot be answered`() = runTest {
        blockCheckThrows()
        stubOptimisticRow()

        val result = repository.sendMediaMessage(
            "chat1", "content://docs/report.pdf", "application/pdf", "recipient1", "caption", null
        )

        assertTrue(result.isSuccess)
        assertEquals("content://docs/report.pdf", inserted.captured.localUri)
        assertRowInsertedThenQueued()
    }

    @Test
    fun `sendVoiceMessage queues the row when the block check cannot be answered`() = runTest {
        blockCheckThrows()
        stubOptimisticRow()

        val result = repository.sendVoiceMessage("chat1", "file:///tmp/v.aac", "recipient1", 5)

        assertTrue(result.isSuccess)
        assertRowInsertedThenQueued()
    }

    @Test
    fun `sendLocationMessage queues the row when the block check cannot be answered`() = runTest {
        blockCheckThrows()
        stubOptimisticRow()

        val result = repository.sendLocationMessage("chat1", 1.0, 2.0, "recipient1", "")

        assertTrue(result.isSuccess)
        assertRowInsertedThenQueued()
    }

    @Test
    fun `forwardMessage queues the row when the block check cannot be answered`() = runTest {
        blockCheckThrows()
        stubOptimisticRow()

        val result = repository.forwardMessage(sentText(), "chat2", "recipient1")

        assertTrue(result.isSuccess)
        assertRowInsertedThenQueued()
    }

    // A timer is not queued — it is written directly — so it keeps refusing
    // rather than deliver to someone who may have blocked the sender.
    @Test
    fun `sendTimerMessage keeps a FAILED row when the block check throws`() = runTest {
        blockCheckThrows()
        val timerRow = slot<MessageRecord>()
        coEvery { messageDao.upsertRecord(capture(timerRow)) } just Runs
        coEvery { messageDao.updateMessageStatus(any(), any()) } just Runs

        val result = repository.sendTimerMessage("chat1", 30_000L, null, "recipient1")

        assertTrue(result.isFailure)
        assertEquals(MessageStatus.SENDING.name, timerRow.captured.status)
        coVerify(exactly = 1) { messageDao.updateMessageStatus(timerRow.captured.id, MessageStatus.FAILED.name) }
        verify { messageSource wasNot Called }
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
    fun `sendMessage queues when recipient is not blocked`() = runTest {
        coEvery { userSource.isUserBlocked("uid1", "recipient1") } returns false
        stubOptimisticRow()

        val result = repository.sendMessage("chat1", "hello", "recipient1")

        assertTrue(result.isSuccess)
        coVerify(exactly = 1) { messageDao.insertOutbox(any()) }
        assertRowInsertedThenQueued()
    }

    @Test
    fun `sendMessage skips block check for empty recipientId (group chats)`() = runTest {
        stubOptimisticRow()

        val result = repository.sendMessage("chat1", "hello", "")

        assertTrue(result.isSuccess)
        coVerify(exactly = 0) { userSource.isUserBlocked(any(), any()) }
    }

    // ── forwardMessage blocked ──────────────────────────────────────────────

    @Test
    fun `forwardMessage fails before any insert when recipient is blocked by sender`() = runTest {
        coEvery { userSource.isUserBlocked("uid1", "recipient1") } returns true

        val result = repository.forwardMessage(sentText(), "chat2", "recipient1")

        assertTrue(result.isFailure)
        assertEquals("Cannot send messages to a blocked user", result.exceptionOrNull()?.message)
        coVerify(exactly = 0) { messageDao.insertOutbox(any()) }
        verify(exactly = 0) { outboxScheduler.enqueue(any(), any()) }
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

    private fun sentText() = Message(
        id = "m1", chatId = "chat1", senderId = "uid1", content = "hi",
        type = MessageType.TEXT,
        status = MessageStatus.SENT,
        timestamp = 1000L
    )
}
