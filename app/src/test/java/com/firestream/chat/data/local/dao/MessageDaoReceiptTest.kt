package com.firestream.chat.data.local.dao

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.firestream.chat.data.local.AppDatabase
import com.firestream.chat.data.local.entity.MessageRecord
import com.firestream.chat.domain.model.Message
import com.firestream.chat.domain.model.MessageStatus
import com.firestream.chat.domain.model.MessageType
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * [MessageDao.markDeliveredBatch] is forward-only: a delivery receipt moves a
 * SENT row to DELIVERED and leaves a READ row alone. Regression: the push for a
 * message landed after the open chat had read it, and the blind status update
 * took the row back to DELIVERED. The sequence itself is pinned one layer up in
 * MessageRepositoryReceiptOrderTest.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29], manifest = Config.NONE, application = android.app.Application::class)
class MessageDaoReceiptTest {

    private lateinit var db: AppDatabase
    private lateinit var dao: MessageDao

    private val incoming = Message(
        id = "msg1",
        chatId = "chat1",
        senderId = "peer1",
        content = "hi",
        type = MessageType.TEXT,
        status = MessageStatus.SENT,
        timestamp = 1_000L,
    )

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
    fun `a delivery receipt moves a SENT row to DELIVERED`() = runTest {
        dao.upsertRecord(MessageRecord.fromDomain(incoming))

        dao.markDeliveredBatch(listOf("msg1"))

        assertEquals(MessageStatus.DELIVERED.name, dao.getMessageById("msg1")!!.status)
    }

    @Test
    fun `one batch forwards the unread rows and leaves the read one alone`() = runTest {
        dao.upsertRecord(MessageRecord.fromDomain(incoming.copy(status = MessageStatus.READ)))
        dao.upsertRecord(MessageRecord.fromDomain(incoming.copy(id = "msg2", timestamp = 2_000L)))

        dao.markDeliveredBatch(listOf("msg1", "msg2"))

        assertEquals(MessageStatus.READ.name, dao.getMessageById("msg1")!!.status)
        assertEquals(MessageStatus.DELIVERED.name, dao.getMessageById("msg2")!!.status)
    }
}
