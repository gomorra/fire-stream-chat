package com.firestream.chat.data.call

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
    fun `toggleSpeaker flips isSpeakerOn`() {
        assertFalse(holder.uiControls.value.isSpeakerOn)
        holder.toggleSpeaker()
        assertTrue(holder.uiControls.value.isSpeakerOn)
        holder.toggleSpeaker()
        assertFalse(holder.uiControls.value.isSpeakerOn)
    }

    @Test
    fun `reset returns to Idle with default controls`() {
        holder.updateState(CallState.Connected("call1", "user2", "Alice", null, 1000L))
        holder.toggleMute()
        holder.toggleSpeaker()

        holder.reset()

        assertTrue(holder.callState.value is CallState.Idle)
        assertEquals(CallUiControls(), holder.uiControls.value)
    }

    @Test
    fun `updateControls sets controls directly`() {
        holder.updateControls(CallUiControls(isMuted = true, isSpeakerOn = true))
        assertTrue(holder.uiControls.value.isMuted)
        assertTrue(holder.uiControls.value.isSpeakerOn)
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
