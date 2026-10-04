package com.firestream.chat.data.call

import com.firestream.chat.domain.model.CallAudioRoute
import com.firestream.chat.domain.model.CallParticipant
import com.firestream.chat.domain.model.CallState
import com.firestream.chat.domain.model.CallUiControls
import com.firestream.chat.domain.model.EndReason
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class CallStateHolderTest {

    private lateinit var holder: CallStateHolder

    private val alice = CallParticipant("user2", "Alice", null)
    private val bob = CallParticipant("user3", "Bob", "https://example.com/bob.jpg")

    @Before
    fun setUp() {
        holder = CallStateHolder()
    }

    @Test
    fun `initial state is Idle`() {
        assertTrue(holder.callState.value is CallState.Idle)
        assertEquals(CallUiControls(), holder.uiControls.value)
    }

    @Test
    fun `updateState sets new state`() {
        val state = CallState.OutgoingRinging("call1", "user2", "Alice", null)
        holder.updateState(state)
        assertEquals(state, holder.callState.value)
    }

    @Test
    fun `compareAndSetState moves on only from the expected state`() {
        val ringing = CallState.OutgoingRinging("call1", "user2", "Alice", null)
        val connecting = CallState.Connecting("call1", "user2", "Alice", null)
        val connected = CallState.Connected("call1", "user2", "Alice", null, 1L)
        holder.updateState(ringing)

        assertTrue(holder.compareAndSetState(ringing, connecting))
        assertEquals(connecting, holder.callState.value)

        // A late writer that still expects "ringing" must not undo a connect.
        holder.updateState(connected)
        assertFalse(holder.compareAndSetState(ringing, connecting))
        assertEquals(connected, holder.callState.value)
    }

    @Test
    fun `markVideo flips the kind of the current call in every live state`() {
        val live = listOf(
            CallState.OutgoingRinging("call1", "user2", "Alice", null),
            CallState.IncomingRinging("call1", "user2", "Alice", null),
            CallState.Connecting("call1", "user2", "Alice", null),
            CallState.Connected("call1", "user2", "Alice", null, 1L),
        )
        val expected = listOf(
            CallState.OutgoingRinging("call1", "user2", "Alice", null, video = true),
            CallState.IncomingRinging("call1", "user2", "Alice", null, video = true),
            CallState.Connecting("call1", "user2", "Alice", null, video = true),
            CallState.Connected("call1", "user2", "Alice", null, 1L, video = true),
        )

        val marked = live.map { state ->
            holder.updateState(state)
            holder.markVideo("call1").also { assertEquals(it, holder.callState.value) }
        }

        assertEquals(expected, marked)
    }

    @Test
    fun `markVideo leaves another call, an ended call and no call alone`() {
        val other = CallState.IncomingRinging("call2", "user3", "Bob", null)
        val ended = CallState.Ended("call1", EndReason.HANGUP)

        for (state in listOf(other, ended, CallState.Idle)) {
            holder.updateState(state)
            assertEquals(state, holder.markVideo("call1"))
            assertEquals(state, holder.callState.value)
        }
    }

    @Test
    fun `toggleMute flips isMuted and says what it is now`() {
        assertFalse(holder.uiControls.value.isMuted)
        assertTrue(holder.toggleMute())
        assertTrue(holder.uiControls.value.isMuted)
        assertFalse(holder.toggleMute())
        assertFalse(holder.uiControls.value.isMuted)
    }

    // ── A call's own controls ────────────────────────────────────────────────

    @Test
    fun `the camera starts off, on the front camera, with video available`() {
        val controls = holder.uiControls.value

        assertFalse(controls.cameraOn)
        assertFalse(controls.cameraPaused)
        assertTrue(controls.frontCamera)
        // Until the other side turns out to be an older app, so the camera can go on while it rings.
        assertTrue(controls.videoAvailable)
    }

    @Test
    fun `updateControls changes only what the change names`() {
        holder.toggleMute()

        holder.updateControls { it.copy(cameraOn = true, cameraPaused = true) }

        val controls = holder.uiControls.value
        assertTrue(controls.cameraOn)
        assertTrue(controls.cameraPaused)
        assertTrue(controls.isMuted)
    }

    // Regression: nothing reset the controls between calls, so a call muted before it ended left
    // the next one showing "muted" over an open microphone.
    @Test
    fun `beginCall does not carry the controls of the call before into the next one`() {
        holder.toggleMute()
        holder.updateControls { it.copy(cameraOn = true, frontCamera = false, videoAvailable = false) }
        holder.updateAudioRoutes(listOf(CallAudioRoute.SPEAKER), CallAudioRoute.SPEAKER)

        holder.beginCall(listOf(alice))

        assertEquals(CallUiControls(), holder.uiControls.value)
    }

    // ── Participants ─────────────────────────────────────────────────────────

    @Test
    fun `beginCall sets the people of the call, replacing the ones before`() {
        assertTrue(holder.participants.value.isEmpty())

        holder.beginCall(listOf(alice))
        holder.beginCall(listOf(bob))

        assertEquals(listOf(bob), holder.participants.value)
    }

    @Test
    fun `a participant starts with the camera off, the microphone on, not connected, no frame`() {
        assertFalse(alice.cameraOn)
        assertTrue(alice.micOn)
        assertFalse(alice.connected)
        assertFalse(alice.hasFrame)
    }

    @Test
    fun `updateParticipant changes that participant and nobody else`() {
        holder.beginCall(listOf(alice, bob))

        holder.updateParticipant("user2") { it.copy(cameraOn = true, micOn = false, connected = true) }

        assertEquals(
            listOf(alice.copy(cameraOn = true, micOn = false, connected = true), bob),
            holder.participants.value
        )
    }

    @Test
    fun `updateParticipant for someone who is not in the call does nothing`() {
        holder.beginCall(listOf(alice))

        holder.updateParticipant("stranger") { it.copy(cameraOn = true) }

        assertEquals(listOf(alice), holder.participants.value)
    }

    @Test
    fun `setFramed marks who has a frame and clears everyone else`() {
        holder.beginCall(listOf(alice, bob))

        holder.setFramed(setOf("user2", CallVideoSinks.LOCAL))
        assertEquals(listOf(alice.copy(hasFrame = true), bob), holder.participants.value)

        holder.setFramed(setOf("user3"))
        assertEquals(listOf(alice, bob.copy(hasFrame = true)), holder.participants.value)

        holder.setFramed(emptySet())
        assertEquals(listOf(alice, bob), holder.participants.value)
    }

    @Test
    fun `reset clears the participants`() {
        holder.beginCall(listOf(alice))

        holder.reset()

        assertTrue(holder.participants.value.isEmpty())
    }

    @Test
    fun `updateAudioRoutes publishes the available routes and the active one`() {
        assertEquals(CallAudioRoute.EARPIECE, holder.uiControls.value.audioRoute)

        holder.updateAudioRoutes(
            listOf(CallAudioRoute.EARPIECE, CallAudioRoute.SPEAKER, CallAudioRoute.BLUETOOTH),
            CallAudioRoute.BLUETOOTH
        )

        assertEquals(CallAudioRoute.BLUETOOTH, holder.uiControls.value.audioRoute)
        assertEquals(
            listOf(CallAudioRoute.EARPIECE, CallAudioRoute.SPEAKER, CallAudioRoute.BLUETOOTH),
            holder.uiControls.value.availableRoutes
        )
    }

    @Test
    fun `updateAudioRoutes with no reported route keeps the displayed one`() {
        holder.updateAudioRoutes(
            listOf(CallAudioRoute.EARPIECE, CallAudioRoute.SPEAKER, CallAudioRoute.BLUETOOTH),
            CallAudioRoute.BLUETOOTH
        )

        // The OS stopped reporting a communication device; the list still updates.
        holder.updateAudioRoutes(listOf(CallAudioRoute.EARPIECE, CallAudioRoute.SPEAKER), null)

        assertEquals(CallAudioRoute.BLUETOOTH, holder.uiControls.value.audioRoute)
        assertEquals(
            listOf(CallAudioRoute.EARPIECE, CallAudioRoute.SPEAKER),
            holder.uiControls.value.availableRoutes
        )
    }

    @Test
    fun `updateAudioRoutes leaves the mute state alone`() {
        holder.toggleMute()

        holder.updateAudioRoutes(listOf(CallAudioRoute.EARPIECE), CallAudioRoute.EARPIECE)

        assertTrue(holder.uiControls.value.isMuted)
    }

    @Test
    fun `reset returns to Idle with default controls`() {
        holder.updateState(CallState.Connected("call1", "user2", "Alice", null, 1000L))
        holder.toggleMute()
        holder.updateAudioRoutes(
            listOf(CallAudioRoute.EARPIECE, CallAudioRoute.SPEAKER),
            CallAudioRoute.SPEAKER
        )

        holder.reset()

        assertTrue(holder.callState.value is CallState.Idle)
        assertEquals(CallUiControls(), holder.uiControls.value)
    }

    @Test
    fun `updateControls sets controls directly`() {
        holder.updateControls(CallUiControls(isMuted = true, audioRoute = CallAudioRoute.SPEAKER))
        assertTrue(holder.uiControls.value.isMuted)
        assertEquals(CallAudioRoute.SPEAKER, holder.uiControls.value.audioRoute)
    }

    @Test
    fun `state transitions through full call lifecycle`() = runTest {
        // Idle -> OutgoingRinging
        holder.updateState(CallState.OutgoingRinging("c1", "u2", "Bob", null))
        assertTrue(holder.callState.value is CallState.OutgoingRinging)

        // -> Connecting
        holder.updateState(CallState.Connecting("c1", "u2", "Bob", null))
        assertTrue(holder.callState.value is CallState.Connecting)

        // -> Connected
        holder.updateState(CallState.Connected("c1", "u2", "Bob", null, System.currentTimeMillis()))
        assertTrue(holder.callState.value is CallState.Connected)

        // -> Ended
        holder.updateState(CallState.Ended("c1", EndReason.HANGUP))
        val ended = holder.callState.value as CallState.Ended
        assertEquals(EndReason.HANGUP, ended.reason)
    }
}
