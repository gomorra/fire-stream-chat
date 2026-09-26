package com.firestream.chat.domain.model

/**
 * Whether a message named by id (a notification or reminder deep link) can
 * still turn up in the chat, answered by
 * [com.firestream.chat.domain.repository.MessageRepository.checkMessageAvailability].
 */
enum class MessageAvailability {
    /** Already in the local store. */
    LOCAL,

    /** The backend holds it; the open chat's listener has yet to bring it in. */
    PENDING,

    /** The backend answered and does not hold it (or its sender is blocked). */
    GONE,

    /** The backend could not be asked (offline, read failed). */
    UNKNOWN,
}
