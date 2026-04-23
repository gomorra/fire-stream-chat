package com.firestream.chat.ui.chat

import com.firestream.chat.domain.model.Message

internal data class MessagesState(
    val messages: List<Message> = emptyList(),
    val pinnedMessages: List<Message> = emptyList(),
    val scrollToBottomTrigger: Int = 0,
)
