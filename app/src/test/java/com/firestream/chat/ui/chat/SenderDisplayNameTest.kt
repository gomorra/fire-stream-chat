package com.firestream.chat.ui.chat

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Regression cover for the search-result sender label, which rendered
 * `senderId.take(12)` — a raw Firestore uid — at the top of every text row.
 */
class SenderDisplayNameTest {

    private val session = SessionState(
        currentUserId = "me",
        chatName = "Weekend Trip",
        participantAvatars = mapOf(
            "me" to ParticipantAvatar("Gomorra", null, null),
            "u1" to ParticipantAvatar("Alice", null, null),
        ),
    )

    @Test
    fun `own messages are labelled You`() {
        assertEquals("You", session.senderDisplayName("me"))
    }

    @Test
    fun `known participant resolves to display name, never the raw id`() {
        assertEquals("Alice", session.senderDisplayName("u1"))
    }

    @Test
    fun `unknown sender falls back to the chat name`() {
        assertEquals("Weekend Trip", session.senderDisplayName("u2"))
    }

    @Test
    fun `avatar map wins over the chat name for a 1 to 1 recipient`() {
        // observeRecipient populates participantAvatars for 1:1 chats, where
        // chatName is null — the recipient must still be named.
        val oneToOne = SessionState(
            currentUserId = "me",
            participantAvatars = mapOf("u1" to ParticipantAvatar("Alice", null, null)),
        )
        assertEquals("Alice", oneToOne.senderDisplayName("u1"))
    }

    @Test
    fun `id is the last resort when nothing has loaded yet`() {
        assertEquals("u9", SessionState(currentUserId = "me").senderDisplayName("u9"))
    }
}
