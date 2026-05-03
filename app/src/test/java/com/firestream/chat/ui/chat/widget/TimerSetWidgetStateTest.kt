package com.firestream.chat.ui.chat.widget

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TimerSetWidgetStateTest {

    @Test
    fun `initial state is zero and send is disabled`() {
        val s = TimerSetWidgetState()
        assertEquals(0, s.hours)
        assertEquals(0, s.minutes)
        assertEquals(0, s.seconds)
        assertEquals(0L, s.durationMs)
        assertFalse(s.isSendEnabled)
    }

    @Test
    fun `seconds only converts to ms`() {
        val s = TimerSetWidgetState()
        s.seconds = 30
        assertEquals(30_000L, s.durationMs)
        assertTrue(s.isSendEnabled)
    }

    @Test
    fun `mixed hh mm ss converts correctly`() {
        val s = TimerSetWidgetState()
        s.hours = 1
        s.minutes = 23
        s.seconds = 45
        // 1h = 3_600_000, 23m = 1_380_000, 45s = 45_000
        assertEquals(5_025_000L, s.durationMs)
    }

    @Test
    fun `max cap is 23 59 59 — sub-components clamp at boundaries`() {
        val s = TimerSetWidgetState()
        s.hours = 23
        s.minutes = 59
        s.seconds = 59
        assertEquals(TimerSetWidgetState.MAX_DURATION_MS, s.durationMs)
        assertEquals((23 * 3_600_000L) + (59 * 60_000L) + 59_000L, s.durationMs)
    }

    @Test
    fun `hours over 23 are clamped`() {
        val s = TimerSetWidgetState()
        s.hours = 99
        assertEquals(23, s.hours)
    }

    @Test
    fun `negative hours are clamped to zero`() {
        val s = TimerSetWidgetState()
        s.hours = -5
        assertEquals(0, s.hours)
    }

    @Test
    fun `minutes over 59 are clamped`() {
        val s = TimerSetWidgetState()
        s.minutes = 120
        assertEquals(59, s.minutes)
    }

    @Test
    fun `seconds over 59 are clamped`() {
        val s = TimerSetWidgetState()
        s.seconds = 75
        assertEquals(59, s.seconds)
    }

    @Test
    fun `negative minutes and seconds clamp to zero`() {
        val s = TimerSetWidgetState()
        s.minutes = -1
        s.seconds = -100
        assertEquals(0, s.minutes)
        assertEquals(0, s.seconds)
    }

    @Test
    fun `send is disabled when all components are zero`() {
        val s = TimerSetWidgetState()
        s.hours = 0; s.minutes = 0; s.seconds = 0
        assertFalse(s.isSendEnabled)
    }

    @Test
    fun `send is enabled when only one minute is set`() {
        val s = TimerSetWidgetState()
        s.minutes = 1
        assertTrue(s.isSendEnabled)
        assertEquals(60_000L, s.durationMs)
    }

    @Test
    fun `reset returns state to zero`() {
        val s = TimerSetWidgetState()
        s.hours = 5; s.minutes = 30; s.seconds = 15
        s.reset()
        assertEquals(0, s.hours)
        assertEquals(0, s.minutes)
        assertEquals(0, s.seconds)
        assertFalse(s.isSendEnabled)
    }

    // ── extractCaption helper ─────────────────────────────────────────

    @Test
    fun `extractCaption returns null when composer holds only the chip text`() {
        assertNull(extractCaption(".timer.set"))
    }

    @Test
    fun `extractCaption returns trimmed caption after the chip`() {
        assertEquals("Pizza is in", extractCaption(".timer.set Pizza is in"))
    }

    @Test
    fun `extractCaption returns null on chip plus only whitespace`() {
        assertNull(extractCaption(".timer.set   "))
    }

    @Test
    fun `extractCaption returns null on bare empty text`() {
        assertNull(extractCaption(""))
    }

    @Test
    fun `extractCaption preserves non-command leading text as caption`() {
        // Defensive: if somehow the widget mounted on non-command text, treat the
        // whole thing as the caption rather than dropping it.
        assertEquals("not a command", extractCaption("not a command"))
    }
}
