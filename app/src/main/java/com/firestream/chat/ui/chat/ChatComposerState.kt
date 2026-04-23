package com.firestream.chat.ui.chat

import com.firestream.chat.domain.model.Message
import com.firestream.chat.domain.model.User

internal data class ComposerState(
    val isSending: Boolean = false,
    val editingMessage: Message? = null,
    val replyToMessage: Message? = null,
    val mentionCandidates: List<User> = emptyList(),
    val canSendMessages: Boolean = true,
    val isAnnouncementMode: Boolean = false,
)
