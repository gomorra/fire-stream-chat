package com.firestream.chat.domain.model

/**
 * A pending message-snooze reminder. Local-only (never synced to Firestore) —
 * snapshot fields ([messageSnapshot], [senderNameSnapshot]) let the fired
 * notification render even if the source message has since been deleted.
 */
data class Reminder(
    val id: String,
    val messageId: String,
    val chatId: String,
    val recipientId: String,
    val fireAtMs: Long,
    val messageSnapshot: String,
    val senderNameSnapshot: String,
    val createdAtMs: Long
)
