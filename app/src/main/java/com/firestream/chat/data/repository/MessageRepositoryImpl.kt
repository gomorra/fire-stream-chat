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

        // Architecture: Room is the single display source.
        //
        // - Sender's own messages are already in Room from the optimistic insert in
        //   sendMessage(), so they appear immediately with the correct plaintext.
        //
        // - Received messages arrive via the Firestore snapshot listener that runs in
        //   a background coroutine. Each new encrypted message is decrypted once and
        //   written to Room, which triggers a new emission from the Room Flow below.
        //
        // This eliminates the race between the Firestore snapshot listener and the
        // Room insert of the remote-ID version of the sender's own messages, which
        // previously caused "[message]" placeholders.

        return channelFlow {
            // Background: Firestore → decrypt received messages → write to Room.
            // Room's live query re-emits automatically after each insert.
            launch {
                signalManager.ensureInitialized()
                messageSource.observeMessages(chatId).collectLatest { rawList ->
                    for (raw in rawList) {
                        val existing = messageDao.getMessageById(raw.id)

                        if (raw.senderId == currentUid) {
                            // Own message: already in Room from the optimistic insert in sendMessage().
                            // If Room was wiped (e.g. emulator restart), restore it from Firestore
                            // with whatever plaintext is available (group) or a placeholder (encrypted).
                            if (existing != null) continue
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
                                editedAt = raw.editedAt
                            )
                            messageDao.insertMessage(MessageEntity.fromDomain(message))
                            continue
                        }

                        // Skip if already decrypted/cached with the same edit version.
                        // This also stops retrying messages that previously failed decryption
                        // (they're stored in Room with the error text).
                        if (existing != null && existing.editedAt == raw.editedAt) continue

                        val content = try {
                            when {
                                // Edited plaintext: skip decryption, use content directly.
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
                            editedAt = raw.editedAt
                        )
                        messageDao.insertMessage(MessageEntity.fromDomain(message))
                    }
                }
            }

            // Primary display source: Room live query.
            // Emits whenever Room data changes (own sends, received decrypts, status updates).
            messageDao.getMessagesByChatId(chatId)
                .map { entities -> entities.map { it.toDomain() } }
                .collect { send(it) }
        }
    }

    override suspend fun sendMessage(chatId: String, content: String, recipientId: String): Result<Message> {
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
                timestamp = timestamp
            )
            // Optimistic insert so the sender sees the message immediately
            messageDao.insertMessage(MessageEntity.fromDomain(optimisticMessage))

            val remoteId: String
            if (recipientId.isNotEmpty()) {
                // Individual chat — use Signal Protocol encryption
                signalManager.ensureInitialized()
                val encrypted = signalManager.encrypt(recipientId, content)
                remoteId = messageSource.sendMessage(
                    chatId = chatId,
                    senderId = senderId,
                    ciphertext = encrypted.ciphertext,
                    signalType = encrypted.signalType,
                    type = MessageType.TEXT,
                    replyToId = null,
                    timestamp = timestamp
                )
            } else {
                // Group chat — plaintext for now (Phase 4: Sender Keys)
                remoteId = messageSource.sendPlainMessage(
                    chatId = chatId,
                    senderId = senderId,
                    content = content,
                    type = MessageType.TEXT,
                    replyToId = null,
                    timestamp = timestamp
                )
            }

            // Replace optimistic entry with the confirmed remote ID
            messageDao.deleteMessage(tempId)
            val sentMessage = optimisticMessage.copy(id = remoteId, status = MessageStatus.SENT)
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

            messageDao.deleteMessage(tempId)
            val sentMessage = optimisticMessage.copy(id = remoteId, status = MessageStatus.SENT, mediaUrl = downloadUrl)
            messageDao.insertMessage(MessageEntity.fromDomain(sentMessage))

            Result.success(sentMessage)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }
}
