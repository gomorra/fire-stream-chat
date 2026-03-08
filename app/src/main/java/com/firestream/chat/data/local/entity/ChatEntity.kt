package com.firestream.chat.data.local.entity

import androidx.room.Entity
import androidx.room.PrimaryKey
import com.firestream.chat.domain.model.Chat
import com.firestream.chat.domain.model.ChatType
import com.firestream.chat.domain.model.Message

@Entity(tableName = "chats")
data class ChatEntity(
    @PrimaryKey val id: String,
    val type: String,
    val name: String?,
    val avatarUrl: String?,
    val participants: List<String>,
    val unreadCount: Int,
    val createdAt: Long,
    val createdBy: String?,
    val admins: List<String>,
    val lastMessageId: String?,
    val lastMessageContent: String?,
    val lastMessageTimestamp: Long?,
    // Phase 2: chat organisation
    val isPinned: Boolean = false,
    val isArchived: Boolean = false,
    val muteUntil: Long = 0L
) {
    fun toDomain() = Chat(
        id = id,
        type = ChatType.valueOf(type),
        name = name,
        avatarUrl = avatarUrl,
        participants = participants,
        unreadCount = unreadCount,
        createdAt = createdAt,
        createdBy = createdBy,
        admins = admins,
        lastMessage = if (lastMessageContent != null && lastMessageTimestamp != null) {
            Message(
                id = lastMessageId ?: "",
                chatId = id,
                senderId = "",
                content = lastMessageContent,
                timestamp = lastMessageTimestamp
            )
        } else null,
        isPinned = isPinned,
        isArchived = isArchived,
        muteUntil = muteUntil
    )

    companion object {
        fun fromDomain(chat: Chat) = ChatEntity(
            id = chat.id,
            type = chat.type.name,
            name = chat.name,
            avatarUrl = chat.avatarUrl,
            participants = chat.participants,
            unreadCount = chat.unreadCount,
            createdAt = chat.createdAt,
            createdBy = chat.createdBy,
            admins = chat.admins,
            lastMessageId = chat.lastMessage?.id,
            lastMessageContent = chat.lastMessage?.content,
            lastMessageTimestamp = chat.lastMessage?.timestamp,
            isPinned = chat.isPinned,
            isArchived = chat.isArchived,
            muteUntil = chat.muteUntil
        )
    }
}
