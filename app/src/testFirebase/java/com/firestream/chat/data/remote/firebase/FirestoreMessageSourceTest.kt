package com.firestream.chat.data.remote.firebase

import com.firestream.chat.domain.model.MessageType
import com.google.android.gms.tasks.Task
import com.google.firebase.firestore.CollectionReference
import com.google.firebase.firestore.DocumentReference
import com.google.firebase.firestore.DocumentSnapshot
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.Transaction
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import io.mockk.verifyOrder
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import java.io.IOException

/**
 * The idempotent-write contract of [FirestoreMessageSource.writeMessage], the
 * newer-only chat preview written after it, and the tombstone of a message
 * deleted while it was queued.
 *
 * Firestore itself cannot run under Robolectric, so these tests pin the parts
 * a unit test *can* see: which SDK call each attempt makes, what a transaction
 * body does against a stubbed document, and that every attempt is bounded when
 * the SDK never acknowledges.
 */
class FirestoreMessageSourceTest {

    private val firestore = mockk<FirebaseFirestore>(relaxed = true)
    private val messageRef = mockk<DocumentReference>(relaxed = true)
    private val chatRef = mockk<DocumentReference>(relaxed = true)
    private val setTask = mockk<Task<Void>>(relaxed = true)

    /** Every transaction body handed to the SDK, in order. None has run yet. */
    private val transactions = mutableListOf<Transaction.Function<Any?>>()

    private lateinit var source: FirestoreMessageSource

    @Before
    fun setUp() {
        val chats = mockk<CollectionReference>(relaxed = true)
        val messages = mockk<CollectionReference>(relaxed = true)
        every { firestore.collection("chats") } returns chats
        every { chats.document("chat1") } returns chatRef
        every { chatRef.collection("messages") } returns messages
        every { messages.document("msg1") } returns messageRef
        completeImmediately(setTask)
        every { messageRef.set(any<Map<String, Any?>>()) } returns setTask
        every { firestore.runTransaction(capture(transactions)) } returns mockk(relaxed = true)
        source = FirestoreMessageSource(firestore)
    }

    @Test
    fun `first attempt is a plain set under the client id`() = runTest {
        val id = source.sendPlainMessage(
            chatId = "chat1", senderId = "uid1", messageId = "msg1",
            content = "hi", type = MessageType.TEXT, replyToId = null, timestamp = 1L,
        )

        assertEquals("msg1", id)
        verify(exactly = 1) { messageRef.set(any<Map<String, Any?>>()) }
        verify(exactly = 0) { firestore.waitForPendingWrites() }
        // The one transaction is the chat preview's; the message itself is not written in one.
        assertEquals(1, transactions.size)
    }

    // The chat document is readable by the server like the message document; a
    // preview holding the text would put the plaintext beside the ciphertext.
    @Test
    fun `an encrypted message's chat preview carries its type, never its text`() = runTest {
        source.sendMessage(
            chatId = "chat1", senderId = "uid1", messageId = "msg1",
            ciphertext = "cipher-1", signalType = 3,
            type = MessageType.TEXT, replyToId = null, timestamp = 1L,
        )

        assertEquals("Message", previewWrites(storedTimestamp = null).single()["lastMessageContent"])
        val written = slot<Map<String, Any?>>()
        verify { messageRef.set(capture(written)) }
        assertFalse("content" in written.captured)
        assertEquals("cipher-1", written.captured["ciphertext"])
    }

    @Test
    fun `an encrypted photo's chat preview drops the caption`() = runTest {
        source.sendMessage(
            chatId = "chat1", senderId = "uid1", messageId = "msg1",
            ciphertext = "cipher-1", signalType = 3,
            type = MessageType.IMAGE, replyToId = null, timestamp = 1L,
            mediaUrl = "https://storage.example/msg1",
        )

        assertEquals("📷 Photo", previewWrites(storedTimestamp = null).single()["lastMessageContent"])
    }

    // Regression: the preview was a blind update(), so of two parallel sends the
    // one whose write finished last took the chat preview, older or not.
    @Test
    fun `a chat preview never replaces a newer one`() = runTest {
        source.sendPlainMessage(
            chatId = "chat1", senderId = "uid1", messageId = "msg1",
            content = "hi", type = MessageType.TEXT, replyToId = null, timestamp = 2_000L,
        )

        assertTrue(previewWrites(storedTimestamp = 3_000L).isEmpty())
    }

    @Test
    fun `a chat preview replaces an older or same-time one`() = runTest {
        source.sendPlainMessage(
            chatId = "chat1", senderId = "uid1", messageId = "msg1",
            content = "hi", type = MessageType.TEXT, replyToId = null, timestamp = 2_000L,
        )

        val overOlder = previewWrites(storedTimestamp = 1_000L).single()
        assertEquals("hi", overOlder["lastMessageContent"])
        assertEquals(2_000L, overOlder["lastMessageTimestamp"])
        assertEquals("uid1", overOlder["lastMessageSenderId"])
        assertEquals(1, previewWrites(storedTimestamp = 2_000L).size)
    }

    @Test
    fun `re-attempt flushes pending writes before creating if absent`() = runTest {
        val flushTask = mockk<Task<Void>>(relaxed = true)
        completeImmediately(flushTask)
        every { firestore.waitForPendingWrites() } returns flushTask
        val txTask = mockk<Task<Unit>>(relaxed = true)
        completeImmediately(txTask)
        every { firestore.runTransaction(any<Transaction.Function<Unit>>()) } returns txTask

        source.sendPlainMessage(
            chatId = "chat1", senderId = "uid1", messageId = "msg1",
            content = "hi", type = MessageType.TEXT, replyToId = null, timestamp = 1L,
            ifAbsent = true,
        )

        verify(exactly = 0) { messageRef.set(any<Map<String, Any?>>()) }
        verify(exactly = 1) { firestore.waitForPendingWrites() }
        verifyOrder {
            firestore.waitForPendingWrites()
            firestore.runTransaction(any<Transaction.Function<Unit>>())
        }
        // Create-if-absent, then the chat preview.
        verify(exactly = 2) { firestore.runTransaction(any<Transaction.Function<Unit>>()) }
    }

    // A connected network is not a live Firestore stream: a set() can wait for
    // an ack that is not coming. The worker retries; the next attempt is a
    // re-attempt and finds the write the SDK kept.
    @Test
    fun `a first attempt fails within the timeout when the write is never acknowledged`() = runTest {
        every { messageRef.set(any<Map<String, Any?>>()) } returns mockk(relaxed = true)

        try {
            source.sendPlainMessage(
                chatId = "chat1", senderId = "uid1", messageId = "msg1",
                content = "hi", type = MessageType.TEXT, replyToId = null, timestamp = 1L,
            )
            fail("expected the attempt to give up")
        } catch (e: IOException) {
            assertTrue(e.message!!.contains("msg1"))
        }
        assertEquals(SEND_ACK_TIMEOUT_MS, testScheduler.currentTime)
        verify(exactly = 1) { messageRef.set(any<Map<String, Any?>>()) }
        // No chat preview for a write that was not acknowledged.
        assertTrue(transactions.isEmpty())
    }

    @Test
    fun `re-attempt fails within the timeout when the flush never acknowledges`() = runTest {
        // A relaxed Task never reports complete and never invokes its listener:
        // exactly what waitForPendingWrites() does offline with the first
        // attempt still queued. Virtual time lets the 30 s elapse instantly.
        try {
            source.sendPlainMessage(
                chatId = "chat1", senderId = "uid1", messageId = "msg1",
                content = "hi", type = MessageType.TEXT, replyToId = null, timestamp = 1L,
                ifAbsent = true,
            )
            fail("expected the re-attempt to give up")
        } catch (e: IOException) {
            assertTrue(e.message!!.contains("msg1"))
        }
        assertEquals(SEND_ACK_TIMEOUT_MS, testScheduler.currentTime)
        verify(exactly = 0) { firestore.runTransaction(any<Transaction.Function<Unit>>()) }
        verify(exactly = 0) { messageRef.set(any<Map<String, Any?>>()) }
    }

    // ── a message deleted while queued ──────────────────────────────────────

    /** Stubs the flush and the transaction to complete at once, capturing the body. */
    private fun stubOnlineTransaction(): MutableList<Transaction.Function<Unit>> {
        val flushTask = mockk<Task<Void>>(relaxed = true)
        completeImmediately(flushTask)
        every { firestore.waitForPendingWrites() } returns flushTask
        val txTask = mockk<Task<Unit>>(relaxed = true)
        completeImmediately(txTask)
        val bodies = mutableListOf<Transaction.Function<Unit>>()
        every { firestore.runTransaction(capture(bodies)) } returns txTask
        return bodies
    }

    /** Runs [body] against a message document that does or does not [exist]; returns the update it made, if any. */
    private fun runAgainstMessage(body: Transaction.Function<Unit>, exists: Boolean): Map<String, Any?>? {
        val doc = mockk<DocumentSnapshot>(relaxed = true)
        every { doc.exists() } returns exists
        val tx = mockk<Transaction>(relaxed = true)
        every { tx.get(messageRef) } returns doc
        val updates = mutableListOf<Map<String, Any?>>()
        every { tx.update(messageRef, capture(updates)) } returns tx
        body.apply(tx)
        verify(exactly = 0) { tx.set(messageRef, any<Map<String, Any?>>()) }
        return updates.singleOrNull()
    }

    @Test
    fun `a tombstone flushes pending writes first, then updates only a document that exists`() = runTest {
        val bodies = stubOnlineTransaction()

        source.deleteIfExists("chat1", "msg1", deletedAt = 5_000L)

        verifyOrder {
            firestore.waitForPendingWrites()
            firestore.runTransaction(any<Transaction.Function<Unit>>())
        }
        val update = runAgainstMessage(bodies.single(), exists = true)!!
        assertEquals(5_000L, update["deletedAt"])
        assertEquals("", update["content"])
        assertTrue("ciphertext" in update && update["ciphertext"] == null)
        assertTrue("mediaUrl" in update && update["mediaUrl"] == null)
    }

    @Test
    fun `a tombstone never creates a document the backend does not hold`() = runTest {
        val bodies = stubOnlineTransaction()

        source.deleteIfExists("chat1", "msg1", deletedAt = 5_000L)

        assertEquals(null, runAgainstMessage(bodies.single(), exists = false))
    }

    @Test
    fun `a tombstone fails within the timeout when the flush never acknowledges`() = runTest {
        try {
            source.deleteIfExists("chat1", "msg1", deletedAt = 5_000L)
            fail("expected the tombstone to give up")
        } catch (e: IOException) {
            assertTrue(e.message!!.contains("msg1"))
        }
        assertEquals(SEND_ACK_TIMEOUT_MS, testScheduler.currentTime)
        verify(exactly = 0) { firestore.runTransaction(any<Transaction.Function<Unit>>()) }
    }

    /**
     * Runs every captured transaction body against a chat document whose preview
     * is stamped [storedTimestamp] (`null`: no preview yet) and returns the fields
     * each one updated it with.
     */
    private fun previewWrites(storedTimestamp: Long?): List<Map<String, Any>> {
        val chatDoc = mockk<DocumentSnapshot>(relaxed = true)
        every { chatDoc.getLong("lastMessageTimestamp") } returns storedTimestamp
        val tx = mockk<Transaction>(relaxed = true)
        every { tx.get(chatRef) } returns chatDoc
        val updates = mutableListOf<Map<String, Any>>()
        every { tx.update(chatRef, capture(updates)) } returns tx
        transactions.forEach { it.apply(tx) }
        return updates
    }

    // ── The single-document read behind a push reconcile ─────────────────────

    @Test
    fun `fetchMessage reads the one document under the message id`() = runTest {
        val doc = mockk<DocumentSnapshot>(relaxed = true)
        every { doc.id } returns "msg1"
        every { doc.data } returns mapOf(
            "senderId" to "peer1", "type" to "IMAGE", "content" to "",
            "mediaUrl" to "https://firebasestorage.example/msg1.jpg", "timestamp" to 5L,
        )
        every { doc.metadata.hasPendingWrites() } returns false
        val getTask = mockk<Task<DocumentSnapshot>>(relaxed = true)
        completeImmediately(getTask)
        every { getTask.result } returns doc
        every { messageRef.get() } returns getTask

        val raw = source.fetchMessage("chat1", "msg1")!!

        assertEquals("msg1", raw.id)
        assertEquals("chat1", raw.chatId)
        assertEquals("peer1", raw.senderId)
        assertEquals("https://firebasestorage.example/msg1.jpg", raw.mediaUrl)
        assertFalse(raw.hasPendingWrites)
    }

    @Test
    fun `fetchMessage answers null for a document the backend does not hold`() = runTest {
        val doc = mockk<DocumentSnapshot>(relaxed = true)
        every { doc.data } returns null
        val getTask = mockk<Task<DocumentSnapshot>>(relaxed = true)
        completeImmediately(getTask)
        every { getTask.result } returns doc
        every { messageRef.get() } returns getTask

        assertEquals(null, source.fetchMessage("chat1", "msg1"))
    }

    private fun <T> completeImmediately(task: Task<T>) {
        every { task.isComplete } returns true
        every { task.isCanceled } returns false
        every { task.exception } returns null
        every { task.result } returns null
    }
}
