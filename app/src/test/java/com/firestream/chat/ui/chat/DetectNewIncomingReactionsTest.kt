package com.firestream.chat.ui.chat

import com.firestream.chat.domain.model.Message
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Unit tests for [detectNewIncomingReactions] — the pure diff that surfaces reactions
 * another user just added to one of MY messages, driving the in-chat highlight /
 * jump-to-reaction FAB.
 */
class DetectNewIncomingReactionsTest {

    private val me = "me"
    private val other = "other"

    private fun msg(id: String, senderId: String, reactions: Map<String, String> = emptyMap()) =
        Message(id = id, senderId = senderId, reactions = reactions)

    @Test
    fun `reaction added by another user on my message is detected`() {
        val previous = listOf(msg("m1", me))
        val current = listOf(msg("m1", me, mapOf(other to "❤️")))

        val alerts = detectNewIncomingReactions(previous, current, me)

        assertEquals(listOf(ReactionAlert("m1", "❤️")), alerts)
    }

    @Test
    fun `my own reaction on my own message is ignored`() {
        val previous = listOf(msg("m1", me))
        val current = listOf(msg("m1", me, mapOf(me to "👍")))

        assertTrue(detectNewIncomingReactions(previous, current, me).isEmpty())
    }

    @Test
    fun `my own reaction on someone else's message is ignored`() {
        val previous = listOf(msg("m1", other))
        val current = listOf(msg("m1", other, mapOf(me to "👍")))

        assertTrue(detectNewIncomingReactions(previous, current, me).isEmpty())
    }

    @Test
    fun `another user reacting to their own message is detected`() {
        // Direction must not matter: reactions on the other side's bubbles are chat
        // activity worth a cue too. Restricting this to my own messages is exactly
        // what made half the reactions pass silently through 1.18.5.
        val previous = listOf(msg("m1", other))
        val current = listOf(msg("m1", other, mapOf(other to "👍")))

        assertEquals(listOf(ReactionAlert("m1", "👍")), detectNewIncomingReactions(previous, current, me))
    }

    @Test
    fun `a third party reacting to someone else's message is detected`() {
        val third = "third"
        val previous = listOf(msg("m1", other))
        val current = listOf(msg("m1", other, mapOf(third to "🎉")))

        assertEquals(listOf(ReactionAlert("m1", "🎉")), detectNewIncomingReactions(previous, current, me))
    }

    @Test
    fun `changed emoji by another user is detected`() {
        val previous = listOf(msg("m1", me, mapOf(other to "👍")))
        val current = listOf(msg("m1", me, mapOf(other to "❤️")))

        assertEquals(listOf(ReactionAlert("m1", "❤️")), detectNewIncomingReactions(previous, current, me))
    }

    @Test
    fun `removed reaction is ignored`() {
        val previous = listOf(msg("m1", me, mapOf(other to "👍")))
        val current = listOf(msg("m1", me))

        assertTrue(detectNewIncomingReactions(previous, current, me).isEmpty())
    }

    @Test
    fun `unchanged reactions produce nothing`() {
        val previous = listOf(msg("m1", me, mapOf(other to "👍")))
        val current = listOf(msg("m1", me, mapOf(other to "👍")))

        assertTrue(detectNewIncomingReactions(previous, current, me).isEmpty())
    }

    @Test
    fun `message not present in previous is treated as baseline, not a new reaction`() {
        // Guards the empty→loaded transition on chat open: a message appearing for
        // the first time must not fire alerts for reactions it already carries.
        val previous = emptyList<Message>()
        val current = listOf(msg("m1", me, mapOf(other to "🔥")))

        assertTrue(detectNewIncomingReactions(previous, current, me).isEmpty())
    }

    @Test
    fun `reaction added to an already-loaded message is detected`() {
        val previous = listOf(msg("m1", me), msg("m2", me, mapOf(other to "👍")))
        val current = listOf(msg("m1", me, mapOf(other to "🔥")), msg("m2", me, mapOf(other to "👍")))

        assertEquals(listOf(ReactionAlert("m1", "🔥")), detectNewIncomingReactions(previous, current, me))
    }

    @Test
    fun `blank current user id yields nothing`() {
        val previous = listOf(msg("m1", ""))
        val current = listOf(msg("m1", "", mapOf(other to "👍")))

        assertTrue(detectNewIncomingReactions(previous, current, "").isEmpty())
    }

    @Test
    fun `multiple messages each report their own new reaction`() {
        val previous = listOf(msg("m1", me), msg("m2", me))
        val current = listOf(
            msg("m1", me, mapOf(other to "❤️")),
            msg("m2", me, mapOf(other to "😂")),
        )

        val alerts = detectNewIncomingReactions(previous, current, me)

        assertEquals(setOf(ReactionAlert("m1", "❤️"), ReactionAlert("m2", "😂")), alerts.toSet())
    }
}
