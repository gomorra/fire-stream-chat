package com.firestream.chat.domain.repository

import com.firestream.chat.domain.model.ListDiff
import com.firestream.chat.domain.model.Message
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow

interface MessageRepository {
    val uploadProgress: StateFlow<Map<String, Float>>
    fun getMessages(chatId: String): Flow<List<Message>>
    suspend fun sendMessage(chatId: String, content: String, recipientId: String, replyToId: String? = null, mentions: List<String> = emptyList(), emojiSizes: Map<Int, Float> = emptyMap()): Result<Message>
    suspend fun deleteMessage(chatId: String, messageId: String): Result<Unit>
    suspend fun updateMessageStatus(chatId: String, messageId: String, status: String): Result<Unit>
    suspend fun editMessage(chatId: String, messageId: String, newContent: String): Result<Unit>
    /** @param uri URI string (e.g. `content://...` or `file://...`). Parsed in the data layer. */
    suspend fun sendMediaMessage(chatId: String, uri: String, mimeType: String, recipientId: String, caption: String = ""): Result<Message>
    // Phase 1: reactions
    suspend fun addReaction(chatId: String, messageId: String, userId: String, emoji: String): Result<Unit>
    suspend fun removeReaction(chatId: String, messageId: String, userId: String): Result<Unit>
    // Phase 1: forwarding
    suspend fun forwardMessage(message: Message, targetChatId: String, recipientId: String): Result<Message>
    // Phase 1: voice messages
    /** @param uri URI string (e.g. `content://...` or `file://...`). Parsed in the data layer. */
    suspend fun sendVoiceMessage(chatId: String, uri: String, recipientId: String, durationSeconds: Int): Result<Message>
    // Phase 2: starred messages
    suspend fun starMessage(messageId: String, starred: Boolean): Result<Unit>
    fun getStarredMessages(): Flow<List<Message>>
    // Phase 2: search
    suspend fun searchMessages(query: String): List<Message>
    suspend fun searchMessagesInChat(chatId: String, query: String): List<Message>
    // Delivery / read receipts
    suspend fun markChatAsDelivered(chatId: String): Result<Unit>
    suspend fun markMessagesAsDelivered(chatId: String, messageIds: List<String>): Result<Unit>
    suspend fun markMessagesAsRead(chatId: String, messageIds: List<String>): Result<Unit>
    // Shared media
    fun getSharedMedia(chatId: String): Flow<List<Message>>
    fun getSharedMediaForUser(userId: String): Flow<List<Message>>
    // Phase 5.5: broadcast
    suspend fun sendBroadcastMessage(broadcastChatId: String, content: String, recipientIds: List<String>): Result<Message>
    // Lists
    suspend fun sendListMessage(chatId: String, listId: String, listTitle: String, listDiff: ListDiff? = null): Result<Message>
    // Generic message pinning
    suspend fun pinMessage(chatId: String, messageId: String, pinned: Boolean): Result<Unit>
    // Call log
    fun getCallLog(): Flow<List<Message>>
    // Location sharing
    suspend fun sendLocationMessage(chatId: String, latitude: Double, longitude: Double, recipientId: String, comment: String = ""): Result<Message>
    // Background sync
    suspend fun syncAllChatMessages(chatIds: List<String>)
    // Timers (.timer.set)
    suspend fun sendTimerMessage(chatId: String, durationMs: Long, caption: String?, recipientId: String): Result<Message>
    suspend fun cancelTimer(chatId: String, messageId: String): Result<Unit>
    suspend fun markTimerCompleted(chatId: String, messageId: String): Result<Unit>
}
