package com.firestream.chat.data.remote.firebase

import com.firestream.chat.domain.model.MessageType
import com.google.android.gms.tasks.Task
import com.google.firebase.firestore.CollectionReference
import com.google.firebase.firestore.DocumentReference
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.Transaction
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import java.io.IOException

/**
 * The idempotent-write contract of [FirestoreMessageSource.writeMessage].
 *
 * Firestore itself cannot run under Robolectric, so these tests pin the parts
 * a unit test *can* see: which SDK call each attempt makes, and that a
 * re-attempt is bounded when the SDK's flush never completes.
 */
class FirestoreMessageSourceTest {

    private val firestore = mockk<FirebaseFirestore>(relaxed = true)
    private val messageRef = mockk<DocumentReference>(relaxed = true)
    private val chatRef = mockk<DocumentReference>(relaxed = true)
    private val setTask = mockk<Task<Void>>(relaxed = true)

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
        verify(exactly = 0) { firestore.runTransaction(any<Transaction.Function<Unit>>()) }
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
        verify(exactly = 1) { firestore.runTransaction(any<Transaction.Function<Unit>>()) }
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
        assertEquals(RETRY_ACK_TIMEOUT_MS, testScheduler.currentTime)
        verify(exactly = 0) { firestore.runTransaction(any<Transaction.Function<Unit>>()) }
        verify(exactly = 0) { messageRef.set(any<Map<String, Any?>>()) }
    }

    private fun <T> completeImmediately(task: Task<T>) {
        every { task.isComplete } returns true
        every { task.isCanceled } returns false
        every { task.exception } returns null
        every { task.result } returns null
    }
}
