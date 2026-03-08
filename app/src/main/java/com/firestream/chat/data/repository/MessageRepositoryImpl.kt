package com.firestream.chat.data.repository

import android.net.Uri
import com.firestream.chat.data.crypto.EncryptedMessage
import com.firestream.chat.data.crypto.SignalManager
import com.firestream.chat.data.local.dao.MessageDao
import com.firestream.chat.data.local.entity.MessageEntity
import com.firestream.chat.data.remote.firebase.FirebaseAuthSource
import com.firestream.chat.data.remote.firebase.FirebaseStorageSource
import com.firestream.chat.data.remote.firebase.FirestoreMessageSource
import com.firestream.chat.domain.model.Message
import com.firestream.chat.domain.model.MessageStatus
import com.firestream.chat.domain.model.MessageType
import com.firestream.chat.domain.repository.MessageRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import org.json.JSONObject
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class MessageRepositoryImpl @Inject constructor(
    private val messageDao: MessageDao,
    private val messageSource: FirestoreMessageSource,
    private val authSource: FirebaseAuthSource,
    private val signalManager: SignalManager,
    private val storageSource: FirebaseStorageSource
) : MessageRepository {

    override fun getMessages(chatId: String): Flow<List<Message>> {
        val currentUid = authSource.currentUserId ?: ""

        return channelFlow {
            launch {
                signalManager.ensureInitialized()
                messageSource.observeMessages(chatId).collectLatest { rawList ->
                    for (raw in rawList) {
                        val existing = messageDao.getMessageById(raw.id)

                        // Update reactions from remote even if message is already cached
                        if (existing != null && existing.reactions != raw.reactions) {
                            val reactionsJson = JSONObject().apply {
                                raw.reactions.forEach { (k, v) -> put(k, v) }
                            }.toString()
                            messageDao.updateReactions(raw.id, reactionsJson)
                        }

                        if (raw.senderId == currentUid) {
                            if (existing != null) {
                                // Update status from remote if it changed (e.g. DELIVERED, READ)
                                val remoteStatus = runCatching { MessageStatus.valueOf(raw.status) }.getOrDefault(MessageStatus.SENT)
                                if (existing.status != remoteStatus.name) {
                                    messageDao.updateMessageStatus(raw.id, remoteStatus.name)
                                }
                                continue
                            }
                            // Skip if there's a pending optimistic message being replaced
                            val pending = messageDao.getPendingSendingMessage(raw.chatId, raw.timestamp, raw.senderId)
                            if (pending != null) continue
                            val content = raw.content ?: "[Sent message]"
                            val message = Message(
                                id = raw.id,
                                chatId = raw.chatId,
                                senderId = raw.senderId,
                                content = content,
                                type = runCatching { MessageType.valueOf(raw.type) }.getOrDefault(MessageType.TEXT),
                                mediaUrl = raw.mediaUrl,
                                mediaThumbnailUrl = raw.mediaThumbnailUrl,
                                status = runCatching { MessageStatus.valueOf(raw.status) }.getOrDefault(MessageStatus.SENT),
                                replyToId = raw.replyToId,
                                timestamp = raw.timestamp,
                                editedAt = raw.editedAt,
                                reactions = raw.reactions,
                                isForwarded = raw.isForwarded,
                                duration = raw.duration,
                                readBy = raw.readBy,
                                deliveredTo = raw.deliveredTo
                            )
                            messageDao.insertMessage(MessageEntity.fromDomain(message))
                            continue
                        }

                        if (existing != null && existing.editedAt == raw.editedAt) continue

                        val content = try {
                            when {
                                raw.editedAt != null && raw.content != null -> raw.content
                                raw.ciphertext != null && raw.signalType != null ->
                                    signalManager.decrypt(
                                        raw.senderId,
                                        EncryptedMessage(raw.ciphertext, raw.signalType)
                                    )
                                raw.content != null -> raw.content
                                else -> continue
                            }
                        } catch (_: Exception) {
                            "[Encrypted message — unable to decrypt]"
                        }

                        val message = Message(
                            id = raw.id,
                            chatId = raw.chatId,
                            senderId = raw.senderId,
                            content = content,
                            type = runCatching { MessageType.valueOf(raw.type) }.getOrDefault(MessageType.TEXT),
                            mediaUrl = raw.mediaUrl,
                            mediaThumbnailUrl = raw.mediaThumbnailUrl,
                            status = runCatching { MessageStatus.valueOf(raw.status) }.getOrDefault(MessageStatus.SENT),
                            replyToId = raw.replyToId,
                            timestamp = raw.timestamp,
                            editedAt = raw.editedAt,
                            reactions = raw.reactions,
                            isForwarded = raw.isForwarded,
                            duration = raw.duration,
                            readBy = raw.readBy,
                            deliveredTo = raw.deliveredTo
                        )
                        messageDao.insertMessage(MessageEntity.fromDomain(message))
                    }
                }
            }

            messageDao.getMessagesByChatId(chatId)
                .map { entities -> entities.map { it.toDomain() } }
                .collect { send(it) }
        }
    }

    override suspend fun sendMessage(
        chatId: String,
        content: String,
        recipientId: String,
        replyToId: String?
    ): Result<Message> {
        return try {
            val senderId = authSource.currentUserId ?: throw Exception("Not authenticated")
            val tempId = UUID.randomUUID().toString()
            val timestamp = System.currentTimeMillis()

            val optimisticMessage = Message(
                id = tempId,
                chatId = chatId,
                senderId = senderId,
                content = content,
                type = MessageType.TEXT,
                status = MessageStatus.SENDING,
                timestamp = timestamp,
                replyToId = replyToId
            )
            messageDao.insertMessage(MessageEntity.fromDomain(optimisticMessage))

            val remoteId: String
            if (recipientId.isNotEmpty()) {
                signalManager.ensureInitialized()
                val encrypted = signalManager.encrypt(recipientId, content)
                remoteId = messageSource.sendMessage(
                    chatId = chatId,
                    senderId = senderId,
                    ciphertext = encrypted.ciphertext,
                    signalType = encrypted.signalType,
                    type = MessageType.TEXT,
                    replyToId = replyToId,
                    timestamp = timestamp
                )
            } else {
                remoteId = messageSource.sendPlainMessage(
                    chatId = chatId,
                    senderId = senderId,
                    content = content,
                    type = MessageType.TEXT,
                    replyToId = replyToId,
                    timestamp = timestamp
                )
            }

            val sentMessage = optimisticMessage.copy(id = remoteId, status = MessageStatus.SENT)
            messageDao.replaceMessage(tempId, MessageEntity.fromDomain(sentMessage))

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

    override suspend fun editMessage(chatId: String, messageId: String, newContent: String): Result<Unit> {
        return try {
            val editedAt = System.currentTimeMillis()
            messageSource.editMessage(chatId, messageId, newContent, editedAt)
            messageDao.editMessage(messageId, newContent, editedAt)
            Result.success(Unit)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    override suspend fun sendMediaMessage(chatId: String, uri: Uri, mimeType: String, recipientId: String): Result<Message> {
        return try {
            val senderId = authSource.currentUserId ?: throw Exception("Not authenticated")
            val tempId = UUID.randomUUID().toString()
            val timestamp = System.currentTimeMillis()
            val messageType = if (mimeType.startsWith("image/")) MessageType.IMAGE else MessageType.DOCUMENT
            val filename = uri.lastPathSegment ?: "file"

            val optimisticMessage = Message(
                id = tempId,
                chatId = chatId,
                senderId = senderId,
                content = filename,
                type = messageType,
                status = MessageStatus.SENDING,
                timestamp = timestamp
            )
            messageDao.insertMessage(MessageEntity.fromDomain(optimisticMessage))

            val downloadUrl = storageSource.uploadMedia(chatId, tempId, uri, mimeType)

            val remoteId: String
            if (recipientId.isNotEmpty()) {
                signalManager.ensureInitialized()
                val encrypted = signalManager.encrypt(recipientId, filename)
                remoteId = messageSource.sendMessage(
                    chatId = chatId,
                    senderId = senderId,
                    ciphertext = encrypted.ciphertext,
                    signalType = encrypted.signalType,
                    type = messageType,
                    replyToId = null,
                    timestamp = timestamp,
                    mediaUrl = downloadUrl
                )
            } else {
                remoteId = messageSource.sendPlainMessage(
                    chatId = chatId,
                    senderId = senderId,
                    content = filename,
                    type = messageType,
                    replyToId = null,
                    timestamp = timestamp,
                    mediaUrl = downloadUrl
                )
            }

            val sentMessage = optimisticMessage.copy(id = remoteId, status = MessageStatus.SENT, mediaUrl = downloadUrl)
            messageDao.replaceMessage(tempId, MessageEntity.fromDomain(sentMessage))

            Result.success(sentMessage)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    override suspend fun addReaction(chatId: String, messageId: String, userId: String, emoji: String): Result<Unit> {
        return try {
            val existing = messageDao.getMessageById(messageId)
            val updatedReactions = (existing?.reactions ?: emptyMap()).toMutableMap()
            updatedReactions[userId] = emoji
            val reactionsJson = JSONObject().apply {
                updatedReactions.forEach { (k, v) -> put(k, v) }
            }.toString()
            messageDao.updateReactions(messageId, reactionsJson)
            messageSource.updateReactions(chatId, messageId, updatedReactions)
            Result.success(Unit)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    override suspend fun removeReaction(chatId: String, messageId: String, userId: String): Result<Unit> {
        return try {
            val existing = messageDao.getMessageById(messageId)
            val updatedReactions = (existing?.reactions ?: emptyMap()).toMutableMap()
            updatedReactions.remove(userId)
            val reactionsJson = JSONObject().apply {
                updatedReactions.forEach { (k, v) -> put(k, v) }
            }.toString()
            messageDao.updateReactions(messageId, reactionsJson)
            messageSource.updateReactions(chatId, messageId, updatedReactions)
            Result.success(Unit)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    override suspend fun forwardMessage(message: Message, targetChatId: String, recipientId: String): Result<Message> {
        return try {
            val senderId = authSource.currentUserId ?: throw Exception("Not authenticated")
            val tempId = UUID.randomUUID().toString()
            val timestamp = System.currentTimeMillis()

            val optimisticMessage = message.copy(
                id = tempId,
                chatId = targetChatId,
                senderId = senderId,
                status = MessageStatus.SENDING,
                timestamp = timestamp,
                isForwarded = true,
                replyToId = null,
                reactions = emptyMap()
            )
            messageDao.insertMessage(MessageEntity.fromDomain(optimisticMessage))

            val remoteId: String
            if (recipientId.isNotEmpty()) {
                signalManager.ensureInitialized()
                val encrypted = signalManager.encrypt(recipientId, message.content)
                remoteId = messageSource.sendMessage(
                    chatId = targetChatId,
                    senderId = senderId,
                    ciphertext = encrypted.ciphertext,
                    signalType = encrypted.signalType,
                    type = message.type,
                    replyToId = null,
                    timestamp = timestamp,
                    mediaUrl = message.mediaUrl,
                    isForwarded = true
                )
            } else {
                remoteId = messageSource.sendPlainMessage(
                    chatId = targetChatId,
                    senderId = senderId,
                    content = message.content,
                    type = message.type,
                    replyToId = null,
                    timestamp = timestamp,
                    mediaUrl = message.mediaUrl,
                    isForwarded = true
                )
            }

            val sentMessage = optimisticMessage.copy(id = remoteId, status = MessageStatus.SENT)
            messageDao.replaceMessage(tempId, MessageEntity.fromDomain(sentMessage))

            Result.success(sentMessage)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    override suspend fun sendVoiceMessage(chatId: String, uri: Uri, recipientId: String, durationSeconds: Int): Result<Message> {
        return try {
            val senderId = authSource.currentUserId ?: throw Exception("Not authenticated")
            val tempId = UUID.randomUUID().toString()
            val timestamp = System.currentTimeMillis()

            val optimisticMessage = Message(
                id = tempId,
                chatId = chatId,
                senderId = senderId,
                content = "Voice message",
                type = MessageType.VOICE,
                status = MessageStatus.SENDING,
                timestamp = timestamp,
                duration = durationSeconds
            )
            messageDao.insertMessage(MessageEntity.fromDomain(optimisticMessage))

            val downloadUrl = storageSource.uploadMedia(chatId, tempId, uri, "audio/aac")

            val remoteId: String
            if (recipientId.isNotEmpty()) {
                signalManager.ensureInitialized()
                val encrypted = signalManager.encrypt(recipientId, "Voice message")
                remoteId = messageSource.sendMessage(
                    chatId = chatId,
                    senderId = senderId,
                    ciphertext = encrypted.ciphertext,
                    signalType = encrypted.signalType,
                    type = MessageType.VOICE,
                    replyToId = null,
                    timestamp = timestamp,
                    mediaUrl = downloadUrl,
                    duration = durationSeconds
                )
            } else {
                remoteId = messageSource.sendPlainMessage(
                    chatId = chatId,
                    senderId = senderId,
                    content = "Voice message",
                    type = MessageType.VOICE,
                    replyToId = null,
                    timestamp = timestamp,
                    mediaUrl = downloadUrl,
                    duration = durationSeconds
                )
            }

            val sentMessage = optimisticMessage.copy(id = remoteId, status = MessageStatus.SENT, mediaUrl = downloadUrl)
            messageDao.replaceMessage(tempId, MessageEntity.fromDomain(sentMessage))

            Result.success(sentMessage)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    override suspend fun starMessage(messageId: String, starred: Boolean): Result<Unit> {
        return try {
            messageDao.setStarred(messageId, starred)
            Result.success(Unit)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    override fun getStarredMessages(): Flow<List<Message>> {
        return messageDao.getStarredMessages().map { entities -> entities.map { it.toDomain() } }
    }

    override suspend fun searchMessages(query: String): List<Message> {
        return try {
            messageDao.searchMessages(query).map { it.toDomain() }
        } catch (_: Exception) {
            emptyList()
        }
    }

    override suspend fun searchMessagesInChat(chatId: String, query: String): List<Message> {
        return try {
            messageDao.searchMessagesInChat(chatId, query).map { it.toDomain() }
        } catch (_: Exception) {
            emptyList()
        }
    }

    override suspend fun markChatAsDelivered(chatId: String): Result<Unit> {
        return try {
            val userId = authSource.currentUserId ?: throw Exception("Not authenticated")
            val now = System.currentTimeMillis()
            val undeliveredIds = messageSource.getUndeliveredMessageIds(chatId, userId)
            for (id in undeliveredIds) {
                try {
                    messageSource.markDelivered(chatId, id, userId, now)
                } catch (_: Exception) { }
            }
            Result.success(Unit)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    override suspend fun markMessagesAsDelivered(chatId: String, messageIds: List<String>): Result<Unit> {
        return try {
            val userId = authSource.currentUserId ?: throw Exception("Not authenticated")
            val now = System.currentTimeMillis()
            for (id in messageIds) {
                try {
                    messageSource.markDelivered(chatId, id, userId, now)
                    messageDao.updateMessageStatus(id, MessageStatus.DELIVERED.name)
                } catch (_: Exception) { }
            }
            Result.success(Unit)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    override suspend fun markMessagesAsRead(chatId: String, messageIds: List<String>): Result<Unit> {
        return try {
            val userId = authSource.currentUserId ?: throw Exception("Not authenticated")
            val now = System.currentTimeMillis()
            for (id in messageIds) {
                try {
                    messageSource.markRead(chatId, id, userId, now)
                    messageDao.updateMessageStatus(id, MessageStatus.READ.name)
                } catch (_: Exception) { }
            }
            Result.success(Unit)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    override fun getSharedMedia(chatId: String): Flow<List<Message>> {
        return messageDao.getSharedMedia(chatId).map { entities -> entities.map { it.toDomain() } }
    }

    override fun getSharedMediaForUser(userId: String): Flow<List<Message>> {
        return messageDao.getSharedMediaForUser(userId).map { entities -> entities.map { it.toDomain() } }
    }
}
