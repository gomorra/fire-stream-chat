package com.firestream.chat.ui.components

import com.firestream.chat.domain.model.ChatType
import com.firestream.chat.test.TestData
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The three pure questions every chat picker asks: what a row is called, who a
 * send to it is addressed to, and which rows a query keeps.
 *
 * The recipient one is the load-bearing case. The forward dialog this replaced
 * named `participants.first { it != me }` as the recipient for *every* chat, so
 * forwarding into a group addressed the send to one arbitrary member — which in
 * a release build means encrypting it to that member's Signal session, leaving
 * the rest of the group with something they cannot read.
 */
class ChatPickerTargetsTest {

    private val me = "user-1"

    @Test
    fun `a 1-1 chat is addressed to the other participant`() {
        val chat = TestData.chat(participants = listOf(me, "user-2"))

        assertEquals("user-2", chat.sendRecipientId(me))
    }

    @Test
    fun `a group is addressed to nobody so it sends as plaintext`() {
        val chat = TestData.chat(
            type = ChatType.GROUP,
            name = "Weekend Trip",
            participants = listOf(me, "user-2", "user-3"),
        )

        assertEquals("", chat.sendRecipientId(me))
    }

    @Test
    fun `a broadcast is addressed to nobody either`() {
        val chat = TestData.chat(
            type = ChatType.BROADCAST,
            name = "Announcements",
            participants = listOf(me, "user-2", "user-3"),
        )

        assertEquals("", chat.sendRecipientId(me))
    }

    @Test
    fun `a named chat shows its own name`() {
        val chat = TestData.chat(type = ChatType.GROUP, name = "Weekend Trip")

        assertEquals("Weekend Trip", chat.pickerDisplayName(me, emptyMap()))
    }

    @Test
    fun `an unnamed 1-1 shows the other participant's profile name`() {
        val chat = TestData.chat(participants = listOf(me, "user-2"))
        val profiles = mapOf("user-2" to TestData.user(uid = "user-2", displayName = "Alice"))

        assertEquals("Alice", chat.pickerDisplayName(me, profiles))
    }

    @Test
    fun `a chat with neither a name nor a profile never shows a raw uid`() {
        val chat = TestData.chat(participants = listOf(me, "user-2"))

        assertEquals("Chat", chat.pickerDisplayName(me, emptyMap()))
    }

    @Test
    fun `a blank name falls through to the profile name`() {
        val chat = TestData.chat(name = "", participants = listOf(me, "user-2"))
        val profiles = mapOf("user-2" to TestData.user(uid = "user-2", displayName = "Alice"))

        assertEquals("Alice", chat.pickerDisplayName(me, profiles))
    }

    @Test
    fun `search matches display names case-insensitively`() {
        val alice = TestData.chat(id = "c1", participants = listOf(me, "user-2"))
        val group = TestData.chat(id = "c2", type = ChatType.GROUP, name = "Weekend Trip")
        val profiles = mapOf("user-2" to TestData.user(uid = "user-2", displayName = "Alice"))

        val hits = listOf(alice, group).filterChatsByName("alI", me, profiles)

        assertEquals(listOf("c1"), hits.map { it.id })
    }

    @Test
    fun `a blank query keeps every chat`() {
        val chats = listOf(TestData.chat(id = "c1"), TestData.chat(id = "c2"))

        assertEquals(chats, chats.filterChatsByName("   ", me, emptyMap()))
    }
}
