// region: AGENT-NOTE
// Responsibility: Who a send is for — the one peer of a 1:1 chat, or nobody —
//   and the one mapping to and from the outboxRecipientId column.
// Owns: the null / "" / peer-id three-way reading of that column.
// Collaborators: MessageEntity.outbox / sendTarget, MessageWriter (encrypt for a
//   peer), MessageRepositoryImpl and OutboxWorker (the block check's peer).
// Don't put here: the block check or the encryption themselves. Cites "Sends are
//   idempotent by client id and drained by OutboxWorker" (docs/PATTERNS.md).
// endregion

package com.firestream.chat.data.outbox

/**
 * Who a message is for, as far as the send pipeline cares: the one peer of a
 * 1:1 chat, or nobody in particular.
 *
 * A [Peer] is the Signal session the body is encrypted for and the user the
 * block check asks about. [NoPeer] is a group or broadcast chat: no session can
 * address it, so the message travels in plaintext and no block check applies.
 *
 * The one place the `outboxRecipientId` column is read or written: the UI's
 * recipient id (`""` for groups) enters through [of], the row's column leaves
 * through [column] and comes back through [fromColumn], where `null` means the
 * row never recorded a target and must not be sent at all.
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
        /** The recipient id the UI passes: a 1:1 peer, or `""` for a group or broadcast chat. */
        fun of(recipientId: String): SendTarget = if (recipientId.isEmpty()) NoPeer else Peer(recipientId)

        /** From a row's `outboxRecipientId`; `null` when it never recorded one. */
        fun fromColumn(column: String?): SendTarget? = column?.let(::of)
    }
}
