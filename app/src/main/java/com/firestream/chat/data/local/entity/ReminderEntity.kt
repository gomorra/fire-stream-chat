package com.firestream.chat.data.local.entity

import androidx.room.Entity
import androidx.room.PrimaryKey
import com.firestream.chat.domain.model.Reminder

/** One pending reminder per message — [messageId] is the primary key. */
@Entity(tableName = "reminders")
data class ReminderEntity(
    @PrimaryKey val messageId: String,
    val id: String,
    val chatId: String,
    val recipientId: String,
    val fireAtMs: Long,
    val messageSnapshot: String,
    val senderNameSnapshot: String,
    val createdAtMs: Long
) {
    fun toDomain() = Reminder(
        id = id,
        messageId = messageId,
        chatId = chatId,
        recipientId = recipientId,
        fireAtMs = fireAtMs,
        messageSnapshot = messageSnapshot,
        senderNameSnapshot = senderNameSnapshot,
        createdAtMs = createdAtMs
    )

    companion object {
        fun fromDomain(reminder: Reminder) = ReminderEntity(
            messageId = reminder.messageId,
            id = reminder.id,
            chatId = reminder.chatId,
            recipientId = reminder.recipientId,
            fireAtMs = reminder.fireAtMs,
            messageSnapshot = reminder.messageSnapshot,
            senderNameSnapshot = reminder.senderNameSnapshot,
            createdAtMs = reminder.createdAtMs
        )
    }
}
