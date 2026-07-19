package com.firestream.chat.ui.chat.command

import com.firestream.chat.domain.model.Message
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Test

/**
 * Target-resolution rule for the `.remind` widget: reply-target if selected, else the
 * newest message (chronological list, newest last), else null for an empty chat.
 */
class RemindTargetResolutionTest {

    private fun msg(id: String) = Message(id = id, content = "m$id")

    @Test
    fun `reply-target wins when one is selected`() {
        val reply = msg("reply")
        val messages = listOf(msg("a"), msg("b"), msg("c"))
        assertSame(reply, resolveRemindTarget(reply, messages))
    }

    @Test
    fun `falls back to the newest message when no reply-target`() {
        val messages = listOf(msg("a"), msg("b"), msg("c"))
        assertEquals("c", resolveRemindTarget(null, messages)?.id)
    }

    @Test
    fun `null when no reply-target and the chat is empty`() {
        assertNull(resolveRemindTarget(null, emptyList()))
    }

    @Test
    fun `reply-target wins even when the chat is empty`() {
        val reply = msg("reply")
        assertSame(reply, resolveRemindTarget(reply, emptyList()))
    }
}
