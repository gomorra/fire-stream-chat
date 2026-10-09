package com.firestream.chat.domain.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CallLogTypeTest {

    private fun mine(endReason: String, durationSeconds: Int?) =
        CallLogType.of(isOwnMessage = true, endReason = endReason, durationSeconds = durationSeconds)

    private fun received(endReason: String, durationSeconds: Int?) =
        CallLogType.of(isOwnMessage = false, endReason = endReason, durationSeconds = durationSeconds)

    @Test
    fun `a call I placed that connected is outgoing, however it ended`() {
        assertEquals(CallLogType.OUTGOING, mine("hangup", 42))
        assertEquals(CallLogType.OUTGOING, mine("remote_hangup", 42))
        assertEquals(CallLogType.OUTGOING, mine("error", 42))
    }

    @Test
    fun `a call I placed that rang out is no answer`() {
        assertEquals(CallLogType.NO_ANSWER, mine("timeout", 0))
    }

    @Test
    fun `a call I placed that the other person declined is outgoing declined`() {
        assertEquals(CallLogType.OUTGOING_DECLINED, mine("declined", 0))
    }

    @Test
    fun `a call I placed that never connected for another reason is no answer`() {
        assertEquals(CallLogType.NO_ANSWER, mine("hangup", 0))
        assertEquals(CallLogType.NO_ANSWER, mine("remote_hangup", 0))
        assertEquals(CallLogType.NO_ANSWER, mine("error", null))
    }

    @Test
    fun `a received call that connected is incoming, however it ended`() {
        assertEquals(CallLogType.INCOMING, received("hangup", 1))
        assertEquals(CallLogType.INCOMING, received("error", 95))
    }

    @Test
    fun `a received call that never connected is missed`() {
        assertEquals(CallLogType.MISSED, received("hangup", 0))
        assertEquals(CallLogType.MISSED, received("timeout", 0))
        assertEquals(CallLogType.MISSED, received("error", 0))
    }

    @Test
    fun `a received call without a recorded duration is missed`() {
        assertEquals(CallLogType.MISSED, received("hangup", null))
    }

    @Test
    fun `a received call I declined is declined`() {
        assertEquals(CallLogType.DECLINED, received("declined", 0))
        assertEquals(CallLogType.DECLINED, received("declined", null))
    }

    @Test
    fun `an unknown end reason reads like any other call that never connected`() {
        assertEquals(CallLogType.NO_ANSWER, mine("busy", 0))
        assertEquals(CallLogType.MISSED, received("busy", 0))
    }

    @Test
    fun `only received calls the viewer did not take are flagged`() {
        val flagged = CallLogType.entries.filter { it.isMissedOrDeclined }

        assertEquals(listOf(CallLogType.MISSED, CallLogType.DECLINED), flagged)
        assertFalse(CallLogType.OUTGOING_DECLINED.isMissedOrDeclined)
        assertTrue(CallLogType.DECLINED.isMissedOrDeclined)
    }
}
