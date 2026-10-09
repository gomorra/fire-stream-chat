package com.firestream.chat.data.remote.firebase

import com.firestream.chat.domain.model.CallMedia
import com.firestream.chat.domain.model.CallSignalingData
import com.google.android.gms.tasks.Task
import com.google.firebase.firestore.CollectionReference
import com.google.firebase.firestore.DocumentReference
import com.google.firebase.firestore.DocumentSnapshot
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.FirebaseFirestoreException
import com.google.firebase.firestore.Transaction
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test

/**
 * How a call document is created, and what `calls/{callId}` carries beside the negotiation.
 *
 * Firestore cannot run under Robolectric, so these tests pin which SDK call creates the document: a
 * transaction, which fails without the server, not a set(), which would wait in the cache and ring
 * the callee whenever the phone is next online.
 *
 * The call's kind is written at creation and read with a missing field as a voice call. The live
 * state of each side is `media.<uid>`.
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
        every { calls.document("call1") } returns callRef
        every { callRef.id } returns "call1"
        every { firestore.runTransaction(capture(transactions)) } returns completed()
        source = FirestoreCallSource(firestore)
    }

    /** Create a call, run the transaction the SDK was handed, and return what it wrote. */
    private suspend fun createdCall(video: Boolean, transaction: Transaction = mockk(relaxed = true)): Map<*, *> {
        assertEquals("call1", source.createCallDocument(callerId = "caller1", calleeId = "callee1", video = video))
        val written = slot<Any>()
        every { transaction.set(callRef, capture(written)) } returns transaction
        transactions.single().apply(transaction)
        return written.captured as Map<*, *>
    }

    // ── Creating the call ───────────────────────────────────────────────────

    @Test
    fun `a call is created in a transaction that only writes`() = runTest {
        val transaction = mockk<Transaction>(relaxed = true)

        val data = createdCall(video = false, transaction)

        verify(exactly = 0) { callRef.set(any<Any>()) }
        // The rules refuse to read a call that does not exist yet, so the body must not try.
        verify(exactly = 0) { transaction.get(any()) }
        assertEquals("caller1", data["callerId"])
        assertEquals("callee1", data["calleeId"])
        assertEquals("ringing", data["status"])
    }

    @Test
    fun `a create the server never takes fails`() = runTest {
        val offline = FirebaseFirestoreException("offline", FirebaseFirestoreException.Code.UNAVAILABLE)
        every { firestore.runTransaction(any<Transaction.Function<Any?>>()) } returns failed(offline)

        try {
            source.createCallDocument(callerId = "caller1", calleeId = "callee1", video = false)
            fail("the create should have failed")
        } catch (e: FirebaseFirestoreException) {
            assertEquals(FirebaseFirestoreException.Code.UNAVAILABLE, e.code)
        }
    }

    // ── video: how the call was started ─────────────────────────────────────

    @Test
    fun `a call started as video is created with video true`() = runTest {
        assertEquals(true, createdCall(video = true)["video"])
    }

    @Test
    fun `a call started as voice is created with video false`() = runTest {
        assertEquals(false, createdCall(video = false)["video"])
    }

    private suspend fun readCallDocument(vararg extra: Pair<String, Any?>): CallSignalingData {
        val doc = mockk<DocumentSnapshot>(relaxed = true)
        every { doc.data } returns mapOf(
            "callerId" to "caller1", "calleeId" to "callee1", "status" to "ringing",
            "createdAt" to 5L, *extra,
        )
        val getTask = mockk<Task<DocumentSnapshot>>(relaxed = true)
        completeImmediately(getTask)
        every { getTask.result } returns doc
        every { callRef.get() } returns getTask

        return source.getCallById("call1")!!
    }

    private suspend fun readCall(vararg extra: Pair<String, Any?>): Boolean = readCallDocument(*extra).video

    @Test
    fun `a read carries the call's kind`() = runTest {
        assertTrue(readCall("video" to true))
        assertFalse(readCall("video" to false))
    }

    // An older app creates the call document without the field.
    @Test
    fun `a call document without the field is a voice call`() = runTest {
        assertFalse(readCall())
    }

    // ── media: each side's live camera and microphone state ──────────────────

    @Test
    fun `setMedia writes only the writer's own entry`() = runTest {
        val written = slot<Map<String, Any>>()
        val updateTask = mockk<Task<Void>>(relaxed = true)
        completeImmediately(updateTask)
        every { callRef.update(capture(written)) } returns updateTask

        source.setMedia("call1", "caller1", camera = true, mic = false)

        // Dotted paths: the other side's entry under `media` is left alone.
        assertEquals(
            mapOf("media.caller1.camera" to true, "media.caller1.mic" to false),
            written.captured
        )
    }

    @Test
    fun `a read carries the live state of both sides`() = runTest {
        val call = readCallDocument(
            "media" to mapOf(
                "caller1" to mapOf("camera" to true, "mic" to false),
                "callee1" to mapOf("camera" to false, "mic" to true),
            )
        )

        assertEquals(
            mapOf(
                "caller1" to CallMedia(camera = true, mic = false),
                "callee1" to CallMedia(camera = false, mic = true),
            ),
            call.media
        )
    }

    // An older app, and a call that has not connected, write nothing.
    @Test
    fun `a call document without media has no entries`() = runTest {
        assertTrue(readCallDocument().media.isEmpty())
    }

    @Test
    fun `a missing field reads as camera off and microphone on, and a broken entry is skipped`() = runTest {
        val call = readCallDocument(
            "media" to mapOf(
                "caller1" to emptyMap<String, Any>(),
                "callee1" to "not a map",
            )
        )

        assertEquals(mapOf("caller1" to CallMedia(camera = false, mic = true)), call.media)
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
