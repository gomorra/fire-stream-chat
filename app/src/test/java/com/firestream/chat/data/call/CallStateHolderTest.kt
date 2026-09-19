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
    fun `updateAudioRoutes sets the route and lists routes in display order`() {
        holder.toggleMute()

        holder.updateAudioRoutes(
            setOf(CallAudioRoute.WIRED_HEADSET, CallAudioRoute.BLUETOOTH, CallAudioRoute.SPEAKER, CallAudioRoute.EARPIECE),
            CallAudioRoute.BLUETOOTH,
        )

        val controls = holder.uiControls.value
        assertEquals(CallAudioRoute.BLUETOOTH, controls.audioRoute)
        assertEquals(
            listOf(CallAudioRoute.EARPIECE, CallAudioRoute.SPEAKER, CallAudioRoute.BLUETOOTH, CallAudioRoute.WIRED_HEADSET),
            controls.availableRoutes,
        )
        assertTrue("mute is untouched", controls.isMuted)
    }

    @Test
    fun `default controls are a phone on the earpiece`() {
        val controls = holder.uiControls.value
        assertEquals(CallAudioRoute.EARPIECE, controls.audioRoute)
        assertEquals(listOf(CallAudioRoute.EARPIECE, CallAudioRoute.SPEAKER), controls.availableRoutes)
    }

    @Test
    fun `reset returns to Idle with default controls`() {
        holder.updateState(CallState.Connected("call1", "user2", "Alice", null, 1000L))
        holder.toggleMute()
        holder.updateAudioRoutes(listOf(CallAudioRoute.SPEAKER), CallAudioRoute.SPEAKER)

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
