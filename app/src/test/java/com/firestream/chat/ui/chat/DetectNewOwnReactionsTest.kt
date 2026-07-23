package com.firestream.chat.ui.chat

import com.firestream.chat.domain.model.Message
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Unit tests for [detectNewOwnReactions] — the pure diff that surfaces reactions
 * another user just added to one of MY messages, driving the in-chat highlight /
 * jump-to-reaction FAB.
 */
class DetectNewOwnReactionsTest {

    private val me = "me"
    private val other = "other"

    private fun msg(id: String, senderId: String, reactions: Map<String, String> = emptyMap()) =
        Message(id = id, senderId = senderId, reactions = reactions)

    @Test
    fun `reaction added by another user on my message is detected`() {
        val previous = listOf(msg("m1", me))
        val current = listOf(msg("m1", me, mapOf(other to "❤️")))

        val alerts = detectNewOwnReactions(previous, current, me)

        assertEquals(listOf(ReactionAlert("m1", "❤️")), alerts)
    }

    @Test
    fun `my own reaction on my own message is ignored`() {
        val previous = listOf(msg("m1", me))
        val current = listOf(msg("m1", me, mapOf(me to "👍")))

        assertTrue(detectNewOwnReactions(previous, current, me).isEmpty())
    }

    @Test
    fun `reaction on someone else's message is ignored`() {
        val previous = listOf(msg("m1", other))
        val current = listOf(msg("m1", other, mapOf(me to "👍")))

        assertTrue(detectNewOwnReactions(previous, current, me).isEmpty())
    }

    @Test
    fun `changed emoji by another user is detected`() {
        val previous = listOf(msg("m1", me, mapOf(other to "👍")))
        val current = listOf(msg("m1", me, mapOf(other to "❤️")))

        assertEquals(listOf(ReactionAlert("m1", "❤️")), detectNewOwnReactions(previous, current, me))
    }

    @Test
    fun `removed reaction is ignored`() {
        val previous = listOf(msg("m1", me, mapOf(other to "👍")))
        val current = listOf(msg("m1", me))

        assertTrue(detectNewOwnReactions(previous, current, me).isEmpty())
    }

    @Test
    fun `unchanged reactions produce nothing`() {
        val previous = listOf(msg("m1", me, mapOf(other to "👍")))
        val current = listOf(msg("m1", me, mapOf(other to "👍")))

        assertTrue(detectNewOwnReactions(previous, current, me).isEmpty())
    }

    @Test
    fun `new message that arrives already carrying another user's reaction is detected`() {
        val previous = emptyList<Message>()
        val current = listOf(msg("m1", me, mapOf(other to "🔥")))

        assertEquals(listOf(ReactionAlert("m1", "🔥")), detectNewOwnReactions(previous, current, me))
    }

    @Test
    fun `blank current user id yields nothing`() {
        val previous = listOf(msg("m1", ""))
        val current = listOf(msg("m1", "", mapOf(other to "👍")))

        assertTrue(detectNewOwnReactions(previous, current, "").isEmpty())
    }

    @Test
    fun `multiple messages each report their own new reaction`() {
        val previous = listOf(msg("m1", me), msg("m2", me))
        val current = listOf(
            msg("m1", me, mapOf(other to "❤️")),
            msg("m2", me, mapOf(other to "😂")),
        )

        val alerts = detectNewOwnReactions(previous, current, me)

        assertEquals(setOf(ReactionAlert("m1", "❤️"), ReactionAlert("m2", "😂")), alerts.toSet())
    }
}
