package com.firestream.chat.domain.repository

import android.net.Uri
import com.firestream.chat.domain.model.Message
import kotlinx.coroutines.flow.Flow

interface MessageRepository {
    fun getMessages(chatId: String): Flow<List<Message>>
    suspend fun sendMessage(chatId: String, content: String, recipientId: String): Result<Message>
    suspend fun deleteMessage(chatId: String, messageId: String): Result<Unit>
    suspend fun updateMessageStatus(chatId: String, messageId: String, status: String): Result<Unit>
    suspend fun editMessage(chatId: String, messageId: String, newContent: String): Result<Unit>
    suspend fun sendMediaMessage(chatId: String, uri: Uri, mimeType: String, recipientId: String): Result<Message>
}
