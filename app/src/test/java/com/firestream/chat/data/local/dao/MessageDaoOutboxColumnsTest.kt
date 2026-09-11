package com.firestream.chat.data.local.dao

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.firestream.chat.data.local.AppDatabase
import com.firestream.chat.data.local.entity.MessageEntity
import com.firestream.chat.domain.model.Message
import com.firestream.chat.domain.model.MessageStatus
import com.firestream.chat.domain.model.MessageType
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The outbox columns exist only on [MessageEntity], so every write to an unsent
 * row except its SENT replace must leave them alone. Losing the recipient would
 * refuse the retry; losing the attempt count would re-write a landed message
 * blind; losing the ciphertext would encrypt it a second time.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29], manifest = Config.NONE, application = android.app.Application::class)
class MessageDaoOutboxColumnsTest {

    private lateinit var db: AppDatabase
    private lateinit var dao: MessageDao

    private val unsent = Message(
        id = "msg1",
        chatId = "chat1",
        senderId = "uid1",
        content = "caption",
        type = MessageType.IMAGE,
        status = MessageStatus.SENDING,
        timestamp = 1_000L,
        localUri = "content://picker/1",
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
    fun `step, attempt, ciphertext and pin updates keep every outbox column`() = runTest {
        dao.insertMessage(MessageEntity.outbox(unsent, recipientId = "peer1"))

        dao.incrementOutboxAttempts("msg1")
        dao.incrementOutboxAttempts("msg1")
        dao.updateSendProgress(
            messageId = "msg1",
            localUri = "/media/msg1.jpg",
            mediaWidth = 800,
            mediaHeight = 600,
            duration = null,
            mediaThumbnailUrl = null,
            mediaUrl = "https://storage.example/msg1",
        )
        dao.storeOutboxCiphertext("msg1", "cipher-1", signalType = 3)
        dao.setPinned("msg1", pinned = true)

        val row = dao.getMessageById("msg1")!!
        assertEquals("peer1", row.outboxRecipientId)
        assertEquals(2, row.outboxAttempts)
        assertEquals("cipher-1", row.outboxCiphertext)
        assertEquals(3, row.outboxSignalType)
        assertEquals("/media/msg1.jpg", row.localUri)
        assertEquals(800, row.mediaWidth)
        assertEquals("https://storage.example/msg1", row.mediaUrl)
        assertTrue(row.isPinned)
        assertEquals(MessageStatus.SENDING.name, row.status)
    }

    @Test
    fun `the SENT replace clears the outbox columns`() = runTest {
        dao.insertMessage(
            MessageEntity.outbox(unsent, recipientId = "peer1")
                .copy(outboxCiphertext = "cipher-1", outboxSignalType = 3, outboxAttempts = 1)
        )

        dao.replaceMessage("msg1", MessageEntity.fromDomain(unsent.copy(status = MessageStatus.SENT)))

        val row = dao.getMessageById("msg1")!!
        assertNull(row.outboxRecipientId)
        assertNull(row.outboxCiphertext)
        assertNull(row.outboxSignalType)
        assertEquals(0, row.outboxAttempts)
    }
}
