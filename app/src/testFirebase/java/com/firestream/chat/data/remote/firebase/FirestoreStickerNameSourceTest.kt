package com.firestream.chat.data.remote.firebase

import com.firestream.chat.data.remote.source.RemoteStickerName
import com.firestream.chat.data.remote.source.StickerNamingRefusedException
import com.google.android.gms.tasks.Task
import com.google.firebase.firestore.CollectionReference
import com.google.firebase.firestore.DocumentReference
import com.google.firebase.firestore.DocumentSnapshot
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.FirebaseFirestoreException
import com.google.firebase.firestore.Source
import com.google.firebase.firestore.Transaction
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test

/**
 * The shape of a naming request in Firestore, and which SDK call each operation
 * makes. `firestore.rules` allows exactly these, and the rules tests in
 * `firestore-rules-tests/stickerNames.test.js` make the same writes.
 */
class FirestoreStickerNameSourceTest {

    private val firestore = mockk<FirebaseFirestore>()
    private val collection = mockk<CollectionReference>()
    private val document = mockk<DocumentReference>()

    private lateinit var source: FirestoreStickerNameSource

    @Before
    fun setUp() {
        every { firestore.collection("stickerNames") } returns collection
        every { collection.document(ID) } returns document
        source = FirestoreStickerNameSource(firestore)
    }

    private fun <T> done(result: T? = null): Task<T> = mockk {
        every { isComplete } returns true
        every { isCanceled } returns false
        every { exception } returns null
        @Suppress("UNCHECKED_CAST")
        every { this@mockk.result } answers { result as T }
    }

    private fun <T> failed(error: Exception): Task<T> = mockk {
        every { isComplete } returns true
        every { isCanceled } returns false
        every { exception } returns error
    }

    private fun stored(data: Map<String, Any>?) {
        val snapshot = mockk<DocumentSnapshot> { every { this@mockk.data } returns data }
        every { document.get(Source.SERVER) } returns done(snapshot)
    }

    private fun denied() = FirebaseFirestoreException("denied", FirebaseFirestoreException.Code.PERMISSION_DENIED)

    @Test
    fun `a request is the three fields the rules allow, with the server's time`() {
        val request = stickerNameRequest("WEBP")

        assertEquals(setOf("status", "format", "requestedAt"), request.keys)
        assertEquals("pending", request["status"])
        assertEquals("WEBP", request["format"])
        assertEquals(FieldValue.serverTimestamp(), request["requestedAt"])
    }

    @Test
    fun `asking writes only when the backend has no document`() = runTest {
        val bodies = mutableListOf<Transaction.Function<Unit>>()
        every { firestore.runTransaction(capture(bodies)) } returns done(Unit)

        source.request(ID, "WEBP")

        fun writesAgainst(exists: Boolean): List<Any> {
            val snapshot = mockk<DocumentSnapshot> { every { exists() } returns exists }
            val writes = mutableListOf<Any>()
            val tx = mockk<Transaction> {
                every { get(document) } returns snapshot
                every { set(document, capture(writes)) } returns this
            }
            bodies.single().apply(tx)
            return writes
        }
        assertTrue("a record that is there is left as it is", writesAgainst(exists = true).isEmpty())
        assertEquals(listOf<Any>(stickerNameRequest("WEBP")), writesAgainst(exists = false))
        // No blind set: the rules refuse a write over a record, and that must not read as a refusal.
        verify(exactly = 0) { document.set(any()) }
    }

    @Test
    fun `a fetch asks the server and hands the document over as it is`() = runTest {
        val data = mapOf<String, Any>("status" to "done", "nameEn" to "Sleepy bear", "words" to listOf("tired"))
        stored(data)

        assertEquals(RemoteStickerName("done", data), source.fetch(ID))
        verify(exactly = 0) { document.get() }
    }

    @Test
    fun `a document that is not there is no record, and one without a status has an unknown one`() = runTest {
        stored(null)
        assertNull(source.fetch(ID))

        stored(mapOf("status" to 7, "nameEn" to "Sleepy bear"))
        assertEquals("", source.fetch(ID)!!.status)
    }

    @Test
    fun `permission denied is a refusal, on a fetch and on a request`() = runTest {
        val error = denied()
        every { document.get(Source.SERVER) } returns failed(error)
        every { firestore.runTransaction(any<Transaction.Function<Unit>>()) } returns failed(error)

        try {
            source.fetch(ID)
            fail("expected the fetch to be refused")
        } catch (e: StickerNamingRefusedException) {
            assertSame(error, e.cause)
        }
        try {
            source.request(ID, "WEBP")
            fail("expected the request to be refused")
        } catch (e: StickerNamingRefusedException) {
            assertSame(error, e.cause)
        }
    }

    @Test
    fun `any other failure stays what it is`() = runTest {
        val offline = FirebaseFirestoreException("offline", FirebaseFirestoreException.Code.UNAVAILABLE)
        every { document.get(Source.SERVER) } returns failed(offline)

        try {
            source.fetch(ID)
            fail("expected the failure to surface")
        } catch (e: FirebaseFirestoreException) {
            assertSame(offline, e)
        }
    }

    private companion object {
        val ID = "a".repeat(64)
    }
}
