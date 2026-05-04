package com.firestream.chat.data.timer

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class BootRestoreLogicTest {

    private val now = 1_700_000_000_000L

    @Test
    fun `future fire time produces Schedule action`() {
        val action = BootRestoreLogic.classify(
            messageId = "m1",
            chatId = "c1",
            caption = "Pizza",
            timerStartedAtMs = now - 30_000L,
            timerDurationMs = 60_000L,
            nowMs = now,
        )
        assertEquals(
            TimerBootAction.Schedule(
                messageId = "m1",
                chatId = "c1",
                caption = "Pizza",
                fireAtMs = now + 30_000L,
            ),
            action,
        )
    }

    @Test
    fun `past fire time produces MarkCompleted action — no notification`() {
        val action = BootRestoreLogic.classify(
            messageId = "m2",
            chatId = "c2",
            caption = null,
            timerStartedAtMs = now - 120_000L,
            timerDurationMs = 60_000L,
            nowMs = now,
        )
        assertEquals(TimerBootAction.MarkCompleted("m2", "c2"), action)
    }

    @Test
    fun `fire-time exactly equal to now produces MarkCompleted not Schedule`() {
        // Edge case: a re-arm of an already-fired alarm would no-op anyway, so
        // marking completed is the safer branch.
        val action = BootRestoreLogic.classify(
            messageId = "m3",
            chatId = "c3",
            caption = null,
            timerStartedAtMs = now - 60_000L,
            timerDurationMs = 60_000L,
            nowMs = now,
        )
        assertEquals(TimerBootAction.MarkCompleted("m3", "c3"), action)
    }

    @Test
    fun `null timerStartedAtMs is Skip`() {
        val action = BootRestoreLogic.classify(
            messageId = "m4",
            chatId = "c4",
            caption = null,
            timerStartedAtMs = null,
            timerDurationMs = 60_000L,
            nowMs = now,
        )
        assertEquals(TimerBootAction.Skip, action)
    }

    @Test
    fun `null timerDurationMs is Skip`() {
        val action = BootRestoreLogic.classify(
            messageId = "m5",
            chatId = "c5",
            caption = null,
            timerStartedAtMs = now,
            timerDurationMs = null,
            nowMs = now,
        )
        assertEquals(TimerBootAction.Skip, action)
    }

    @Test
    fun `zero or negative duration is Skip`() {
        listOf(0L, -1L, -1_000L).forEach { d ->
            val action = BootRestoreLogic.classify(
                messageId = "m6",
                chatId = "c6",
                caption = null,
                timerStartedAtMs = now,
                timerDurationMs = d,
                nowMs = now,
            )
            assertEquals("duration=$d", TimerBootAction.Skip, action)
        }
    }

    @Test
    fun `caption flows through to Schedule action`() {
        val action = BootRestoreLogic.classify(
            messageId = "m7",
            chatId = "c7",
            caption = "Tea brewing",
            timerStartedAtMs = now,
            timerDurationMs = 30_000L,
            nowMs = now,
        )
        assertTrue(action is TimerBootAction.Schedule)
        assertEquals("Tea brewing", (action as TimerBootAction.Schedule).caption)
    }
}
