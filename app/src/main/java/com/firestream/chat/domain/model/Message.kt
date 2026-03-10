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
    val editedAt: Long? = null,
    // Phase 1: reactions — map of userId → emoji
    val reactions: Map<String, String> = emptyMap(),
    // Phase 1: forwarding
    val isForwarded: Boolean = false,
    // Phase 1: voice messages — duration in seconds
    val duration: Int? = null,
    // Phase 2: starred messages
    val isStarred: Boolean = false,
    // Per-recipient delivery/read tracking for group chats
    val readBy: Map<String, Long> = emptyMap(),
    val deliveredTo: Map<String, Long> = emptyMap(),
    // Phase 5.3: polls
    val pollData: Poll? = null,
    // Phase 5.4: mentions — userIds; "everyone" for @everyone
    val mentions: List<String> = emptyList(),
    val deletedAt: Long? = null
)
