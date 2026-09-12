package com.firestream.chat.data.repository

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.firestream.chat.data.local.AppDatabase
import com.firestream.chat.data.local.dao.MessageDao
import com.firestream.chat.data.local.entity.MessageRecord
import com.firestream.chat.data.remote.source.AuthSource
import com.firestream.chat.data.remote.source.MessageSource
import com.firestream.chat.domain.model.Message
import com.firestream.chat.domain.model.MessageStatus
import com.firestream.chat.domain.model.MessageType
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * A receipt never moves a message's local status backwards. Regression: the
 * push for a message lands seconds after the open chat has already marked it
 * DELIVERED and then READ, and the push handler's delivery receipt took the
 * Room row back to DELIVERED — so the chat marked it read a second time and
 * the sender's ticks went read → delivered → read. The backend half of the
 * same rule is pinned in FirestoreMessageSourceTest, the SQL in MessageDaoReceiptTest.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29], manifest = Config.NONE, application = android.app.Application::class)
class MessageRepositoryReceiptOrderTest {

    private lateinit var db: AppDatabase
    private lateinit var dao: MessageDao
    private lateinit var repository: MessageRepositoryImpl

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
        val authSource = mockk<AuthSource>(relaxed = true)
        every { authSource.currentUserId } returns "me"
        repository = messageRepository(
            messageDao = dao,
            messageSource = mockk<MessageSource>(relaxed = true),
            authSource = authSource,
        )
    }

    @After
    fun tearDown() {
        db.close()
    }

    @Test
    fun `a delivery receipt landing after the read leaves the row READ`() = runTest {
        dao.upsertRecord(MessageRecord.fromDomain(incoming))
        repository.markMessagesAsDelivered("chat1", listOf("msg1"))
        repository.markMessagesAsRead("chat1", listOf("msg1"))

        repository.markMessagesAsDelivered("chat1", listOf("msg1"))

        assertEquals(MessageStatus.READ.name, dao.getMessageById("msg1")!!.status)
    }
}
