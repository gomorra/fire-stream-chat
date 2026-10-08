package com.firestream.chat.data.call

import com.firestream.chat.domain.model.CallAudioRoute
import com.firestream.chat.domain.model.CallState
import com.firestream.chat.domain.model.CallUiControls
import com.firestream.chat.domain.model.EndReason
import com.firestream.chat.domain.model.isOngoing
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.util.concurrent.CyclicBarrier
import kotlin.concurrent.thread

class CallStateHolderTest {

    private lateinit var holder: CallStateHolder

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
    fun `toggleMute flips isMuted`() {
        assertFalse(holder.uiControls.value.isMuted)
        holder.toggleMute()
        assertTrue(holder.uiControls.value.isMuted)
        holder.toggleMute()
        assertFalse(holder.uiControls.value.isMuted)
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

    private companion object {
        const val ROUNDS = 20
        // Even, so a round that loses no toggle ends unmuted.
        const val RACING_WRITES = 20_000
    }
}
