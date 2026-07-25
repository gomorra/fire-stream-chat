package com.firestream.chat.ui.chat

import com.firestream.chat.domain.model.Message

/**
 * A reaction that another user just added to (or changed on) a message in the chat the
 * user is currently viewing, surfaced so the chat screen can highlight the bubble or
 * offer a jump-to-reaction affordance. [emoji] is the newly added/changed emoji.
 */
internal data class ReactionAlert(val messageId: String, val emoji: String)

/**
 * Diffs two message lists and returns the reactions that are *new to me*: a reaction
 * added or changed by someone other than [currentUserId], on any message in the chat.
 * Removals and the current user's own reactions are ignored — no cue is needed for a
 * reaction you just added yourself.
 *
 * Whose message was reacted to does **not** matter: a reaction the other person puts on
 * their own bubble is chat activity worth a cue just as much as one on mine. (Through
 * 1.18.5 this was restricted to messages the current user authored, so reactions on the
 * other side's bubbles passed silently.)
 *
 * A message must already be present in [previous] for its reactions to count as "new":
 * this suppresses false positives on the first real emission (e.g. the empty→loaded
 * transition on chat open), where every message would otherwise look like it just
 * gained all its pre-existing reactions.
 *
 * Pure and side-effect free so it can be unit-tested without Compose.
 */
internal fun detectNewIncomingReactions(
    previous: List<Message>,
    current: List<Message>,
    currentUserId: String
): List<ReactionAlert> {
    if (currentUserId.isBlank()) return emptyList()
    val previousById = previous.associateBy { it.id }
    val alerts = mutableListOf<ReactionAlert>()
    for (message in current) {
        val before = previousById[message.id]?.reactions ?: continue // unseen message → not a change
        var newest: String? = null
        for ((reactorId, emoji) in message.reactions) {
            if (reactorId == currentUserId) continue
            if (before[reactorId] != emoji) newest = emoji // added or changed
        }
        if (newest != null) alerts += ReactionAlert(message.id, newest)
    }
    return alerts
}
