package com.firestream.chat.domain.model

import org.junit.Assert.assertEquals
import org.junit.Test

class CallStateTest {

    @Test
    fun `only ringing, connecting and connected calls are ongoing`() {
        val expected = mapOf(
            CallState.Idle to false,
            CallState.OutgoingRinging("c1", "u2", "Alice", null) to true,
            CallState.IncomingRinging("c1", "u2", "Alice", null) to true,
            CallState.Connecting("c1", "u2", "Alice", null) to true,
            CallState.Connected("c1", "u2", "Alice", null, startTime = 1_000L) to true,
            CallState.Ended("c1", EndReason.HANGUP) to false,
        )

        expected.forEach { (state, ongoing) ->
            assertEquals("isOngoing of $state", ongoing, state.isOngoing)
        }
    }
}
