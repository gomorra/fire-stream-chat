package com.firestream.chat.data.local.entity

import androidx.room.Entity
import androidx.room.PrimaryKey
import com.firestream.chat.domain.model.Chat
import com.firestream.chat.domain.model.ChatType

@Entity(tableName = "chats")
data class ChatEntity(
    @PrimaryKey val id: String,
    val type: String,
    val name: String?,
    val avatarUrl: String?,
    val participants: String, // JSON array of UIDs
    val unreadCount: Int,
    val createdAt: Long,
    val createdBy: String?,
    val admins: String, // JSON array of UIDs
    val lastMessageId: String?,
    val lastMessageContent: String?,
    val lastMessageTimestamp: Long?
) {
    fun toDomain() = Chat(
        id = id,
        type = ChatType.valueOf(type),
        name = name,
        avatarUrl = avatarUrl,
        participants = participants.split(",").filter { it.isNotEmpty() },
        unreadCount = unreadCount,
        createdAt = createdAt,
        createdBy = createdBy,
        admins = admins.split(",").filter { it.isNotEmpty() }
    )

    companion object {
        fun fromDomain(chat: Chat) = ChatEntity(
            id = chat.id,
            type = chat.type.name,
            name = chat.name,
            avatarUrl = chat.avatarUrl,
            participants = chat.participants.joinToString(","),
            unreadCount = chat.unreadCount,
            createdAt = chat.createdAt,
            createdBy = chat.createdBy,
            admins = chat.admins.joinToString(","),
            lastMessageId = chat.lastMessage?.id,
            lastMessageContent = chat.lastMessage?.content,
            lastMessageTimestamp = chat.lastMessage?.timestamp
        )
    }
}
