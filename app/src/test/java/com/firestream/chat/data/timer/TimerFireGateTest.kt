package com.firestream.chat.data.timer

import com.firestream.chat.domain.model.TimerState
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException

/**
 * Regression cover for a timer deleted for everyone still ringing: the alarm
 * lives in AlarmManager, and only `ChatTimerReactor` — which runs while that chat
 * is open — used to cancel it. The fire-time check is the backstop.
 */
class TimerFireGateTest {

    @Test
    fun `a timer deleted for everyone does not ring`() = runTest {
        assertFalse(TimerFireGate.shouldRing { TimerRow(TimerState.RUNNING, deleted = true) })
    }

    @Test
    fun `a running timer rings`() = runTest {
        assertTrue(TimerFireGate.shouldRing { TimerRow(TimerState.RUNNING, deleted = false) })
    }

    @Test
    fun `a paused or cancelled timer does not ring`() = runTest {
        assertFalse(TimerFireGate.shouldRing { TimerRow(TimerState.PAUSED, deleted = false) })
        assertFalse(TimerFireGate.shouldRing { TimerRow(TimerState.CANCELLED, deleted = false) })
    }

    @Test
    fun `a completed timer still rings — the other phone may have fired a moment earlier`() = runTest {
        assertTrue(TimerFireGate.shouldRing { TimerRow(TimerState.COMPLETED, deleted = false) })
    }

    @Test
    fun `a timer missing from the local database still rings`() = runTest {
        assertTrue(TimerFireGate.shouldRing { null })
    }

    @Test
    fun `a lookup that never answers rings after the timeout instead of hanging`() = runTest {
        val ring = TimerFireGate.shouldRing { awaitCancellation() }

        assertTrue(ring)
        assertEquals(TimerFireGate.LOOKUP_TIMEOUT_MS, currentTime)
    }

    @Test
    fun `a lookup that fails still rings`() = runTest {
        assertTrue(TimerFireGate.shouldRing { throw IOException("db unavailable") })
    }
}
