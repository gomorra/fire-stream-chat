package com.firestream.chat.data.remote.firebase

import com.google.android.gms.tasks.Task
import com.google.firebase.firestore.CollectionReference
import com.google.firebase.firestore.DocumentReference
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.FirebaseFirestoreException
import com.google.firebase.firestore.Transaction
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test

/**
 * How a call document is created. Firestore cannot run under Robolectric, so these tests pin which
 * SDK call creates it: a transaction, which fails without the server, not a set(), which would wait
 * in the cache and ring the callee whenever the phone is next online.
 */
class FirestoreCallSourceTest {

    private val firestore = mockk<FirebaseFirestore>(relaxed = true)
    private val calls = mockk<CollectionReference>(relaxed = true)
    private val callRef = mockk<DocumentReference>(relaxed = true)

    /** Every transaction body handed to the SDK, in order. None has run yet. */
    private val transactions = mutableListOf<Transaction.Function<Any?>>()

    private lateinit var source: FirestoreCallSource

    @Before
    fun setUp() {
        every { firestore.collection("calls") } returns calls
        every { calls.document() } returns callRef
        every { callRef.id } returns "call1"
        every { firestore.runTransaction(capture(transactions)) } returns completed()
        source = FirestoreCallSource(firestore)
    }

    @Test
    fun `a call is created in a transaction that only writes`() = runTest {
        val callId = source.createCallDocument(callerId = "me", calleeId = "u2")

        assertEquals("call1", callId)
        verify(exactly = 0) { callRef.set(any<Any>()) }

        val transaction = mockk<Transaction>(relaxed = true)
        val written = slot<Any>()
        every { transaction.set(callRef, capture(written)) } returns transaction
        transactions.single().apply(transaction)

        // The rules refuse to read a call that does not exist yet, so the body must not try.
        verify(exactly = 0) { transaction.get(any()) }
        val data = written.captured as Map<*, *>
        assertEquals("me", data["callerId"])
        assertEquals("u2", data["calleeId"])
        assertEquals("ringing", data["status"])
    }

    @Test
    fun `a create the server never takes fails`() = runTest {
        val offline = FirebaseFirestoreException("offline", FirebaseFirestoreException.Code.UNAVAILABLE)
        every { firestore.runTransaction(any<Transaction.Function<Any?>>()) } returns failed(offline)

        try {
            source.createCallDocument(callerId = "me", calleeId = "u2")
            fail("the create should have failed")
        } catch (e: FirebaseFirestoreException) {
            assertEquals(FirebaseFirestoreException.Code.UNAVAILABLE, e.code)
        }
    }

    private fun <T> completed(): Task<T> = mockk<Task<T>>(relaxed = true).also { task ->
        every { task.isComplete } returns true
        every { task.isCanceled } returns false
        every { task.exception } returns null
        every { task.result } returns null
    }

    private fun <T> failed(error: Exception): Task<T> = mockk<Task<T>>(relaxed = true).also { task ->
        every { task.isComplete } returns true
        every { task.isCanceled } returns false
        every { task.exception } returns error
    }
}
