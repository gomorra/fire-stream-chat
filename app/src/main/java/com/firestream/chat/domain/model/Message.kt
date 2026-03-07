package com.firestream.chat.domain.model

data class Message(
    val id: String = "",
    val chatId: String = "",
    val senderId: String = "",
    val content: String = "",
    val type: MessageType = MessageType.TEXT,
    val mediaUrl: String? = null,
    val mediaThumbnailUrl: String? = null,
    val status: MessageStatus = MessageStatus.SENDING,
    val replyToId: String? = null,
    val timestamp: Long = System.currentTimeMillis(),
    val editedAt: Long? = null
)
