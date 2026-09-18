package com.firestream.chat.data.remote.firebase

import app.cash.turbine.test
import com.google.firebase.firestore.CollectionReference
import com.google.firebase.firestore.DocumentReference
import com.google.firebase.firestore.DocumentSnapshot
import com.google.firebase.firestore.EventListener
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.ListenerRegistration
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test

/**
 * [FirestoreChatSource.observeTypingUsers]: a `typingUsers` entry counts as
 * typing for [TYPING_TTL_MS] after its timestamp, and drops out on time even
 * when the writer's typing-off write never lands and the document never
 * changes again.
 */
class FirestoreChatSourceTypingTest {

    private val firestore = mockk<FirebaseFirestore>()
    private val chatRef = mockk<DocumentReference>()
    private val registration = mockk<ListenerRegistration>(relaxed = true)
    private val listener = slot<EventListener<DocumentSnapshot>>()

    private var now = 100_000L
    private lateinit var source: FirestoreChatSource

    @Before
    fun setUp() {
        val chats = mockk<CollectionReference>()
        every { firestore.collection("chats") } returns chats
        every { chats.document("chat1") } returns chatRef
        every { chatRef.addSnapshotListener(capture(listener)) } returns registration
        source = FirestoreChatSource(firestore) { now }
    }

    @Test
    fun `fresh entries are typing, aged ones are not`() = runTest {
        source.observeTypingUsers("chat1").test {
            snapshot("fresh" to now - 1_000, "stale" to now - TYPING_TTL_MS)

            assertEquals(listOf("fresh"), awaitItem())
        }
    }

    @Test
    fun `an entry the writer never cleared drops out when it ages past the TTL`() = runTest {
        source.observeTypingUsers("chat1").test {
            snapshot("other" to now - 4_000)
            assertEquals(listOf("other"), awaitItem())

            // No further snapshot: the writer lost its connection before the
            // typing-off write. The reader must not wait for the document to change.
            now += 6_000
            testScheduler.advanceTimeBy(6_001)

            assertEquals(emptyList<String>(), awaitItem())
            expectNoEvents()
        }
    }

    @Test
    fun `a newer snapshot restarts the expiry`() = runTest {
        source.observeTypingUsers("chat1").test {
            snapshot("other" to now - 9_000)
            assertEquals(listOf("other"), awaitItem())

            now += 500
            snapshot("other" to now) // another keystroke
            assertEquals(listOf("other"), awaitItem())

            now += 1_500
            testScheduler.advanceTimeBy(1_501) // the first entry's deadline passes
            expectNoEvents()

            now += 8_500
            testScheduler.advanceTimeBy(8_501)
            assertEquals(emptyList<String>(), awaitItem())
        }
    }

    @Test
    fun `an entry with no timestamp is ignored and the listener is removed on cancel`() = runTest {
        source.observeTypingUsers("chat1").test {
            val doc = mockk<DocumentSnapshot>()
            every { doc.get("typingUsers") } returns mapOf("other" to true, "typing" to now)
            listener.captured.onEvent(doc, null)

            assertEquals(listOf("typing"), awaitItem())
            cancel()
        }
        verify { registration.remove() }
    }

    private fun snapshot(vararg entries: Pair<String, Long>) {
        val doc = mockk<DocumentSnapshot>()
        every { doc.get("typingUsers") } returns entries.toMap()
        listener.captured.onEvent(doc, null)
    }
}
