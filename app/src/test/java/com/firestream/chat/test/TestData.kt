package com.firestream.chat.test

import com.firestream.chat.domain.model.Chat
import com.firestream.chat.domain.model.ChatType
import com.firestream.chat.domain.model.Message
import com.firestream.chat.domain.model.MessageStatus
import com.firestream.chat.domain.model.MessageType
import com.firestream.chat.domain.model.User

/**
 * Builder functions for domain models with sensible test defaults.
 * Prefer these over inline `Message(...)` / `Chat(...)` constructions in tests.
 */
object TestData {

    fun message(
        id: String = "msg-1",
        chatId: String = "chat-1",
        senderId: String = "user-1",
        content: String = "hello",
        type: MessageType = MessageType.TEXT,
        status: MessageStatus = MessageStatus.SENT,
        timestamp: Long = 1_700_000_000_000L,
    ): Message = Message(
        id = id,
        chatId = chatId,
        senderId = senderId,
        content = content,
        type = type,
        status = status,
        timestamp = timestamp,
    )

    fun user(
        uid: String = "user-1",
        displayName: String = "Alice",
        phoneNumber: String = "+10000000000",
        avatarUrl: String? = null,
        isOnline: Boolean = false,
    ): User = User(
        uid = uid,
        displayName = displayName,
        phoneNumber = phoneNumber,
        avatarUrl = avatarUrl,
        isOnline = isOnline,
    )

    fun chat(
        id: String = "chat-1",
        type: ChatType = ChatType.INDIVIDUAL,
        name: String? = null,
        participants: List<String> = listOf("user-1", "user-2"),
        unreadCount: Int = 0,
        avatarUrl: String? = null,
    ): Chat = Chat(
        id = id,
        type = type,
        name = name,
        participants = participants,
        unreadCount = unreadCount,
        avatarUrl = avatarUrl,
    )
}
