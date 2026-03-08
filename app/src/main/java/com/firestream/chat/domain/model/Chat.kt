package com.firestream.chat.domain.model

data class Chat(
    val id: String = "",
    val type: ChatType = ChatType.INDIVIDUAL,
    val name: String? = null,
    val avatarUrl: String? = null,
    val participants: List<String> = emptyList(),
    val lastMessage: Message? = null,
    val unreadCount: Int = 0,
    val createdAt: Long = System.currentTimeMillis(),
    val createdBy: String? = null,
    val admins: List<String> = emptyList(),
    val typingUserIds: List<String> = emptyList(),
    // Phase 2: chat organisation
    val isPinned: Boolean = false,
    val isArchived: Boolean = false,
    val muteUntil: Long = 0L  // epoch ms; 0 = not muted, Long.MAX_VALUE = always muted
)
