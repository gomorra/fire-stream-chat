package com.firestream.chat.data.remote.firebase

import com.google.android.gms.tasks.OnSuccessListener
import com.google.android.gms.tasks.Task
import com.google.firebase.database.DataSnapshot
import com.google.firebase.database.DatabaseError
import com.google.firebase.database.DatabaseReference
import com.google.firebase.database.FirebaseDatabase
import com.google.firebase.database.OnDisconnect
import com.google.firebase.database.ValueEventListener
import io.mockk.Runs
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

@Suppress("UNCHECKED_CAST")
class RealtimePresenceSourceTest {

    private val database = mockk<FirebaseDatabase>()
    private val presenceRef = mockk<DatabaseReference>(relaxed = true)
    private val connectedRef = mockk<DatabaseReference>(relaxed = true)
    private val mockOnDisconnect = mockk<OnDisconnect>(relaxed = true)
    private val onDisconnectTask = mockk<Task<Void>>(relaxed = true)
    private val setValueTask = mockk<Task<Void>>(relaxed = true)

    private val listenerSlot = slot<ValueEventListener>()

    private lateinit var source: RealtimePresenceSource

    @Before
    fun setUp() {
        every { database.getReference("presence/uid1") } returns presenceRef
        every { database.getReference(".info/connected") } returns connectedRef
        every { presenceRef.onDisconnect() } returns mockOnDisconnect
        every { mockOnDisconnect.setValue(any()) } returns onDisconnectTask
        every { presenceRef.setValue(any()) } returns setValueTask

        // Immediately invoke the onDisconnect success callback so the online write fires
        every { onDisconnectTask.addOnSuccessListener(any()) } answers {
            (firstArg<Any>() as OnSuccessListener<Void?>).onSuccess(null)
            onDisconnectTask
        }

        every { connectedRef.addValueEventListener(capture(listenerSlot)) } returns mockk()
        every { connectedRef.removeEventListener(any<ValueEventListener>()) } just Runs

        // setValueTask: complete immediately for goOffline's await()
        every { setValueTask.isComplete } returns true
        every { setValueTask.isCanceled } returns false
        every { setValueTask.exception } returns null
        every { setValueTask.result } returns null

        source = RealtimePresenceSource(database)
    }

    // ── Happy path ──────────────────────────────────────────────────────────────

    @Test
    fun `startPresence registers onDisconnect and sets online when connected`() {
        val setValueArgs = mutableListOf<Any?>()
        every { presenceRef.setValue(any()) } answers { setValueArgs.add(firstArg()); setValueTask }

        source.startPresence("uid1")
        simulateConnected()

        verify { mockOnDisconnect.setValue(any()) }
        assertTrue("Expected an online setValue call",
            setValueArgs.any { (it as? Map<*, *>)?.get("isOnline") == true })
    }

    @Test
    fun `startPresence does nothing when disconnected`() {
        source.startPresence("uid1")
        simulateDisconnected()

        verify(exactly = 0) { mockOnDisconnect.setValue(any()) }
        verify(exactly = 0) { presenceRef.setValue(any()) }
    }

    // ── Bug 1 fix: handler re-registered on every reconnect ──────────────────

    @Test
    fun `startPresence re-registers onDisconnect on reconnect`() {
        source.startPresence("uid1")

        simulateConnected()
        simulateDisconnected()
        simulateConnected() // second connection

        verify(exactly = 2) { mockOnDisconnect.setValue(any()) }
    }

    // ── Bug 2 fix: goOffline does not cancel onDisconnect ────────────────────

    @Test
    fun `goOffline sets offline without cancelling onDisconnect`() = runTest {
        val setValueArgs = mutableListOf<Any?>()
        every { presenceRef.setValue(any()) } answers { setValueArgs.add(firstArg()); setValueTask }

        source.goOffline("uid1")

        assertTrue("Expected an offline setValue call",
            setValueArgs.any { (it as? Map<*, *>)?.get("isOnline") == false })
        verify(exactly = 0) { mockOnDisconnect.cancel() }
    }

    // ── Cleanup ──────────────────────────────────────────────────────────────

    @Test
    fun `stopPresence removes the connected listener`() {
        source.startPresence("uid1")
        source.stopPresence()

        verify { connectedRef.removeEventListener(any<ValueEventListener>()) }
    }

    // ── Idempotency ───────────────────────────────────────────────────────────

    @Test
    fun `startPresence is idempotent for same userId`() {
        source.startPresence("uid1")
        source.startPresence("uid1") // second call — same user

        verify(exactly = 1) { connectedRef.addValueEventListener(any()) }
    }

    @Test
    fun `startPresence replaces listener for different userId`() {
        val presenceRef2 = mockk<DatabaseReference>(relaxed = true)
        val onDisconnect2 = mockk<OnDisconnect>(relaxed = true)
        val task2 = mockk<Task<Void>>(relaxed = true)
        every { database.getReference("presence/uid2") } returns presenceRef2
        every { presenceRef2.onDisconnect() } returns onDisconnect2
        every { onDisconnect2.setValue(any()) } returns task2
        every { task2.addOnSuccessListener(any()) } answers {
            (firstArg<Any>() as OnSuccessListener<Void?>).onSuccess(null)
            task2
        }

        source.startPresence("uid1")
        source.startPresence("uid2") // different user

        // Old listener removed, new listener registered
        verify { connectedRef.removeEventListener(any<ValueEventListener>()) }
        verify(exactly = 2) { connectedRef.addValueEventListener(any()) }
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private fun simulateConnected() {
        val snapshot = mockk<DataSnapshot>()
        every { snapshot.getValue(Boolean::class.java) } returns true
        listenerSlot.captured.onDataChange(snapshot)
    }

    private fun simulateDisconnected() {
        val snapshot = mockk<DataSnapshot>()
        every { snapshot.getValue(Boolean::class.java) } returns false
        listenerSlot.captured.onDataChange(snapshot)
    }
}
