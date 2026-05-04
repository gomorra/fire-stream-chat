package com.firestream.chat.ui.chat

import com.firestream.chat.domain.model.Message
import com.firestream.chat.domain.model.MessageType
import com.firestream.chat.domain.model.TimerState
import org.junit.Assert.assertEquals
import org.junit.Test

class TimerCountdownTest {

    private fun timerMessage(
        startedAtMs: Long? = 1_000_000L,
        durationMs: Long? = 30_000L,
        state: TimerState? = TimerState.RUNNING,
    ) = Message(
        id = "m1",
        chatId = "c1",
        senderId = "u1",
        content = "",
        type = MessageType.TIMER,
        timerStartedAtMs = startedAtMs,
        timerDurationMs = durationMs,
        timerState = state,
    )

    @Test
    fun `computeRemainingMs returns full duration at start`() {
        val msg = timerMessage(startedAtMs = 1_000_000L, durationMs = 30_000L)
        assertEquals(30_000L, computeRemainingMs(msg, nowMs = 1_000_000L))
    }

    @Test
    fun `computeRemainingMs returns positive value mid-flight`() {
        val msg = timerMessage(startedAtMs = 1_000_000L, durationMs = 30_000L)
        assertEquals(20_000L, computeRemainingMs(msg, nowMs = 1_010_000L))
    }

    @Test
    fun `computeRemainingMs returns zero past fire time`() {
        val msg = timerMessage(startedAtMs = 1_000_000L, durationMs = 30_000L)
        assertEquals(0L, computeRemainingMs(msg, nowMs = 9_999_999L))
    }

    @Test
    fun `computeRemainingMs returns zero when startedAt is null`() {
        val msg = timerMessage(startedAtMs = null)
        assertEquals(0L, computeRemainingMs(msg, nowMs = 1_000_000L))
    }

    @Test
    fun `computeRemainingMs returns zero when duration is null`() {
        val msg = timerMessage(durationMs = null)
        assertEquals(0L, computeRemainingMs(msg, nowMs = 1_000_000L))
    }

    @Test
    fun `formatTimerDuration uses mm ss under one hour`() {
        assertEquals("00:30", formatTimerDuration(30_000L))
        assertEquals("01:05", formatTimerDuration(65_000L))
        assertEquals("59:59", formatTimerDuration(3_599_000L))
    }

    @Test
    fun `formatTimerDuration switches to hh mm ss at one hour`() {
        assertEquals("01:00:00", formatTimerDuration(3_600_000L))
        assertEquals("01:23:45", formatTimerDuration(5_025_000L))
        assertEquals("23:59:59", formatTimerDuration(86_399_000L))
    }

    @Test
    fun `formatTimerDuration clamps negative input to zero`() {
        assertEquals("00:00", formatTimerDuration(-5_000L))
    }

    @Test
    fun `formatTimerDuration treats sub-second as zero seconds`() {
        assertEquals("00:00", formatTimerDuration(500L))
    }
}
