package com.firestream.chat.domain.repository

import android.net.Uri
import com.firestream.chat.domain.model.Message
import kotlinx.coroutines.flow.Flow

interface MessageRepository {
    fun getMessages(chatId: String): Flow<List<Message>>
    suspend fun sendMessage(chatId: String, content: String, recipientId: String, replyToId: String? = null): Result<Message>
    suspend fun deleteMessage(chatId: String, messageId: String): Result<Unit>
    suspend fun updateMessageStatus(chatId: String, messageId: String, status: String): Result<Unit>
    suspend fun editMessage(chatId: String, messageId: String, newContent: String): Result<Unit>
    suspend fun sendMediaMessage(chatId: String, uri: Uri, mimeType: String, recipientId: String): Result<Message>
    // Phase 1: reactions
    suspend fun addReaction(chatId: String, messageId: String, userId: String, emoji: String): Result<Unit>
    suspend fun removeReaction(chatId: String, messageId: String, userId: String): Result<Unit>
    // Phase 1: forwarding
    suspend fun forwardMessage(message: Message, targetChatId: String, recipientId: String): Result<Message>
    // Phase 1: voice messages
    suspend fun sendVoiceMessage(chatId: String, uri: Uri, recipientId: String, durationSeconds: Int): Result<Message>
    // Phase 2: starred messages
    suspend fun starMessage(messageId: String, starred: Boolean): Result<Unit>
    fun getStarredMessages(): Flow<List<Message>>
    // Phase 2: search
    suspend fun searchMessages(query: String): List<Message>
    suspend fun searchMessagesInChat(chatId: String, query: String): List<Message>
}
