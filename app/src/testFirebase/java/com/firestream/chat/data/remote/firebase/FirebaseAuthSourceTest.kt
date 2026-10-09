package com.firestream.chat.data.remote.firebase

import com.google.android.gms.tasks.Task
import com.google.firebase.firestore.CollectionReference
import com.google.firebase.firestore.DocumentReference
import com.google.firebase.firestore.DocumentSnapshot
import com.google.firebase.firestore.FirebaseFirestore
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * `users/{uid}.callVideoLine`: an app that takes a video line in a call offer says so, and a caller
 * reads it. An app from before video calls never wrote the field and crashes on such an offer, so
 * everything but a stored `true` reads as false.
 */
class FirebaseAuthSourceTest {

    private val firestore = mockk<FirebaseFirestore>(relaxed = true)
    private val users = mockk<CollectionReference>(relaxed = true)
    private val userRef = mockk<DocumentReference>(relaxed = true)

    private lateinit var source: FirebaseAuthSource

    @Before
    fun setUp() {
        every { firestore.collection("users") } returns users
        every { users.document("user1") } returns userRef
        source = FirebaseAuthSource(mockk(relaxed = true), firestore)
    }

    @Test
    fun `the announcement updates the one field and leaves the rest of the document alone`() = runTest {
        val updateTask = mockk<Task<Void>>(relaxed = true)
        completeImmediately(updateTask)
        every { userRef.update("callVideoLine", true) } returns updateTask

        source.announceCallVideoLine("user1")

        verify(exactly = 1) { userRef.update("callVideoLine", true) }
        // An update, not a set: it must not create a user document for someone without a profile.
        verify(exactly = 0) { userRef.set(any()) }
        verify(exactly = 0) { userRef.set(any(), any()) }
    }

    @Test
    fun `a new user document carries the field from its creation`() = runTest {
        val written = slot<Map<String, Any?>>()
        val setTask = mockk<Task<Void>>(relaxed = true)
        completeImmediately(setTask)
        every { userRef.set(capture(written)) } returns setTask

        source.createUserDocument("user1", "+491700000000", "Me", avatarUrl = null)

        assertEquals(true, written.captured["callVideoLine"])
        assertEquals("Me", written.captured["displayName"])
    }

    private suspend fun takesVideoLine(stored: Boolean?): Boolean {
        val doc = mockk<DocumentSnapshot>(relaxed = true)
        every { doc.getBoolean("callVideoLine") } returns stored
        val getTask = mockk<Task<DocumentSnapshot>>(relaxed = true)
        completeImmediately(getTask)
        every { getTask.result } returns doc
        every { userRef.get() } returns getTask

        return source.takesCallVideoLine("user1")
    }

    @Test
    fun `a user who announced takes a video line`() = runTest {
        assertTrue(takesVideoLine(stored = true))
    }

    // What an app from before video calls leaves behind, and what a missing document reads as.
    @Test
    fun `a user document without the field takes none`() = runTest {
        assertFalse(takesVideoLine(stored = null))
    }

    @Test
    fun `a stored false takes none`() = runTest {
        assertFalse(takesVideoLine(stored = false))
    }
}
