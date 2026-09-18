package com.firestream.chat.ui.chat

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The header's "Online" rule: presence and the typing indicator arrive over two
 * independent channels, and a recipient who is typing is online whatever the
 * presence channel has reported so far.
 */
class SessionStateOnlineTest {

    @Test
    fun `presence alone shows online`() {
        assertTrue(SessionState(isRecipientOnline = true).recipientAppearsOnline)
        assertFalse(SessionState(isRecipientOnline = false).recipientAppearsOnline)
    }

    @Test
    fun `a typing recipient shows online before presence catches up`() {
        val state = SessionState(isRecipientOnline = false, typingUserIds = listOf("other"))

        assertTrue(state.recipientAppearsOnline)
    }

    @Test
    fun `typing in a group or broadcast does not show a single online status`() {
        assertFalse(
            SessionState(isGroupChat = true, typingUserIds = listOf("other")).recipientAppearsOnline
        )
        assertFalse(
            SessionState(isBroadcast = true, typingUserIds = listOf("other")).recipientAppearsOnline
        )
    }

    @Test
    fun `typing stopping falls back to presence`() {
        val typing = SessionState(isRecipientOnline = false, typingUserIds = listOf("other"))

        assertFalse(typing.copy(typingUserIds = emptyList()).recipientAppearsOnline)
        assertTrue(typing.copy(typingUserIds = emptyList(), isRecipientOnline = true).recipientAppearsOnline)
    }
}
