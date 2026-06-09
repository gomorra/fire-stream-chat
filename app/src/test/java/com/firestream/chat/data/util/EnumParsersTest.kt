package com.firestream.chat.data.util

import com.firestream.chat.domain.model.MessageStatus
import com.firestream.chat.domain.model.MessageType
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
