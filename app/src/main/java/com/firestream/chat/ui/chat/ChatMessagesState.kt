package com.firestream.chat.ui.chat

import com.firestream.chat.domain.model.Message

internal data class MessagesState(
    val messages: List<Message> = emptyList(),
    val pinnedMessages: List<Message> = emptyList(),
    val scrollToBottomTrigger: Int = 0,
    // messageIds with a pending snooze reminder in this chat. Combined into the
    // message flow by ChatMessageLoader (see ReminderRepository.observePendingIdsForChat).
    val pendingReminderIds: Set<String> = emptySet(),
    // A reaction another user just added to any message in this chat, awaiting its
    // in-chat cue (bubble flash if the message is visible, jump-to-reaction FAB if not).
    // Deliberately carried on this slice — the same state that renders the reaction
    // chip — rather than over a side channel: both earlier deliveries (a loader
    // SharedFlow in 1.18.0, a ChatScreen snapshotFlow diff in 1.18.3) never reached
    // the screen. Set by ChatMessageLoader in the same update as the list it was
    // diffed from; cleared via consumeReactionCue() once ChatScreen has acted.
    val newIncomingReaction: ReactionAlert? = null,
)
