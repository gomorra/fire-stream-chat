package com.firestream.chat.data.local.dao

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.firestream.chat.data.local.AppDatabase
import com.firestream.chat.data.local.entity.MessageEntity
import com.firestream.chat.domain.model.MessageStatus
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
 * Regression tests for orphan recovery: a send whose coroutine is cancelled
 * mid-flight (the user leaves the chat before it completes) leaves its
 * optimistic row stuck at SENDING forever. [MessageDao.failStuckSendingMessages]
 * (run on app start) and [MessageDao.failStuckSendingMessagesForChat] (run on
 * chat re-entry) flip those orphans to FAILED so the manual-retry button
 * reappears.
 *
 * Also guards the widened [MessageDao.getPendingSendingMessage] echo-dedupe,
 * which must still match a row the flip turned to FAILED so a remote echo that
 * arrives after the flip is de-duplicated rather than inserted twice.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29], manifest = Config.NONE, application = android.app.Application::class)
class MessageDaoOrphanRecoveryTest {

    private lateinit var db: AppDatabase
    private lateinit var dao: MessageDao

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

    @Test
    fun `failStuckSendingMessages flips SENDING to FAILED and leaves other states untouched`() = runTest {
        dao.insertMessages(listOf(
            msg(id = "sending-1", status = MessageStatus.SENDING),
            msg(id = "sending-2", status = MessageStatus.SENDING),
            msg(id = "sent", status = MessageStatus.SENT),
            msg(id = "delivered", status = MessageStatus.DELIVERED),
            msg(id = "read", status = MessageStatus.READ),
            msg(id = "already-failed", status = MessageStatus.FAILED),
        ))

        val recovered = dao.failStuckSendingMessages()

        assertEquals(2, recovered)
        assertEquals(MessageStatus.FAILED.name, dao.getMessageById("sending-1")!!.status)
        assertEquals(MessageStatus.FAILED.name, dao.getMessageById("sending-2")!!.status)
        assertEquals(MessageStatus.SENT.name, dao.getMessageById("sent")!!.status)
        assertEquals(MessageStatus.DELIVERED.name, dao.getMessageById("delivered")!!.status)
        assertEquals(MessageStatus.READ.name, dao.getMessageById("read")!!.status)
        assertEquals(MessageStatus.FAILED.name, dao.getMessageById("already-failed")!!.status)
    }

    @Test
    fun `failStuckSendingMessagesForChat only recovers the given chat`() = runTest {
        dao.insertMessages(listOf(
            msg(id = "a-orphan", chatId = "chatA", status = MessageStatus.SENDING),
            msg(id = "b-orphan", chatId = "chatB", status = MessageStatus.SENDING),
        ))

        val recovered = dao.failStuckSendingMessagesForChat("chatA")

        assertEquals(1, recovered)
        assertEquals(MessageStatus.FAILED.name, dao.getMessageById("a-orphan")!!.status)
        // The other chat's in-flight row must be untouched.
        assertEquals(MessageStatus.SENDING.name, dao.getMessageById("b-orphan")!!.status)
    }

    @Test
    fun `getPendingSendingMessage matches a SENDING row`() = runTest {
        dao.insertMessage(msg(id = "opt", status = MessageStatus.SENDING, timestamp = 5000L))

        val match = dao.getPendingSendingMessage(chatId = "c1", timestamp = 5000L, senderId = "me")

        assertNotNull(match)
        assertEquals("opt", match!!.id)
    }

    @Test
    fun `getPendingSendingMessage matches a FAILED row so the remote echo is de-duplicated`() = runTest {
        // Simulates the rare race: the message reached the backend but its local
        // row was orphaned and then flipped to FAILED by recovery before the
        // remote echo arrived. The echo path must still find it (and skip the
        // duplicate insert) by timestamp + sender.
        dao.insertMessage(msg(id = "orphan-but-sent", status = MessageStatus.FAILED, timestamp = 7000L))

        val match = dao.getPendingSendingMessage(chatId = "c1", timestamp = 7000L, senderId = "me")

        assertNotNull(match)
        assertEquals("orphan-but-sent", match!!.id)
    }

    @Test
    fun `getPendingSendingMessage ignores a fully SENT row`() = runTest {
        dao.insertMessage(msg(id = "done", status = MessageStatus.SENT, timestamp = 9000L))

        val match = dao.getPendingSendingMessage(chatId = "c1", timestamp = 9000L, senderId = "me")

        assertNull(match)
    }

    private fun msg(
        id: String,
        chatId: String = "c1",
        status: MessageStatus,
        timestamp: Long = 1000L,
        senderId: String = "me",
    ): MessageEntity = MessageEntity(
        id = id,
        chatId = chatId,
        senderId = senderId,
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
