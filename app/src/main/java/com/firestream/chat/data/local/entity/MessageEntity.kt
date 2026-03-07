package com.firestream.chat.data.local.entity

import androidx.room.Entity
import androidx.room.PrimaryKey
import com.firestream.chat.domain.model.Message
import com.firestream.chat.domain.model.MessageStatus
import com.firestream.chat.domain.model.MessageType

@Entity(tableName = "messages")
data class MessageEntity(
    @PrimaryKey val id: String,
    val chatId: String,
    val senderId: String,
    val content: String,
    val type: String,
    val mediaUrl: String?,
    val mediaThumbnailUrl: String?,
    val status: String,
    val replyToId: String?,
    val timestamp: Long,
    val editedAt: Long?
) {
    fun toDomain() = Message(
        id = id,
        chatId = chatId,
        senderId = senderId,
        content = content,
        type = MessageType.valueOf(type),
        mediaUrl = mediaUrl,
        mediaThumbnailUrl = mediaThumbnailUrl,
        status = MessageStatus.valueOf(status),
        replyToId = replyToId,
        timestamp = timestamp,
        editedAt = editedAt
    )

    companion object {
        fun fromDomain(message: Message) = MessageEntity(
            id = message.id,
            chatId = message.chatId,
            senderId = message.senderId,
            content = message.content,
            type = message.type.name,
            mediaUrl = message.mediaUrl,
            mediaThumbnailUrl = message.mediaThumbnailUrl,
            status = message.status.name,
            replyToId = message.replyToId,
            timestamp = message.timestamp,
            editedAt = message.editedAt
        )
    }
}
