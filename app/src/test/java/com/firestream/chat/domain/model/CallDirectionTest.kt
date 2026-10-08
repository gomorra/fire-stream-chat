package com.firestream.chat.domain.model

import org.junit.Assert.assertEquals
import org.junit.Test

class CallDirectionTest {

    @Test
    fun `a call I placed is outgoing whether or not it connected`() {
        assertEquals(CallDirection.OUTGOING, CallDirection.of(isOwnMessage = true, durationSeconds = 0))
        assertEquals(CallDirection.OUTGOING, CallDirection.of(isOwnMessage = true, durationSeconds = 42))
    }

    @Test
    fun `a received call that connected is incoming`() {
        assertEquals(CallDirection.INCOMING, CallDirection.of(isOwnMessage = false, durationSeconds = 1))
    }

    @Test
    fun `a received call that never connected is missed`() {
        assertEquals(CallDirection.MISSED, CallDirection.of(isOwnMessage = false, durationSeconds = 0))
    }

    @Test
    fun `a received call without a recorded duration is missed`() {
        assertEquals(CallDirection.MISSED, CallDirection.of(isOwnMessage = false, durationSeconds = null))
    }
}
