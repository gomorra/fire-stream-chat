package com.firestream.chat.ui.search

import com.firestream.chat.domain.model.Chat
import com.firestream.chat.domain.model.ChatType
import com.firestream.chat.domain.model.Contact
import com.firestream.chat.domain.model.Message
import com.firestream.chat.domain.model.MessageStatus
import com.firestream.chat.domain.model.MessageType
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The label above a global result — the one thing an in-chat result row does
 * not need, because in a chat "where" is never in question.
 *
 * The load-bearing case is `a 1-to-1 hit names the person once`: the sender of
 * an incoming 1:1 message *is* the chat title, so the obvious
 * "sender · chat" formatting would render "Alice · Alice" on the most common
 * kind of hit there is.
 */
class GlobalSearchLabelTest {

    private val me = "me"

    private val contacts = mapOf(
        "alice" to Contact(uid = "alice", displayName = "Alice"),
        "bob" to Contact(uid = "bob", displayName = "Bob"),
    )

    private val oneToOne = Chat(
        id = "c1",
        type = ChatType.INDIVIDUAL,
        participants = listOf(me, "alice"),
    )

    private val group = Chat(
        id = "c2",
        type = ChatType.GROUP,
        name = "Weekend Trip",
        participants = listOf(me, "alice", "bob"),
    )

    private fun message(senderId: String, chatId: String) = Message(
        id = "m1",
        chatId = chatId,
        senderId = senderId,
        content = "hello",
        type = MessageType.TEXT,
        status = MessageStatus.SENT,
    )

    private fun label(senderId: String, chat: Chat?) =
        globalResultLabel(message(senderId, chat?.id ?: "?"), chat, contacts, me)

    @Test
    fun `a 1-to-1 hit names the person once`() {
        assertEquals("Alice", label("alice", oneToOne))
    }

    @Test
    fun `a group hit names the sender and the chat`() {
        assertEquals("Bob · Weekend Trip", label("bob", group))
    }

    @Test
    fun `own messages say You`() {
        assertEquals("You · Alice", label(me, oneToOne))
        assertEquals("You · Weekend Trip", label(me, group))
    }

    @Test
    fun `an unresolvable sender falls back to the chat title rather than a raw uid`() {
        assertEquals("Weekend Trip", label("stranger", group))
    }

    @Test
    fun `a missing chat still renders something rather than blank`() {
        assertEquals("Chat", label("stranger", null))
    }

    @Test
    fun `a 1-to-1 chat with no contact entry falls back to its own name`() {
        val unknown = Chat(id = "c3", type = ChatType.INDIVIDUAL, name = "+49 170 000", participants = listOf(me, "zed"))
        assertEquals("+49 170 000", label("zed", unknown))
    }

    @Test
    fun `displayTitle prefers the contact name over the chat's own name`() {
        val named = oneToOne.copy(name = "+49 170 111")
        assertEquals("Alice", named.displayTitle(contacts, me))
    }
}
