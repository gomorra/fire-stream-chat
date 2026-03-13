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
    CALL
}

enum class ChatType {
    INDIVIDUAL,
    GROUP,
    BROADCAST
}
