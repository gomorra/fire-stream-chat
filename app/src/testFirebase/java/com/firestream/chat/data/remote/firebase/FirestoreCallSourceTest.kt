package com.firestream.chat.data.remote.firebase

import com.google.android.gms.tasks.Task
import com.google.firebase.firestore.CollectionReference
import com.google.firebase.firestore.DocumentReference
import com.google.firebase.firestore.DocumentSnapshot
import com.google.firebase.firestore.FirebaseFirestore
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/** The call's kind on `calls/{callId}`: written at creation, read with a missing field as a voice call. */
class FirestoreCallSourceTest {

    private val firestore = mockk<FirebaseFirestore>(relaxed = true)
    private val calls = mockk<CollectionReference>(relaxed = true)
    private val callRef = mockk<DocumentReference>(relaxed = true)

    private lateinit var source: FirestoreCallSource

    @Before
    fun setUp() {
        every { firestore.collection("calls") } returns calls
        every { calls.document() } returns callRef
        every { calls.document("call1") } returns callRef
        every { callRef.id } returns "call1"
        source = FirestoreCallSource(firestore)
    }

    private suspend fun createdCall(video: Boolean): Map<String, Any?> {
        val written = slot<Map<String, Any?>>()
        val setTask = mockk<Task<Void>>(relaxed = true)
        completeImmediately(setTask)
        every { callRef.set(capture(written)) } returns setTask

        assertEquals("call1", source.createCallDocument("caller1", "callee1", video))
        return written.captured
    }

    @Test
    fun `a call started as video is created with video true`() = runTest {
        val written = createdCall(video = true)

        assertEquals(true, written["video"])
        assertEquals("caller1", written["callerId"])
        assertEquals("callee1", written["calleeId"])
        assertEquals("ringing", written["status"])
    }

    @Test
    fun `a call started as voice is created with video false`() = runTest {
        assertEquals(false, createdCall(video = false)["video"])
    }

    private suspend fun readCall(vararg extra: Pair<String, Any?>): Boolean {
        val doc = mockk<DocumentSnapshot>(relaxed = true)
        every { doc.data } returns mapOf(
            "callerId" to "caller1", "calleeId" to "callee1", "status" to "ringing",
            "createdAt" to 5L, *extra,
        )
        val getTask = mockk<Task<DocumentSnapshot>>(relaxed = true)
        completeImmediately(getTask)
        every { getTask.result } returns doc
        every { callRef.get() } returns getTask

        return source.getCallById("call1")!!.video
    }

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
}
