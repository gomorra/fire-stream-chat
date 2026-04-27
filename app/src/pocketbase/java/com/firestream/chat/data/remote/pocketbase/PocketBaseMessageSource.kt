package com.firestream.chat.data.remote.pocketbase

import com.firestream.chat.data.remote.source.MessageSource
import com.firestream.chat.data.remote.source.RawMessage
import com.firestream.chat.domain.model.MessageType
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Step 4 stub. Real impl in step 6 (only the walking-skeleton subset:
 * observeMessages, sendPlainMessage, lastContentFor, markDelivered, markRead,
 * getUndeliveredMessageIds, fetchMessages). The other ~18 methods stay
 * NotImplementedError throughout v0.
 */
@Singleton
class PocketBaseMessageSource @Inject constructor() : MessageSource {

    // Pure helper — mirrors FirestoreMessageSource.lastContentFor so chat-list
    // previews are identical across flavors. Kept valid even pre-step-6 so the
    // Hilt graph doesn't crash on background renders.
    override fun lastContentFor(type: MessageType, plain: String): String = when (type) {
        MessageType.IMAGE -> if (plain.isNotBlank()) "📷 $plain" else "📷 Photo"
        MessageType.DOCUMENT -> "📎 File"
        MessageType.VOICE -> "🎤 Voice message"
        MessageType.POLL -> "📊 Poll"
        MessageType.LIST -> if (plain.isBlank()) "📋 List" else plain
        MessageType.LOCATION -> "📍 Location"
        MessageType.CALL -> "📞 Voice call"
        else -> plain.ifBlank { "Message" }
    }

    override fun observeMessages(chatId: String): Flow<List<RawMessage>> = emptyFlow()

    override suspend fun fetchMessages(chatId: String): List<RawMessage> =
        throw NotImplementedError("PB v0 stub")

    override suspend fun sendMessage(
        chatId: String,
        senderId: String,
        ciphertext: String,
        signalType: Int,
        type: MessageType,
        replyToId: String?,
        timestamp: Long,
        mediaUrl: String?,
        isForwarded: Boolean,
        duration: Int?,
        mentions: List<String>,
        plainContent: String,
        emojiSizes: Map<Int, Float>,
        mediaWidth: Int?,
        mediaHeight: Int?,
        latitude: Double?,
        longitude: Double?
    ): String = throw NotImplementedError("PB v0 stub")

    override suspend fun sendPlainMessage(
        chatId: String,
        senderId: String,
        content: String,
        type: MessageType,
        replyToId: String?,
        timestamp: Long,
        mediaUrl: String?,
        isForwarded: Boolean,
        duration: Int?,
        mentions: List<String>,
        emojiSizes: Map<Int, Float>,
        mediaWidth: Int?,
        mediaHeight: Int?,
        latitude: Double?,
        longitude: Double?
    ): String = throw NotImplementedError("PB v0 stub")

    override suspend fun editMessage(
        chatId: String,
        messageId: String,
        newContent: String,
        editedAt: Long
    ): Unit = throw NotImplementedError("PB v0 stub")

    override suspend fun deleteMessage(chatId: String, messageId: String): Unit =
        throw NotImplementedError("PB v0 stub")

    override suspend fun updateMessageStatus(chatId: String, messageId: String, status: String): Unit =
        throw NotImplementedError("PB v0 stub")

    override suspend fun getUndeliveredMessageIds(chatId: String, currentUserId: String): List<String> =
        throw NotImplementedError("PB v0 stub")

    override suspend fun markDelivered(chatId: String, messageId: String, userId: String, timestamp: Long): Unit =
        throw NotImplementedError("PB v0 stub")

    override suspend fun markRead(chatId: String, messageId: String, userId: String, timestamp: Long): Unit =
        throw NotImplementedError("PB v0 stub")

    override suspend fun sendPollMessage(
        chatId: String,
        senderId: String,
        pollData: Map<String, Any?>,
        timestamp: Long
    ): String = throw NotImplementedError("PB v0 stub")

    override suspend fun votePoll(
        chatId: String,
        messageId: String,
        userId: String,
        optionIds: List<String>
    ): Unit = throw NotImplementedError("PB v0 stub")

    override suspend fun closePoll(chatId: String, messageId: String): Unit =
        throw NotImplementedError("PB v0 stub")

    override suspend fun sendCallMessage(
        chatId: String,
        senderId: String,
        endReason: String,
        durationSeconds: Int,
        timestamp: Long
    ): String = throw NotImplementedError("PB v0 stub")

    override suspend fun sendListMessage(
        chatId: String,
        senderId: String,
        listId: String,
        content: String,
        timestamp: Long,
        listDiff: Map<String, Any?>?
    ): String = throw NotImplementedError("PB v0 stub")

    override suspend fun updateListMessageDiff(
        chatId: String,
        messageId: String,
        content: String,
        listDiff: Map<String, Any?>,
        timestamp: Long
    ): Unit = throw NotImplementedError("PB v0 stub")

    override suspend fun pinMessage(chatId: String, messageId: String, pinned: Boolean): Unit =
        throw NotImplementedError("PB v0 stub")

    override suspend fun updateReactions(
        chatId: String,
        messageId: String,
        reactions: Map<String, String>
    ): Unit = throw NotImplementedError("PB v0 stub")
}
