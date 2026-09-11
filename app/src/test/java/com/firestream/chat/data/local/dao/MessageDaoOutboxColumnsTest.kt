package com.firestream.chat.data.local.dao

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.firestream.chat.data.local.AppDatabase
import com.firestream.chat.data.local.entity.MessageEntity
import com.firestream.chat.data.local.entity.MessageRecord
import com.firestream.chat.domain.model.Message
import com.firestream.chat.domain.model.MessageStatus
import com.firestream.chat.domain.model.MessageType
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The write contract of the `messages` table. A [MessageRecord] upsert — what a
 * snapshot, a sync or an edit echo writes — can only reach the backend's
 * columns; `localUri`, the star and the outbox bookkeeping survive it without
 * anyone copying them across. Losing the recipient would refuse a retry; losing
 * the attempt count would re-write a landed message blind; losing the ciphertext
 * would encrypt it a second time; losing `localUri` would re-download a photo.
 * Only the SENT transaction and the acknowledged-echo heal clear the outbox.
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

    private val queued = MessageEntity.outbox(unsent, recipientId = "peer1")
        .copy(isStarred = true, outboxCiphertext = "cipher-1", outboxSignalType = 3, outboxPeerIdentity = "id-1", outboxAttempts = 2)

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
        dao.insertOutbox(MessageEntity.outbox(unsent, recipientId = "peer1"))

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
        dao.storeOutboxCiphertext("msg1", "cipher-1", signalType = 3, peerIdentity = "id-1")
        dao.setPinned("msg1", pinned = true)

        val row = dao.getMessageById("msg1")!!
        assertEquals("peer1", row.outboxRecipientId)
        assertEquals(2, row.outboxAttempts)
        assertEquals("cipher-1", row.outboxCiphertext)
        assertEquals(3, row.outboxSignalType)
        assertEquals("id-1", row.outboxPeerIdentity)
        assertEquals("/media/msg1.jpg", row.localUri)
        assertEquals(800, row.mediaWidth)
        assertEquals("https://storage.example/msg1", row.mediaUrl)
        assertTrue(row.isPinned)
        assertEquals(MessageStatus.SENDING.name, row.status)
    }

    // The echo of an own write, or an edit of a received message, is a record
    // upsert. Before the split, that was a whole-row replace built from the
    // domain Message, which reset every column the Message does not carry.
    @Test
    fun `a record upsert over an existing row leaves localUri, the star and the outbox columns alone`() = runTest {
        dao.insertOutbox(queued)

        dao.upsertRecord(queued.record.copy(content = "caption, edited", editedAt = 2_000L, isPinned = true))

        val row = dao.getMessageById("msg1")!!
        assertEquals("caption, edited", row.content)
        assertEquals(2_000L, row.editedAt)
        assertTrue(row.isPinned)
        assertEquals("content://picker/1", row.localUri)
        assertTrue(row.isStarred)
        assertEquals("peer1", row.outboxRecipientId)
        assertEquals("cipher-1", row.outboxCiphertext)
        assertEquals(3, row.outboxSignalType)
        assertEquals("id-1", row.outboxPeerIdentity)
        assertEquals(2, row.outboxAttempts)
    }

    @Test
    fun `a record upsert of a new id inserts the row with the local defaults`() = runTest {
        dao.upsertRecord(MessageRecord.fromDomain(unsent.copy(id = "new1", status = MessageStatus.SENT)))

        val row = dao.getMessageById("new1")!!
        assertEquals(MessageStatus.SENT.name, row.status)
        assertNull(row.localUri)
        assertFalse(row.isStarred)
        assertNull(row.outboxRecipientId)
        assertEquals(0, row.outboxAttempts)
    }

    @Test
    fun `markSent writes the row as sent under its new id, keeps the given localUri and clears the outbox`() = runTest {
        dao.insertOutbox(queued)
        val sent = unsent.copy(id = "server-1", status = MessageStatus.SENT, mediaUrl = "https://storage.example/msg1")

        dao.markSent("msg1", MessageRecord.fromDomain(sent), localUri = "/media/msg1.jpg")

        assertNull(dao.getMessageById("msg1"))
        val row = dao.getMessageById("server-1")!!
        assertEquals(MessageStatus.SENT.name, row.status)
        assertEquals("https://storage.example/msg1", row.mediaUrl)
        assertEquals("/media/msg1.jpg", row.localUri)
        assertNull(row.outboxRecipientId)
        assertNull(row.outboxCiphertext)
        assertNull(row.outboxSignalType)
        assertNull(row.outboxPeerIdentity)
        assertEquals(0, row.outboxAttempts)
    }

    @Test
    fun `markSent under the same id keeps the star and can drop the localUri`() = runTest {
        dao.insertOutbox(queued)

        dao.markSent("msg1", MessageRecord.fromDomain(unsent.copy(status = MessageStatus.SENT)), localUri = null)

        val row = dao.getMessageById("msg1")!!
        assertEquals(MessageStatus.SENT.name, row.status)
        assertNull(row.localUri)
        assertTrue(row.isStarred)
        assertEquals(0, row.outboxAttempts)
    }

    // A send whose await died but whose write landed heals through the
    // acknowledged echo, never through markSent; it must leave the outbox too.
    @Test
    fun `acknowledge sets the backend's status and clears the outbox columns`() = runTest {
        dao.insertOutbox(queued.copy(record = queued.record.copy(status = MessageStatus.FAILED.name)))

        dao.acknowledge("msg1", MessageStatus.SENT.name)

        val row = dao.getMessageById("msg1")!!
        assertEquals(MessageStatus.SENT.name, row.status)
        assertEquals("content://picker/1", row.localUri)
        assertNull(row.outboxRecipientId)
        assertNull(row.outboxCiphertext)
        assertNull(row.outboxPeerIdentity)
        assertEquals(0, row.outboxAttempts)
    }
}
