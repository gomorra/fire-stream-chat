package com.firestream.chat.data.outbox

import com.firestream.chat.data.local.entity.ChatEntity
import com.firestream.chat.data.repository.testChat
import com.firestream.chat.domain.model.ChatNotReadyException
import com.firestream.chat.domain.model.ChatType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Test

/**
 * The one rule that says who a send is for. A 1:1 chat addresses its one other
 * participant; a group or broadcast chat addresses nobody; everything the rule
 * cannot be sure of is refused, because the only other answer — nobody — would
 * send a 1:1 message in plaintext.
 */
class SendTargetTest {

    private fun chat(type: String, vararg participants: String) = testChat("chat1", type, participants.toList())

    private fun targetOf(row: ChatEntity?) = SendTarget.forChat("chat1", row, senderId = "me")

    // ── forChat: what resolves ──────────────────────────────────────────────

    @Test
    fun `forChat resolves a chat row to the peer of a 1-to-1 chat and to nobody for a group or broadcast`() {
        val cases = listOf(
            chat(ChatType.INDIVIDUAL.name, "me", "peer") to SendTarget.Peer("peer"),
            // The sender's position in the list does not matter.
            chat(ChatType.INDIVIDUAL.name, "peer", "me") to SendTarget.Peer("peer"),
            chat(ChatType.GROUP.name, "me", "a", "b") to SendTarget.NoPeer,
            // A group of two is still a group: its type decides, not its size.
            chat(ChatType.GROUP.name, "me", "a") to SendTarget.NoPeer,
            chat(ChatType.BROADCAST.name, "me", "a", "b") to SendTarget.NoPeer,
        )

        cases.forEach { (row, expected) ->
            assertEquals("${row.type} ${row.participants}", expected, targetOf(row))
        }
    }

    // ── forChat: what is refused ────────────────────────────────────────────

    @Test
    fun `forChat refuses a chat it cannot address, naming why`() {
        val cases = listOf(
            null to "no local chat row",
            // A self-chat, in both of the shapes it can be stored in.
            chat(ChatType.INDIVIDUAL.name, "me") to "INDIVIDUAL with 0 other participants",
            chat(ChatType.INDIVIDUAL.name, "me", "me") to "INDIVIDUAL with 0 other participants",
            chat(ChatType.INDIVIDUAL.name) to "INDIVIDUAL with 0 other participants",
            // A group mislabelled as a 1:1: any one member would be a guess.
            chat(ChatType.INDIVIDUAL.name, "me", "a", "b") to "INDIVIDUAL with 2 other participants",
            // A row that does not list the sender at all.
            chat(ChatType.INDIVIDUAL.name, "a", "b") to "INDIVIDUAL with 2 other participants",
            // The column stores nobody as "": a blank peer id would be recorded
            // as nobody, and the outbox would send the message in plaintext.
            chat(ChatType.INDIVIDUAL.name, "me", "") to "INDIVIDUAL whose other participant has a blank id",
            chat(ChatType.INDIVIDUAL.name, "me", " ") to "INDIVIDUAL whose other participant has a blank id",
            chat("CHANNEL", "me", "a") to "unknown chat type 'CHANNEL'",
            chat("", "me", "a") to "unknown chat type ''",
            // The stored string is an enum name, compared exactly.
            chat("individual", "me", "a") to "unknown chat type 'individual'",
        )

        cases.forEach { (row, reason) ->
            val label = row?.let { "${it.type} ${it.participants}" } ?: "no row"
            val refusal = assertThrows(label, ChatNotReadyException::class.java) { targetOf(row) }
            assertEquals(label, "chat1", refusal.chatId)
            assertEquals(label, reason, refusal.reason)
        }
    }

    // The message is the banner's; the log line is toString.
    @Test
    fun `a refusal's log line names the chat and the reason`() {
        val refusal = assertThrows(ChatNotReadyException::class.java) { targetOf(null) }

        assertEquals("ChatNotReadyException(chat=chat1): no local chat row", refusal.toString())
    }

    // ── the outboxRecipientId column ────────────────────────────────────────

    @Test
    fun `the column holds the peer id or an empty string, and null reads as no recorded target`() {
        assertEquals("", SendTarget.NoPeer.column)
        assertEquals("peer", SendTarget.Peer("peer").column)

        assertNull(SendTarget.fromColumn(null))
        assertEquals(SendTarget.NoPeer, SendTarget.fromColumn(""))
        assertEquals(SendTarget.Peer("peer"), SendTarget.fromColumn("peer"))
    }
}
