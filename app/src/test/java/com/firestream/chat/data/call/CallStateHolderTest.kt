package com.firestream.chat.data.call

import com.firestream.chat.domain.model.CallAudioRoute
import com.firestream.chat.domain.model.CallParticipant
import com.firestream.chat.domain.model.CallState
import com.firestream.chat.domain.model.CallSurface
import com.firestream.chat.domain.model.CallUiControls
import com.firestream.chat.domain.model.EndReason
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
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

        holder.beginCall("call1", listOf(alice))

        assertEquals(CallUiControls(), holder.uiControls.value)
    }

    // ── Participants ─────────────────────────────────────────────────────────

    @Test
    fun `beginCall sets the people of the call, replacing the ones before`() {
        assertTrue(holder.participants.value.isEmpty())

        holder.beginCall("call1", listOf(alice))
        holder.beginCall("call1", listOf(bob))

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
        holder.beginCall("call1", listOf(alice, bob))

        holder.updateParticipant("user2") { it.copy(cameraOn = true, micOn = false, connected = true) }

        assertEquals(
            listOf(alice.copy(cameraOn = true, micOn = false, connected = true), bob),
            holder.participants.value
        )
    }

    @Test
    fun `updateParticipant for someone who is not in the call does nothing`() {
        holder.beginCall("call1", listOf(alice))

        holder.updateParticipant("stranger") { it.copy(cameraOn = true) }

        assertEquals(listOf(alice), holder.participants.value)
    }

    @Test
    fun `setFramed marks who has a frame and clears everyone else`() {
        holder.beginCall("call1", listOf(alice, bob))

        holder.setFramed(setOf("user2", CallVideoSinks.LOCAL))
        assertEquals(listOf(alice.copy(hasFrame = true), bob), holder.participants.value)

        holder.setFramed(setOf("user3"))
        assertEquals(listOf(alice, bob.copy(hasFrame = true)), holder.participants.value)

        holder.setFramed(emptySet())
        assertEquals(listOf(alice, bob), holder.participants.value)
    }

    @Test
    fun `reset clears the participants`() {
        holder.beginCall("call1", listOf(alice))

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

    // ── The call's chat ─────────────────────────────────────────────────────

    @Test
    fun `the caller's call knows its chat from the start`() {
        holder.beginCall("call1", listOf(alice), chatId = "chat1")

        assertEquals("chat1", holder.chatId.value)
    }

    @Test
    fun `the side that answers learns the chat later`() {
        holder.beginCall("call1", listOf(alice))
        assertNull(holder.chatId.value)

        holder.setChatId("call1", "chat1")

        assertEquals("chat1", holder.chatId.value)
    }

    @Test
    fun `a chat that is found after the next call began does not reach that call`() {
        holder.beginCall("call1", listOf(alice))
        holder.beginCall("call2", listOf(bob), chatId = "chat2")

        holder.setChatId("call1", "chat1")

        assertEquals("chat2", holder.chatId.value)
    }

    @Test
    fun `the next call does not inherit the chat of the call before`() {
        holder.beginCall("call1", listOf(alice), chatId = "chat1")
        holder.beginCall("call2", listOf(bob))

        assertNull(holder.chatId.value)
    }

    // ── On screen ───────────────────────────────────────────────────────────

    private fun TestScope.collectOnScreen(): List<Boolean> {
        val seen = mutableListOf<Boolean>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { holder.onScreen.collect { seen += it } }
        return seen
    }

    @Test
    fun `the stage hands over to the docked card without the call leaving the screen`() = runTest {
        holder.setSurfaceShowing(CallSurface.STAGE, true)
        val seen = collectOnScreen()

        // The stage stops, and the card shows a moment later.
        holder.setSurfaceShowing(CallSurface.STAGE, false)
        advanceTimeBy(CallStateHolder.OFF_SCREEN_GRACE_MILLIS - 1)
        holder.setSurfaceShowing(CallSurface.DOCK, true)
        advanceTimeBy(10 * CallStateHolder.OFF_SCREEN_GRACE_MILLIS)

        assertEquals(listOf(true), seen)
    }

    @Test
    fun `the call is off screen one second after the last surface left`() = runTest {
        holder.setSurfaceShowing(CallSurface.DOCK, true)
        val seen = collectOnScreen()

        holder.setSurfaceShowing(CallSurface.DOCK, false)
        advanceTimeBy(CallStateHolder.OFF_SCREEN_GRACE_MILLIS - 1)
        assertEquals(listOf(true), seen)

        advanceTimeBy(2)
        assertEquals(listOf(true, false), seen)
    }

    @Test
    fun `both surfaces at once count as on screen until the last one leaves`() = runTest {
        holder.setSurfaceShowing(CallSurface.STAGE, true)
        holder.setSurfaceShowing(CallSurface.DOCK, true)
        val seen = collectOnScreen()

        holder.setSurfaceShowing(CallSurface.DOCK, false)
        advanceTimeBy(10 * CallStateHolder.OFF_SCREEN_GRACE_MILLIS)
        assertEquals(listOf(true), seen)

        holder.setSurfaceShowing(CallSurface.STAGE, false)
        advanceTimeBy(CallStateHolder.OFF_SCREEN_GRACE_MILLIS + 1)
        assertEquals(listOf(true, false), seen)
    }

    @Test
    fun `a surface that comes back shows the call again at once`() = runTest {
        val seen = collectOnScreen()
        advanceTimeBy(CallStateHolder.OFF_SCREEN_GRACE_MILLIS + 1)
        assertEquals(listOf(false), seen)

        holder.setSurfaceShowing(CallSurface.STAGE, true)
        runCurrent()

        assertEquals(listOf(false, true), seen)
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
