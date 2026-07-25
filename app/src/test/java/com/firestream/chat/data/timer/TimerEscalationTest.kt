package com.firestream.chat.data.timer

import com.firestream.chat.domain.model.TimerAlarmSound
import com.firestream.chat.domain.model.TimerAlarmStyle
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The escalation policy — how often a fired-but-unacknowledged alarm rings again.
 *
 * The property worth protecting here is the one that isn't obvious from reading
 * either style in isolation: **an insistent alarm must never be quieter than a
 * normal one.** It's tempting to let `FLAG_INSISTENT` carry INSISTENT on its own,
 * since the flag loops the sound — but that behaviour is unverified on real
 * hardware and varies by OEM. If it silently didn't loop, INSISTENT would ring
 * once with no follow-up while NORMAL rang three times, inverting the whole point
 * of the setting. Driving both from the re-post chain makes the flag additive.
 */
class TimerEscalationTest {

    @Test
    fun `a silent timer has nothing to escalate`() {
        assertNull(escalationFor(TimerAlarmStyle.SILENT))
    }

    @Test
    fun `insistent rings more often and more times than normal`() {
        val normal = escalationFor(TimerAlarmStyle.NORMAL)!!
        val insistent = escalationFor(TimerAlarmStyle.INSISTENT)!!

        assertTrue(
            "insistent must ring at a shorter interval than normal",
            insistent.intervalMs < normal.intervalMs,
        )
        assertTrue(
            "insistent must ring at least as many times as normal",
            insistent.maxRepeats >= normal.maxRepeats,
        )
    }

    @Test
    fun `insistent escalation exactly fills the auto-silence window`() {
        // If the chain outlasted the window the last rings would be cut off; if it
        // fell short the alarm would go quiet early while claiming two minutes.
        val insistent = escalationFor(TimerAlarmStyle.INSISTENT)!!
        val totalMs = insistent.intervalMs * (insistent.maxRepeats + 1)

        assertEquals(TimerAlarmReceiver.AUTO_SILENCE_MS, totalMs)
    }

    @Test
    fun `every audible style stops on its own`() {
        // Nothing may escalate forever — an unattended alarm has to end.
        for (style in listOf(TimerAlarmStyle.NORMAL, TimerAlarmStyle.INSISTENT)) {
            val escalation = escalationFor(style)!!
            assertTrue("style=$style", escalation.maxRepeats in 1..10)
            assertTrue("style=$style", escalation.intervalMs > 0)
        }
    }

    // ── Auto-silence window shrinks across the chain ────────────────────────

    private fun request(style: TimerAlarmStyle, attempt: Int) = TimerAlarmRequest(
        messageId = "m1",
        chatId = "c1",
        caption = null,
        otherUserId = null,
        style = style,
        sound = TimerAlarmSound.DEFAULT,
        realertAttempt = attempt,
    )

    @Test
    fun `the first insistent ring gets the whole window`() {
        assertEquals(
            TimerAlarmReceiver.AUTO_SILENCE_MS,
            request(TimerAlarmStyle.INSISTENT, attempt = 0).remainingRingMs(),
        )
    }

    @Test
    fun `each re-post shortens the window instead of restarting the clock`() {
        // Without this, every nag would hand the notification a fresh 2-minute
        // timeout and an insistent alarm would ring for 2 minutes past its last
        // nag rather than 2 minutes total.
        val insistent = escalationFor(TimerAlarmStyle.INSISTENT)!!
        var previous = request(TimerAlarmStyle.INSISTENT, attempt = 0).remainingRingMs()

        for (attempt in 1..insistent.maxRepeats) {
            val remaining = request(TimerAlarmStyle.INSISTENT, attempt).remainingRingMs()
            assertTrue("attempt=$attempt must shrink the window", remaining < previous)
            previous = remaining
        }
    }

    @Test
    fun `the window never collapses to nothing`() {
        // A zero timeout would cancel the notification the instant it posted,
        // silencing the alarm rather than ringing it.
        for (attempt in 0..10) {
            assertTrue(
                "attempt=$attempt",
                request(TimerAlarmStyle.INSISTENT, attempt).remainingRingMs() > 0L,
            )
        }
    }
}
