package com.firestream.chat.data.util

import com.firestream.chat.domain.model.MessageStatus
import com.firestream.chat.domain.model.MessageType
import com.firestream.chat.domain.model.TimerAlarmSound
import com.firestream.chat.domain.model.TimerAlarmStyle
import com.firestream.chat.domain.model.TimerState
import kotlinx.coroutines.CancellationException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Test

class EnumParsersTest {

    @Test
    fun `known values parse to the matching constant`() {
        assertEquals(MessageType.VOICE, parseMessageType("VOICE"))
        assertEquals(MessageStatus.DELIVERED, parseMessageStatus("DELIVERED"))
        assertEquals(TimerState.PAUSED, parseTimerState("PAUSED"))
    }

    @Test
    fun `unknown message type defaults to TEXT`() {
        assertEquals(MessageType.TEXT, parseMessageType("HOLOGRAM"))
    }

    @Test
    fun `unknown message status defaults to SENT`() {
        assertEquals(MessageStatus.SENT, parseMessageStatus("TELEPORTED"))
    }

    @Test
    fun `known alarm style and sound values parse`() {
        assertEquals(TimerAlarmStyle.INSISTENT, parseTimerAlarmStyle("INSISTENT"))
        assertEquals(TimerAlarmSound.RINGTONE, parseTimerAlarmSound("RINGTONE"))
    }

    @Test
    fun `unknown alarm style and sound parse to null so the caller can fall back`() {
        // null, not a default: resolveTimerAlarmStyle then falls through to the
        // legacy timerSilent boolean, the safer signal for an unknown future value.
        assertNull(parseTimerAlarmStyle("THERMONUCLEAR"))
        assertNull(parseTimerAlarmSound("AIR_HORN"))
    }

    // The alarm style/sound are *synced* — the sender's choice rings on every
    // participant's phone — so the two phones in a conversation can be running
    // different app versions. These pin the resulting compatibility contract, which
    // lives here so the Firestore mappers and the in-flight-PendingIntent path can
    // share one implementation and never drift apart.

    @Test
    fun `an explicit style wins over the legacy boolean`() {
        assertEquals(
            TimerAlarmStyle.INSISTENT,
            resolveTimerAlarmStyle(rawStyle = "INSISTENT", legacySilent = false),
        )
    }

    @Test
    fun `a timer written before the enum existed resolves from timerSilent`() {
        assertEquals(TimerAlarmStyle.SILENT, resolveTimerAlarmStyle(null, legacySilent = true))
        assertEquals(TimerAlarmStyle.NORMAL, resolveTimerAlarmStyle(null, legacySilent = false))
    }

    @Test
    fun `a style from a newer client falls back to the legacy boolean, not a guess`() {
        // Degrading to a ringing alarm when the sender asked for silence is the
        // failure that actually annoys, so the boolean out-votes an unknown name.
        assertEquals(
            TimerAlarmStyle.SILENT,
            resolveTimerAlarmStyle(rawStyle = "THERMONUCLEAR", legacySilent = true),
        )
    }

    @Test
    fun `sound resolves to the default when absent or unrecognised`() {
        assertEquals(TimerAlarmSound.RINGTONE, resolveTimerAlarmSound("RINGTONE"))
        assertEquals(TimerAlarmSound.DEFAULT, resolveTimerAlarmSound(null))
        assertEquals(TimerAlarmSound.DEFAULT, resolveTimerAlarmSound("AIR_HORN"))
    }

    @Test
    fun `unknown timer state defaults to null`() {
        assertNull(parseTimerState("EXPLODED"))
    }

    @Test
    fun `rethrowIfCancellation rethrows cancellation`() {
        assertThrows(CancellationException::class.java) {
            CancellationException("cancelled").rethrowIfCancellation()
        }
    }

    @Test
    fun `rethrowIfCancellation passes through other throwables`() {
        // Must not throw — the caller's catch block continues to its recovery.
        RuntimeException("boom").rethrowIfCancellation()
    }
}
