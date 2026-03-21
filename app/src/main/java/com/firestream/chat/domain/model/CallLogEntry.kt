package com.firestream.chat.domain.model

data class CallLogEntry(
    val messageId: String,
    val chatId: String,
    val otherPartyId: String,
    val displayName: String,
    val avatarUrl: String?,
    val direction: CallDirection,
    val durationSeconds: Int?,
    val timestamp: Long
)

enum class CallDirection { OUTGOING, INCOMING, MISSED }
