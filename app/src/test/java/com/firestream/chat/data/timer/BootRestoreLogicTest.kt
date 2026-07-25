package com.firestream.chat.data.timer

import com.firestream.chat.domain.model.TimerAlarmSound
import com.firestream.chat.domain.model.TimerAlarmStyle
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
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
                otherUserId = null,
                style = TimerAlarmStyle.DEFAULT,
                sound = TimerAlarmSound.DEFAULT,
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

    // Regression: a reboot used to re-arm every timer with otherUserId = null and
    // no alarm style, so a silent timer started ringing and the notification tap
    // couldn't deep-link (MainActivity.deepLinkFromIntent requires a sender id).
    // The whole alarm context has to survive the restart.

    @Test
    fun `alarm style and sound survive the reboot`() {
        val action = BootRestoreLogic.classify(
            messageId = "m8",
            chatId = "c8",
            caption = null,
            timerStartedAtMs = now,
            timerDurationMs = 30_000L,
            nowMs = now,
            style = TimerAlarmStyle.SILENT,
            sound = TimerAlarmSound.RINGTONE,
        )

        val schedule = action as TimerBootAction.Schedule
        assertEquals(TimerAlarmStyle.SILENT, schedule.style)
        assertEquals(TimerAlarmSound.RINGTONE, schedule.sound)
    }

    @Test
    fun `deep-link partner survives the reboot`() {
        val action = BootRestoreLogic.classify(
            messageId = "m9",
            chatId = "c9",
            caption = null,
            timerStartedAtMs = now,
            timerDurationMs = 30_000L,
            nowMs = now,
            otherUserId = "them",
        )

        assertEquals("them", (action as TimerBootAction.Schedule).otherUserId)
    }

    @Test
    fun `other user resolves to the single non-self participant`() {
        assertEquals("them", BootRestoreLogic.resolveOtherUserId(listOf("me", "them"), "me"))
    }

    @Test
    fun `other user is null for a group chat`() {
        // Matches what ChatTimerReactor passes for a group, so the live path and
        // the boot path can't disagree about what a group timer deep-links to.
        assertNull(BootRestoreLogic.resolveOtherUserId(listOf("me", "them", "third"), "me"))
    }

    @Test
    fun `other user is null when the participant list has not synced`() {
        assertNull(BootRestoreLogic.resolveOtherUserId(emptyList(), "me"))
    }

    @Test
    fun `other user is null for a self-chat`() {
        assertNull(BootRestoreLogic.resolveOtherUserId(listOf("me"), "me"))
    }
}
