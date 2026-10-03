package com.firestream.chat.data.remote.fcm

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Unit tests for [notificationPartnerHint], the value a message notification's
 * tap intent carries as the chat partner.
 */
class NotificationPartnerHintTest {

    @Test
    fun `a group notification names no partner`() {
        // Regression: the sender of a group message was written into the tap
        // intent, so the group opened with one member as its 1:1 partner.
        assertEquals("", notificationPartnerHint(isGroup = true, senderId = "member-1"))
    }

    @Test
    fun `a 1-1 notification names the sender`() {
        assertEquals("user-2", notificationPartnerHint(isGroup = false, senderId = "user-2"))
    }
}
