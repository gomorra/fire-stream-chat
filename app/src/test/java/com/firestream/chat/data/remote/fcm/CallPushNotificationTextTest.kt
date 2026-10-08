package com.firestream.chat.data.remote.fcm

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** The notification a pushed call message gets. Its recipient is always the callee. */
class CallPushNotificationTextTest {

    private fun push(endReason: String, durationSeconds: Int?) = buildMap {
        put("messageType", "CALL")
        put("messageContent", endReason)
        if (durationSeconds != null) put("callDurationSeconds", durationSeconds.toString())
    }

    @Test
    fun `a call that never connected and was not declined is a missed call`() {
        assertEquals("📞 Missed call", callPushNotificationText(push("hangup", 0)))
        assertEquals("📞 Missed call", callPushNotificationText(push("timeout", 0)))
    }

    @Test
    fun `a call the callee declined needs no notification`() {
        assertNull(callPushNotificationText(push("declined", 0)))
    }

    @Test
    fun `a call that connected needs no notification`() {
        assertNull(callPushNotificationText(push("hangup", 65)))
    }

    @Test
    fun `a push without the duration cannot tell, and says call`() {
        assertEquals("📞 Call", callPushNotificationText(push("hangup", durationSeconds = null)))
    }
}
