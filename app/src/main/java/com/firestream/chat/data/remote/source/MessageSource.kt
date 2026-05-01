package com.firestream.chat.data.remote.source

import com.firestream.chat.domain.model.MessageType
import kotlinx.coroutines.flow.Flow

/**
 * Backend-neutral message boundary. Reads emit [RawMessage] (the
 * encrypted-or-plaintext envelope before decryption); writes accept either a
 * pre-encrypted Signal payload ([sendMessage]) or plaintext ([sendPlainMessage])
 * — the repository decides which path to take based on the encryption gate.
 *
 * The PocketBase impl in v0 only honours the walking-skeleton subset:
 * [observeMessages], [sendPlainMessage], [lastContentFor], [markDelivered],
 * [markRead], [getUndeliveredMessageIds], [fetchMessages]. The other methods
 * throw [NotImplementedError] there until the follow-up plans land.
 */
interface MessageSource {

    /** Pure helper — preview string for `chats/{id}.lastMessageContent`. */
    fun lastContentFor(type: MessageType, plain: String = ""): String

    fun observeMessages(chatId: String): Flow<List<RawMessage>>
    suspend fun fetchMessages(chatId: String): List<RawMessage>

    suspend fun sendMessage(
        chatId: String,
        senderId: String,
        ciphertext: String,
        signalType: Int,
        type: MessageType,
        replyToId: String?,
        timestamp: Long,
        mediaUrl: String? = null,
        isForwarded: Boolean = false,
        duration: Int? = null,
        mentions: List<String> = emptyList(),
        plainContent: String = "",
        emojiSizes: Map<Int, Float> = emptyMap(),
        mediaWidth: Int? = null,
        mediaHeight: Int? = null,
        latitude: Double? = null,
        longitude: Double? = null,
        isHd: Boolean = false
    ): String

    suspend fun sendPlainMessage(
        chatId: String,
        senderId: String,
        content: String,
        type: MessageType,
        replyToId: String?,
        timestamp: Long,
        mediaUrl: String? = null,
        isForwarded: Boolean = false,
        duration: Int? = null,
        mentions: List<String> = emptyList(),
        emojiSizes: Map<Int, Float> = emptyMap(),
        mediaWidth: Int? = null,
        mediaHeight: Int? = null,
        latitude: Double? = null,
        longitude: Double? = null,
        isHd: Boolean = false
    ): String

    suspend fun editMessage(chatId: String, messageId: String, newContent: String, editedAt: Long)
    suspend fun deleteMessage(chatId: String, messageId: String)
    suspend fun updateMessageStatus(chatId: String, messageId: String, status: String)

    suspend fun getUndeliveredMessageIds(chatId: String, currentUserId: String): List<String>
    suspend fun markDelivered(chatId: String, messageId: String, userId: String, timestamp: Long)
    suspend fun markRead(chatId: String, messageId: String, userId: String, timestamp: Long)

    suspend fun sendPollMessage(
        chatId: String,
        senderId: String,
        pollData: Map<String, Any?>,
        timestamp: Long
    ): String

    suspend fun votePoll(
        chatId: String,
        messageId: String,
        userId: String,
        optionIds: List<String>
    )

    suspend fun closePoll(chatId: String, messageId: String)

    suspend fun sendCallMessage(
        chatId: String,
        senderId: String,
        endReason: String,
        durationSeconds: Int,
        timestamp: Long
    ): String

    suspend fun sendListMessage(
        chatId: String,
        senderId: String,
        listId: String,
        content: String,
        timestamp: Long,
        listDiff: Map<String, Any?>? = null
    ): String

    suspend fun updateListMessageDiff(
        chatId: String,
        messageId: String,
        content: String,
        listDiff: Map<String, Any?>,
        timestamp: Long
    )

    suspend fun pinMessage(chatId: String, messageId: String, pinned: Boolean)

    suspend fun updateReactions(chatId: String, messageId: String, reactions: Map<String, String>)
}
