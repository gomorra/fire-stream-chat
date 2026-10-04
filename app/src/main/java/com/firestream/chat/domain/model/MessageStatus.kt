package com.firestream.chat.domain.model

enum class MessageStatus {
    SENDING,
    SENT,
    DELIVERED,
    READ,
    FAILED
}

enum class MessageType {
    TEXT,
    IMAGE,
    VIDEO,
    VOICE,
    DOCUMENT,
    POLL,
    CALL,
    LIST,
    LOCATION,
    TIMER,

    /** One sticker from the library. The message names it by `stickerId` and carries no bytes of its own. */
    STICKER,

    /** An animated image sent as it is, never re-encoded. */
    GIF
}

enum class ChatType {
    INDIVIDUAL,
    GROUP,
    BROADCAST
}
