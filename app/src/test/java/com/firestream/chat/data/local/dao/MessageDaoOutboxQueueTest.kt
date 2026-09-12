package com.firestream.chat.data.local.dao

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.firestream.chat.data.local.AppDatabase
import com.firestream.chat.data.local.entity.MessageEntity
import com.firestream.chat.data.local.entity.MessageRecord
import com.firestream.chat.data.outbox.OutboxSender
import com.firestream.chat.data.outbox.SendTarget
import com.firestream.chat.data.outbox.outboxJob
import com.firestream.chat.domain.model.Message
import com.firestream.chat.domain.model.MessageStatus
import com.firestream.chat.domain.model.MessageType
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The queue is the messages table: the queries `OutboxScheduler.requeueAll`
 * drains it with on app start, the attempt budget a manual retry restores, and
 * the echo-dedupe lookup that must still match a row the give-up turned FAILED.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29], manifest = Config.NONE, application = android.app.Application::class)
class MessageDaoOutboxQueueTest {

    private lateinit var db: AppDatabase
    private lateinit var dao: MessageDao

    private val sendable = OutboxSender.SENDABLE_TYPES.map { it.name }

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        dao = db.messageDao()
    }

    @After
    fun tearDown() {
        db.close()
    }

    private fun own(
        id: String,
        status: MessageStatus = MessageStatus.SENDING,
        type: MessageType = MessageType.TEXT,
        senderId: String = "me",
        deletedAt: Long? = null,
        attempts: Int = 0,
    ) = MessageEntity.outbox(
        Message(
            id = id,
            chatId = "c1",
            senderId = senderId,
            content = "hi",
            type = type,
            status = status,
            timestamp = 1_000L,
            deletedAt = deletedAt,
        ),
        SendTarget.NoPeer,
    ).copy(outboxAttempts = attempts)

    @Test
    fun `getQueuedMessages returns the current user's SENDING rows of sendable types, tombstones included`() = runTest {
        dao.insertOutbox(own("queued-text"))
        dao.insertOutbox(own("queued-photo", type = MessageType.IMAGE, deletedAt = 5_000L))
        dao.insertOutbox(own("deleted-after-give-up", status = MessageStatus.FAILED, deletedAt = 5_000L))
        dao.insertOutbox(own("timer", type = MessageType.TIMER))
        dao.insertOutbox(own("theirs", senderId = "someone-else"))
        dao.insertOutbox(own("failed", status = MessageStatus.FAILED))
        dao.insertOutbox(own("sent", status = MessageStatus.SENT))
        dao.insertOutbox(own("deleted-the-direct-way", status = MessageStatus.SENT, deletedAt = 5_000L))

        val queued = dao.getQueuedMessages("me", sendable).map { it.id }

        assertEquals(setOf("queued-text", "queued-photo", "deleted-after-give-up"), queued.toSet())
    }

    // The SQL predicate and MessageEntity.outboxJob must agree on what is queued.
    @Test
    fun `getQueuedMessages agrees with outboxJob`() = runTest {
        val rows = listOf(
            own("a"), own("b", deletedAt = 5_000L), own("c", status = MessageStatus.FAILED, deletedAt = 5_000L),
            own("d", status = MessageStatus.FAILED), own("e", status = MessageStatus.SENT),
            own("f", status = MessageStatus.READ, deletedAt = 5_000L),
        )
        rows.forEach { dao.insertOutbox(it) }

        val queued = dao.getQueuedMessages("me", sendable).map { it.id }.toSet()

        assertEquals(rows.filter { it.outboxJob != null }.map { it.id }.toSet(), queued)
    }

    @Test
    fun `failQueuedOfOtherTypes fails only the current user's SENDING rows the outbox cannot send`() = runTest {
        dao.insertOutbox(own("timer", type = MessageType.TIMER))
        dao.insertOutbox(own("queued-text"))
        dao.insertOutbox(own("their-timer", type = MessageType.TIMER, senderId = "someone-else"))

        val failed = dao.failQueuedOfOtherTypes("me", sendable)

        assertEquals(1, failed)
        assertEquals(MessageStatus.FAILED.name, dao.getMessageById("timer")!!.status)
        assertEquals(MessageStatus.SENDING.name, dao.getMessageById("queued-text")!!.status)
        assertEquals(MessageStatus.SENDING.name, dao.getMessageById("their-timer")!!.status)
    }

    // A manual retry gets a fresh budget of automatic attempts, but a row whose
    // earlier attempt may have landed must keep writing if-absent.
    @Test
    fun `requeueForRetry flips to SENDING, brings a spent budget back to one and leaves a fresh row at zero`() = runTest {
        dao.insertOutbox(own("spent", status = MessageStatus.FAILED, attempts = 8))
        dao.insertOutbox(own("fresh", status = MessageStatus.FAILED, attempts = 0))

        dao.requeueForRetry("spent")
        dao.requeueForRetry("fresh")

        assertEquals(MessageStatus.SENDING.name, dao.getMessageById("spent")!!.status)
        assertEquals(1, dao.getMessageById("spent")!!.outboxAttempts)
        assertEquals(MessageStatus.SENDING.name, dao.getMessageById("fresh")!!.status)
        assertEquals(0, dao.getMessageById("fresh")!!.outboxAttempts)
    }

    @Test
    fun `getPendingSendingMessage matches a SENDING row`() = runTest {
        dao.upsertRecord(record("opt", status = MessageStatus.SENDING, timestamp = 5000L))

        val match = dao.getPendingSendingMessage(chatId = "c1", timestamp = 5000L, senderId = "me")

        assertNotNull(match)
        assertEquals("opt", match!!.id)
    }

    @Test
    fun `getPendingSendingMessage matches a FAILED row so the remote echo is de-duplicated`() = runTest {
        // The rare race: the message reached the backend but the give-up (or a
        // permanent failure) flipped its row FAILED before the remote echo
        // arrived. The echo path must still find it by timestamp + sender.
        dao.upsertRecord(record("given-up-but-sent", status = MessageStatus.FAILED, timestamp = 7000L))

        val match = dao.getPendingSendingMessage(chatId = "c1", timestamp = 7000L, senderId = "me")

        assertNotNull(match)
        assertEquals("given-up-but-sent", match!!.id)
    }

    @Test
    fun `getPendingSendingMessage ignores a fully SENT row`() = runTest {
        dao.upsertRecord(record("done", status = MessageStatus.SENT, timestamp = 9000L))

        val match = dao.getPendingSendingMessage(chatId = "c1", timestamp = 9000L, senderId = "me")

        assertNull(match)
    }

    private fun record(id: String, status: MessageStatus, timestamp: Long) = MessageRecord(
        id = id,
        chatId = "c1",
        senderId = "me",
        content = "hi",
        type = "TEXT",
        mediaUrl = null,
        mediaThumbnailUrl = null,
        status = status.name,
        replyToId = null,
        timestamp = timestamp,
        editedAt = null,
    )
}
