package com.firestream.chat.ui.chat

import com.firestream.chat.domain.model.Message

enum class GroupPosition { ALONE, FIRST, MIDDLE, LAST }

/**
 * Determines the group position of a message relative to its neighbors.
 * Messages are grouped when: same sender, within 1 minute, same day, neither deleted.
 */
internal fun computeGroupPosition(
    message: Message,
    previousMessage: Message?,
    nextMessage: Message?
): GroupPosition {
    val sameSenderAsPrev = previousMessage != null &&
        previousMessage.senderId == message.senderId &&
        message.deletedAt == null &&
        previousMessage.deletedAt == null &&
        (message.timestamp - previousMessage.timestamp) < 60_000 &&
        isSameDay(message.timestamp, previousMessage.timestamp)

    val sameSenderAsNext = nextMessage != null &&
        nextMessage.senderId == message.senderId &&
        message.deletedAt == null &&
        nextMessage.deletedAt == null &&
        (nextMessage.timestamp - message.timestamp) < 60_000 &&
        isSameDay(message.timestamp, nextMessage.timestamp)

    return when {
        sameSenderAsPrev && sameSenderAsNext -> GroupPosition.MIDDLE
        sameSenderAsPrev && !sameSenderAsNext -> GroupPosition.LAST
        !sameSenderAsPrev && sameSenderAsNext -> GroupPosition.FIRST
        else -> GroupPosition.ALONE
    }
}
