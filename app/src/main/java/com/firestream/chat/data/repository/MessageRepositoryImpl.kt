package com.firestream.chat.data.repository

import com.firestream.chat.data.local.dao.MessageDao
import com.firestream.chat.data.local.entity.MessageEntity
import com.firestream.chat.data.remote.firebase.FirebaseAuthSource
import com.firestream.chat.data.remote.firebase.FirestoreMessageSource
import com.firestream.chat.domain.model.Message
import com.firestream.chat.domain.model.MessageStatus
import com.firestream.chat.domain.model.MessageType
import com.firestream.chat.domain.repository.MessageRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onEach
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class MessageRepositoryImpl @Inject constructor(
    private val messageDao: MessageDao,
    private val messageSource: FirestoreMessageSource,
    private val authSource: FirebaseAuthSource
) : MessageRepository {

    override fun getMessages(chatId: String): Flow<List<Message>> {
        // Listen to Firestore for real-time updates and cache locally
        return messageSource.observeMessages(chatId).onEach { messages ->
            messageDao.insertMessages(messages.map { MessageEntity.fromDomain(it) })
        }
    }

    override suspend fun sendMessage(chatId: String, content: String, recipientId: String): Result<Message> {
        return try {
            val senderId = authSource.currentUserId ?: throw Exception("Not authenticated")
            val message = Message(
                id = UUID.randomUUID().toString(),
                chatId = chatId,
                senderId = senderId,
                content = content,
                type = MessageType.TEXT,
                status = MessageStatus.SENDING,
                timestamp = System.currentTimeMillis()
            )

            // Save locally first for optimistic UI
            messageDao.insertMessage(MessageEntity.fromDomain(message))

            // Send to Firestore (in Phase 3, encryption will be added here)
            val remoteId = messageSource.sendMessage(chatId, message)

            val sentMessage = message.copy(id = remoteId, status = MessageStatus.SENT)
            messageDao.insertMessage(MessageEntity.fromDomain(sentMessage))

            Result.success(sentMessage)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    override suspend fun deleteMessage(chatId: String, messageId: String): Result<Unit> {
        return try {
            messageSource.deleteMessage(chatId, messageId)
            messageDao.deleteMessage(messageId)
            Result.success(Unit)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    override suspend fun updateMessageStatus(chatId: String, messageId: String, status: String): Result<Unit> {
        return try {
            messageSource.updateMessageStatus(chatId, messageId, status)
            messageDao.updateMessageStatus(messageId, status)
            Result.success(Unit)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }
}
