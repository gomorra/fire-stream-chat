package com.firestream.chat.domain.model

data class CallLogEntry(
    val messageId: String,
    val chatId: String,
    val otherPartyId: String,
    val displayName: String,
    val avatarUrl: String?,
    val type: CallLogType,
    val durationSeconds: Int?,
    val timestamp: Long
)

/**
 * How a call message reads for the person viewing it. The chat bubble and the Calls tab both
 * label a call from this, so the two cannot disagree.
 */
enum class CallLogType {
    /** The viewer placed the call, and it connected. */
    OUTGOING,

    /** The viewer placed the call, and it never connected. It rang out, the viewer hung up, or it failed. */
    NO_ANSWER,

    /** The viewer placed the call, and the other person declined it. */
    OUTGOING_DECLINED,

    /** The viewer received the call, and it connected. */
    INCOMING,

    /** The viewer received the call, and it never connected. It rang out, the caller cancelled it, or it failed. */
    MISSED,

    /** The viewer received the call and declined it. */
    DECLINED;

    /** A received call the viewer did not take. Both screens show it in the error colour. */
    val isMissedOrDeclined: Boolean
        get() = this == MISSED || this == DECLINED

    companion object {
        /**
         * Only the caller writes the call message, so [isOwnMessage] means "I placed this call".
         * [endReason] is the message's content: the [EndReason] name in lower case.
         *
         * A call counts as answered only if it connected, and only a connected call has a
         * duration. The end reason cannot tell: a call the caller cancelled while it rang is
         * logged as "hangup" with 0 s, the same reason a finished call gets.
         */
        fun of(isOwnMessage: Boolean, endReason: String, durationSeconds: Int?): CallLogType {
            val connected = (durationSeconds ?: 0) > 0
            val reason = EndReason.entries.firstOrNull { it.name.equals(endReason, ignoreCase = true) }
            return when {
                connected -> if (isOwnMessage) OUTGOING else INCOMING
                reason == EndReason.DECLINED -> if (isOwnMessage) OUTGOING_DECLINED else DECLINED
                else -> if (isOwnMessage) NO_ANSWER else MISSED
            }
        }
    }
}
