package com.firestream.chat.data.local.entity

import androidx.room.Entity
import androidx.room.PrimaryKey
import androidx.room.TypeConverters
import com.firestream.chat.data.local.Converters
import com.firestream.chat.domain.model.Message
import com.firestream.chat.domain.model.MessageStatus
import com.firestream.chat.domain.model.MessageType

@Entity(tableName = "messages")
@TypeConverters(Converters::class)
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
    val editedAt: Long?,
    // Phase 1 additions
    val reactions: Map<String, String> = emptyMap(),
    val isForwarded: Boolean = false,
    val duration: Int? = null,
    // Phase 2: starred messages
    val isStarred: Boolean = false
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
        editedAt = editedAt,
        reactions = reactions,
        isForwarded = isForwarded,
        duration = duration,
        isStarred = isStarred
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
            editedAt = message.editedAt,
            reactions = message.reactions,
            isForwarded = message.isForwarded,
            duration = message.duration,
            isStarred = message.isStarred
        )
    }
}
