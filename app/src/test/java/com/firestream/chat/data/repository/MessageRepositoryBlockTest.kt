package com.firestream.chat.data.repository

import com.firestream.chat.data.local.dao.MessageDao
import com.firestream.chat.data.local.entity.MessageEntity
import com.firestream.chat.data.local.entity.MessageRecord
import com.firestream.chat.data.outbox.OutboxScheduler
import com.firestream.chat.data.remote.source.AuthSource
import com.firestream.chat.data.remote.source.MessageSource
import com.firestream.chat.data.remote.source.UserSource
import com.firestream.chat.domain.model.ChatNotReadyException
import com.firestream.chat.domain.model.ChatType
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
 *
 * Who the check asks about, and who the row is addressed to, comes from the
 * chat's row: the other participant of a 1:1 chat, nobody for a group. A chat
 * the row cannot address is refused before anything is written.
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
            chats = listOf(
                testChat("chat1"),
                testChat("chat2"),
                testChat("group1", ChatType.GROUP.name, listOf("uid1", "recipient1", "member2")),
            ),
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

    // ── who a row is addressed to ───────────────────────────────────────────
    // The target a row records is who OutboxSender encrypts for and who the
    // worker's block check asks about. It comes from the chat's row on every
    // path that writes one; the recipient argument is not an input.

    /** Every send that writes an outbox row, into [chatId], naming [recipientId] as the caller's recipient. */
    private fun rowPaths(chatId: String, recipientId: String): Map<String, suspend () -> Result<Message>> = mapOf(
        "text" to { repository.sendMessage(chatId, "hello", recipientId) },
        "media" to {
            repository.sendMediaMessage(chatId, "content://docs/report.pdf", "application/pdf", recipientId, "caption", null)
        },
        "voice" to { repository.sendVoiceMessage(chatId, "file:///tmp/v.aac", recipientId, 5) },
        "location" to { repository.sendLocationMessage(chatId, 1.0, 2.0, recipientId, "") },
        "forward" to { repository.forwardMessage(sentText(), chatId, recipientId) },
    )

    /** Runs each path and asserts that it queued a row recording [expected] as its `outboxRecipientId`. */
    private suspend fun assertEachRecords(expected: String, paths: Map<String, suspend () -> Result<Message>>) {
        paths.forEach { (path, send) ->
            inserted.clear()
            val result = send()
            assertTrue("$path should queue: ${result.exceptionOrNull()}", result.isSuccess)
            assertTrue("$path should insert a row", inserted.isCaptured)
            assertEquals(path, expected, inserted.captured.outboxRecipientId)
        }
    }

    @Test
    fun `every row path into a 1-to-1 chat records the chat's other participant, whatever recipient the caller names`() = runTest {
        stubOptimisticRow()

        assertEachRecords("recipient1", rowPaths("chat1", recipientId = "someone-else"))

        coVerify(exactly = 0) { userSource.isUserBlocked(any(), "someone-else") }
    }

    // Regression: a screen that named one group member as the recipient (a group
    // opened from its notification did) had the send addressed to that member —
    // refused when the sender had blocked them, encrypted for them alone otherwise.
    @Test
    fun `every row path into a group records no peer and asks nobody's block list, whatever recipient the caller names`() = runTest {
        // The member the caller names is one the sender has blocked.
        coEvery { userSource.isUserBlocked("uid1", "recipient1") } returns true
        stubOptimisticRow()

        assertEachRecords("", rowPaths("group1", recipientId = "recipient1"))

        coVerify(exactly = 0) { userSource.isUserBlocked(any(), any()) }
    }

    // A refused send writes nothing: no bubble, no FAILED row, nothing a retry
    // could later send in plaintext. messageDao is a strict mock, so a write
    // would also fail the send with something other than the refusal.
    @Test
    fun `every row path into a chat with no local row is refused before any row is written`() = runTest {
        rowPaths("not-synced-yet", recipientId = "recipient1").forEach { (path, send) ->
            val refusal = send().exceptionOrNull()

            assertTrue("$path: $refusal", refusal is ChatNotReadyException)
        }

        coVerify(exactly = 0) { messageDao.insertOutbox(any()) }
        verify(exactly = 0) { outboxScheduler.enqueue(any(), any()) }
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
