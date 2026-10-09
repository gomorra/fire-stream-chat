package com.firestream.chat.data.call

import com.firestream.chat.domain.model.CallAudioRoute
import com.firestream.chat.domain.model.CallParticipant
import com.firestream.chat.domain.model.CallState
import com.firestream.chat.domain.model.CallSurface
import com.firestream.chat.domain.model.CallUiControls
import com.firestream.chat.domain.model.EndReason
import com.firestream.chat.domain.model.isOngoing
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
import java.util.concurrent.CyclicBarrier
import kotlin.concurrent.thread

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

    // A late "answered" must not undo a connect. The call's state has one thread now, so the rule
    // is CallSession's: see `an answer that arrives after the connect does not take the call back`
    // in CallSessionTest.

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
    fun `placing a call clears the people and the chat of the call before`() {
        holder.beginCall("call1", listOf(alice), chatId = "chat1")
        holder.updateState(CallState.Ended("call1", EndReason.HANGUP))

        holder.startPlacing("user3", "Bob", null)

        assertTrue(holder.participants.value.isEmpty())
        assertNull(holder.chatId.value)
    }

    @Test
    fun `a placing carries how the call was started`() {
        assertEquals(true, holder.startPlacing("user2", "Alice", null, video = true)?.video)
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
    fun `a new call starts unmuted after a call that ended muted`() {
        holder.updateState(CallState.Connected("call1", "user2", "Alice", null, 1000L))
        holder.toggleMute()
        holder.updateAudioRoutes(listOf(CallAudioRoute.EARPIECE, CallAudioRoute.SPEAKER), CallAudioRoute.SPEAKER)
        holder.updateState(CallState.Ended("call1", EndReason.HANGUP))

        // The next call's audio track is created enabled, so its controls must say unmuted.
        holder.updateState(CallState.OutgoingRinging("call2", "user3", "Bob", null))

        assertEquals(CallUiControls(), holder.uiControls.value)
    }

    @Test
    fun `a new incoming call starts unmuted when the previous state was Idle`() {
        holder.toggleMute()

        holder.updateState(CallState.IncomingRinging("call1", "user2", "Alice", null))

        assertFalse(holder.uiControls.value.isMuted)
    }

    @Test
    fun `controls survive the state changes within one call`() {
        holder.updateState(CallState.OutgoingRinging("call1", "user2", "Alice", null))
        holder.toggleMute()
        holder.updateAudioRoutes(listOf(CallAudioRoute.EARPIECE, CallAudioRoute.SPEAKER), CallAudioRoute.SPEAKER)

        holder.updateState(CallState.Connecting("call1", "user2", "Alice", null))
        holder.updateState(CallState.Connected("call1", "user2", "Alice", null, 1000L))

        assertTrue(holder.uiControls.value.isMuted)
        assertEquals(CallAudioRoute.SPEAKER, holder.uiControls.value.audioRoute)
    }

    @Test
    fun `route updates racing mute toggles never lose a toggle`() {
        holder.updateState(CallState.Connected("call1", "user2", "Alice", null, 1000L))
        val routes = listOf(CallAudioRoute.EARPIECE, CallAudioRoute.SPEAKER)

        // The holder is a singleton with no threading contract. With toggles and route updates on
        // two threads, an even number of toggles must always land back on unmuted.
        repeat(ROUNDS) { round ->
            val start = CyclicBarrier(2)
            val routeWriter = thread {
                start.await()
                repeat(RACING_WRITES) { holder.updateAudioRoutes(routes, CallAudioRoute.SPEAKER) }
            }
            start.await()
            repeat(RACING_WRITES) { holder.toggleMute() }
            routeWriter.join()

            assertFalse("round $round lost a mute toggle", holder.uiControls.value.isMuted)
        }
    }

    @Test
    fun `preparing an outgoing call forgets the call that ended`() {
        holder.updateState(CallState.Connected("call1", "user2", "Alice", null, 1000L))
        holder.updateState(CallState.Ended("call1", EndReason.REMOTE_HANGUP))

        // The call screen finishes itself 1.5 s after it sees Ended. The new call's screen must not.
        assertTrue(holder.prepareOutgoingCall())

        assertEquals(CallState.Idle, holder.callState.value)
    }

    @Test
    fun `a prepared call is not ongoing, so a call that comes in during the microphone prompt rings`() {
        holder.prepareOutgoingCall()

        assertFalse(holder.callState.value.isOngoing)
    }

    @Test
    fun `preparing an outgoing call is refused while a call is ongoing`() {
        val ongoing = CallState.Connected("call1", "user2", "Alice", null, 1000L)
        holder.updateState(ongoing)
        holder.toggleMute()

        assertFalse(holder.prepareOutgoingCall())

        assertEquals(ongoing, holder.callState.value)
        assertTrue(holder.uiControls.value.isMuted)
    }

    @Test
    fun `placing a call publishes it with fresh controls`() {
        holder.toggleMute()

        val placing = holder.startPlacing("user2", "Alice", null)

        assertEquals(placing, holder.callState.value)
        assertEquals("user2", placing?.calleeId)
        assertEquals(CallUiControls(), holder.uiControls.value)
    }

    @Test
    fun `a second call cannot be placed while one is being placed`() {
        val first = holder.startPlacing("user2", "Alice", null)

        assertNull(holder.startPlacing("user3", "Bob", null))

        assertEquals(first, holder.callState.value)
    }

    @Test
    fun `the service takes over only the placing of its own call`() {
        val placing = holder.startPlacing("user2", "Alice", null)!!
        assertTrue(holder.placingCreated(placing.placingId, "call1"))
        val other = CallState.OutgoingRinging("call2", "user2", "Alice", null)
        val ringing = CallState.OutgoingRinging("call1", "user2", "Alice", null)

        assertFalse(holder.takeOverPlacing(other))
        assertTrue(holder.takeOverPlacing(ringing))

        assertEquals(ringing, holder.callState.value)
    }

    @Test
    fun `a placing gets one document`() {
        val placing = holder.startPlacing("user2", "Alice", null)!!

        assertTrue(holder.placingCreated(placing.placingId, "call1"))
        assertFalse(holder.placingCreated(placing.placingId, "call2"))

        assertEquals(placing.copy(callId = "call1"), holder.callState.value)
    }

    @Test
    fun `a cancelled placing ends as a hang-up, says which call it had, and cannot be taken over`() {
        val placing = holder.startPlacing("user2", "Alice", null)!!
        holder.placingCreated(placing.placingId, "call1")

        assertEquals(placing.copy(callId = "call1"), holder.cancelPlacing(placing.placingId))

        assertEquals(CallState.Ended("call1", EndReason.HANGUP), holder.callState.value)
        assertFalse(holder.placingCreated(placing.placingId, "call1"))
        assertFalse(holder.takeOverPlacing(CallState.OutgoingRinging("call1", "user2", "Alice", null)))
    }

    @Test
    fun `an old placing never touches the next one`() {
        // Cancel the call to Alice while its document is created, then call Bob at once.
        val toAlice = holder.startPlacing("user2", "Alice", null)!!
        holder.cancelPlacing(toAlice.placingId)
        holder.prepareOutgoingCall()
        val toBob = holder.startPlacing("user3", "Bob", null)!!

        assertFalse(holder.placingCreated(toAlice.placingId, "callAlice"))
        assertFalse(holder.failPlacing(toAlice.placingId))
        assertNull(holder.cancelPlacing(toAlice.placingId))

        assertEquals(toBob, holder.callState.value)
    }

    @Test
    fun `a failed placing ends the screen that waits for it`() {
        val placing = holder.startPlacing("user2", "Alice", null)!!

        assertTrue(holder.failPlacing(placing.placingId))

        assertEquals(CallState.Ended("", EndReason.ERROR), holder.callState.value)
    }

    @Test
    fun `a failed placing leaves alone a call that is not being placed`() {
        val placing = holder.startPlacing("user2", "Alice", null)!!
        val ringing = CallState.IncomingRinging("call9", "user3", "Bob", null)
        holder.updateState(ringing)

        assertFalse(holder.failPlacing(placing.placingId))
        assertNull(holder.cancelPlacing(placing.placingId))

        assertEquals(ringing, holder.callState.value)
    }

    @Test
    fun `exactly one of the service and a Cancel wins a placing`() {
        repeat(ROUNDS) { round ->
            val callId = "call$round"
            holder.prepareOutgoingCall()
            val placing = holder.startPlacing("user2", "Alice", null)!!
            holder.placingCreated(placing.placingId, callId)
            val ringing = CallState.OutgoingRinging(callId, "user2", "Alice", null)
            val start = CyclicBarrier(2)
            var tookOver = false
            val service = thread {
                start.await()
                tookOver = holder.takeOverPlacing(ringing)
            }
            start.await()
            val cancelled = holder.cancelPlacing(placing.placingId) != null
            service.join()

            assertTrue("round $round: one side must win, and only one", tookOver != cancelled)
            val expected = if (tookOver) ringing else CallState.Ended(callId, EndReason.HANGUP)
            assertEquals(expected, holder.callState.value)
            holder.updateState(CallState.Ended(callId, EndReason.HANGUP))
        }
    }

    @Test
    fun `toggleMute returns the state it set`() {
        assertTrue(holder.toggleMute())
        assertFalse(holder.toggleMute())
    }

    @Test
    fun `updateControls can replace the controls as a whole`() {
        holder.updateControls { CallUiControls(isMuted = true, audioRoute = CallAudioRoute.SPEAKER) }
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

    private companion object {
        const val ROUNDS = 20
        // Even, so a round that loses no toggle ends unmuted.
        const val RACING_WRITES = 20_000
    }
}
