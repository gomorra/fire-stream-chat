package com.firestream.chat.domain.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class CallStateTest {

    @Test
    fun `only placing, ringing, connecting and connected calls are ongoing`() {
        val expected = mapOf(
            CallState.Idle to false,
            CallState.Placing("u2", "Alice", null) to true,
            CallState.Placing("u2", "Alice", null, callId = "c1") to true,
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

    @Test
    fun `an end reason reads back from its wire name, whatever its case`() {
        EndReason.entries.forEach { reason ->
            assertEquals(reason, EndReason.fromWireName(reason.wireName))
            assertEquals(reason, EndReason.fromWireName(reason.name))
        }
        assertEquals("remote_hangup", EndReason.REMOTE_HANGUP.wireName)
    }

    @Test
    fun `an end reason this version does not know reads as null`() {
        assertNull(EndReason.fromWireName("busy"))
        assertNull(EndReason.fromWireName(""))
        assertNull(EndReason.fromWireName(null))
    }
}
