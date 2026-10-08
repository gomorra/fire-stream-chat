package com.firestream.chat.data.call

import com.firestream.chat.domain.model.CallAudioRoute
import com.firestream.chat.domain.model.CallState
import com.firestream.chat.domain.model.CallUiControls
import com.firestream.chat.domain.model.EndReason
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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
        holder.updateState(CallState.Ended("call1", EndReason.REMOTE_HANGUP))

        // The call screen finishes itself 1.5 s after it sees Ended; the new call's screen must not.
        assertTrue(holder.prepareOutgoingCall())

        assertEquals(CallState.Idle, holder.callState.value)
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
    fun `a failed outgoing call ends the screen that waits for it`() {
        holder.prepareOutgoingCall()

        holder.failOutgoingCall("call1")

        assertEquals(CallState.Ended("call1", EndReason.ERROR), holder.callState.value)
    }

    @Test
    fun `a failed outgoing call leaves alone a call that started during its setup`() {
        holder.prepareOutgoingCall()
        val ringing = CallState.IncomingRinging("call9", "user3", "Bob", null)
        holder.updateState(ringing)

        holder.failOutgoingCall("call1")

        assertEquals(ringing, holder.callState.value)
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
