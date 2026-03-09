package com.firestream.chat.data.local.entity

import androidx.room.Entity
import androidx.room.PrimaryKey
import com.firestream.chat.domain.model.Chat
import com.firestream.chat.domain.model.ChatType
import com.firestream.chat.domain.model.GroupPermissions
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
    val muteUntil: Long = 0L,
    // Phase 5: group management
    val description: String? = null,
    val inviteLink: String? = null,
    val requireApproval: Boolean = false,
    val pendingMembers: List<String> = emptyList(),
    // Phase 5.2: group permissions
    val owner: String? = null,
    val permissions: GroupPermissions = GroupPermissions()
) {
    fun toDomain() = Chat(
        id = id,
        type = runCatching { ChatType.valueOf(type) }.getOrDefault(ChatType.INDIVIDUAL),
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
        muteUntil = muteUntil,
        description = description,
        inviteLink = inviteLink,
        requireApproval = requireApproval,
        pendingMembers = pendingMembers,
        owner = owner,
        permissions = permissions
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
            muteUntil = chat.muteUntil,
            description = chat.description,
            inviteLink = chat.inviteLink,
            requireApproval = chat.requireApproval,
            pendingMembers = chat.pendingMembers,
            owner = chat.owner,
            permissions = chat.permissions
        )
    }
}
