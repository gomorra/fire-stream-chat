package com.firestream.chat.data.remote.firebase

import com.firestream.chat.domain.model.CallMedia
import com.firestream.chat.domain.model.CallSignalingData
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

/**
 * What `calls/{callId}` carries beside the negotiation. The call's kind is written at creation and
 * read with a missing field as a voice call. The live state of each side is `media.<uid>`.
 */
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
}
