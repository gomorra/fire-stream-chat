package com.firestream.chat.ui.chat

import com.firestream.chat.domain.model.Message

internal data class MessagesState(
    val messages: List<Message> = emptyList(),
    val pinnedMessages: List<Message> = emptyList(),
    val scrollToBottomTrigger: Int = 0,
    // messageIds with a pending snooze reminder in this chat. Combined into the
    // message flow by ChatMessageLoader (see ReminderRepository.observePendingIdsForChat).
    val pendingReminderIds: Set<String> = emptySet(),
)
