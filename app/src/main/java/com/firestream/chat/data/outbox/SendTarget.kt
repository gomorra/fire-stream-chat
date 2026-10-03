// region: AGENT-NOTE
// Responsibility: Who a send is for — the one peer of a 1:1 chat, or nobody.
//   Derived from the chat's local row when a send starts (forChat), and read
//   back from the outboxRecipientId column when the outbox drains it.
// Owns: the rule that turns a chat row into a target, including every refusal
//   (ChatNotReadyException); the null / "" / peer-id three-way reading of the
//   outboxRecipientId column.
// Collaborators: MessageRepositoryImpl (calls forChat once per send, before the
//   row is built), MessageEntity.outbox / sendTarget, MessageWriter (encrypt for
//   a peer), OutboxWorker (the block check's peer).
// Don't put here: the block check or the encryption themselves. Nor a way to
//   build a target from an id a caller computed. A screen's idea of "the
//   recipient" is never an addressing input. Cites "Sends are idempotent by
//   client id and drained by OutboxWorker" (docs/PATTERNS.md).
// endregion

package com.firestream.chat.data.outbox

import com.firestream.chat.data.local.entity.ChatEntity
import com.firestream.chat.domain.model.ChatNotReadyException
import com.firestream.chat.domain.model.ChatType

/**
 * Who a message is for, as far as the send pipeline cares: the one peer of a
 * 1:1 chat, or nobody in particular.
 *
 * A [Peer] is the Signal session the body is encrypted for and the user the
 * block check asks about. [NoPeer] is a group or broadcast chat: no session can
 * address it, so the message travels in plaintext and no block check applies.
 *
 * A send that writes a row gets its target from two places. [forChat] derives
 * it from the chat's local row when the send starts, and it is recorded on the
 * message row as [column]. [fromColumn] reads it back when the outbox drains
 * the row, where `null` means the row never recorded a target and must not be
 * sent at all.
 *
 * The one other construction is the broadcast fan-out
 * (`MessageRepositoryImpl.sendBroadcastMessage`). It builds a [Peer] per list
 * member and writes each copy directly, with no row.
 */
sealed interface SendTarget {
    data class Peer(val id: String) : SendTarget
    data object NoPeer : SendTarget

    /** The peer to encrypt for and to ask the block list about; `null` for [NoPeer]. */
    val peerId: String?
        get() = (this as? Peer)?.id

    /** The `outboxRecipientId` value: the peer id, or `""` for [NoPeer]. */
    val column: String
        get() = peerId ?: ""

    companion object {
        /**
         * Who a send by [senderId] into the chat at [chatId] is for, given the
         * chat's local [row]. Throws [ChatNotReadyException] instead of guessing:
         * doubt never becomes [NoPeer], which would send in plaintext.
         *
         * A 1:1 chat must have exactly one participant other than the sender,
         * and that one must have an id. A missing row, a 1:1 chat with none or
         * several others and a type the app does not know are all refused.
         */
        fun forChat(chatId: String, row: ChatEntity?, senderId: String): SendTarget {
            fun refuse(why: String): Nothing = throw ChatNotReadyException(chatId, why)
            row ?: refuse("no local chat row")
            // Not ChatEntity.toDomain(): it maps an unknown type to INDIVIDUAL.
            return when (ChatType.entries.firstOrNull { it.name == row.type }) {
                ChatType.INDIVIDUAL -> {
                    val others = row.participants.filter { it != senderId }
                    val peer = others.singleOrNull() ?: refuse("INDIVIDUAL with ${others.size} other participants")
                    // The column stores NoPeer as "", so a blank id would be read back as nobody.
                    if (peer.isBlank()) refuse("INDIVIDUAL whose other participant has a blank id")
                    Peer(peer)
                }
                ChatType.GROUP, ChatType.BROADCAST -> NoPeer
                null -> refuse("unknown chat type '${row.type}'")
            }
        }

        /** From a row's `outboxRecipientId`; `null` when it never recorded one. */
        fun fromColumn(column: String?): SendTarget? = when (column) {
            null -> null
            "" -> NoPeer
            else -> Peer(column)
        }
    }
}
