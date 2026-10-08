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

enum class CallDirection {
    OUTGOING, INCOMING, MISSED;

    companion object {
        /**
         * Which way a call message reads for the person viewing it. Only the caller writes the
         * message, so [isOwnMessage] means "I placed this call".
         *
         * A received call counts as answered only if it connected, and only a connected call has a
         * duration. The end reason cannot tell: a call the caller cancelled while it rang is
         * logged as "hangup" with 0 s, the same reason a finished call gets.
         */
        fun of(isOwnMessage: Boolean, durationSeconds: Int?): CallDirection = when {
            isOwnMessage -> OUTGOING
            (durationSeconds ?: 0) > 0 -> INCOMING
            else -> MISSED
        }
    }
}
