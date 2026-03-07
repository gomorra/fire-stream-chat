package com.firestream.chat.data.repository

import com.firestream.chat.data.crypto.EncryptedMessage
import com.firestream.chat.data.crypto.SignalManager
import com.firestream.chat.data.local.dao.MessageDao
import com.firestream.chat.data.local.entity.MessageEntity
import com.firestream.chat.data.remote.firebase.FirebaseAuthSource
import com.firestream.chat.data.remote.firebase.FirestoreMessageSource
import com.firestream.chat.domain.model.Message
import com.firestream.chat.domain.model.MessageStatus
import com.firestream.chat.domain.model.MessageType
import com.firestream.chat.domain.repository.MessageRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.channelFlow
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
    private val signalManager: SignalManager
) : MessageRepository {

    override fun getMessages(chatId: String): Flow<List<Message>> {
        val currentUid = authSource.currentUserId ?: ""

        return messageSource.observeMessages(chatId)
            .map { rawList ->
                // Ensure Signal keys are ready before any decryption attempt.
                // ensureInitialized() is a no-op after the first call, so this is cheap.
                signalManager.ensureInitialized()

                rawList.mapNotNull { raw ->
                    try {
                        val content = when {
                            // Own messages: always use the Room-cached plaintext so we never
                            // expose ciphertext in our own UI (and avoid a pointless decrypt).
                            raw.senderId == currentUid -> {
                                messageDao.getMessageById(raw.id)?.content
                                    ?: raw.content ?: "[message]"
                            }

                            // Received encrypted message.
                            // CRITICAL: check Room cache first. Signal pre-keys are single-use —
                            // the library removes them after the first successful decrypt. If
                            // Firestore re-emits this snapshot (e.g. a new message arrives) and
                            // we try to decrypt the PreKeySignalMessage again, the pre-key is
                            // already gone and we get InvalidKeyIdException. The Room cache
                            // prevents re-decryption.
                            raw.ciphertext != null && raw.signalType != null -> {
                                messageDao.getMessageById(raw.id)?.content
                                    ?: signalManager.decrypt(
                                        raw.senderId,
                                        EncryptedMessage(raw.ciphertext, raw.signalType)
                                    )
                            }

                            // Plaintext fallback (group messages; Phase 4 adds Sender Keys)
                            raw.content != null -> raw.content

                            else -> return@mapNotNull null
                        }
                        Message(
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
                    } catch (_: Exception) {
                        // Decrypt failed and no Room cache yet — return a placeholder so the
                        // message is still visible rather than silently vanishing.
                        Message(
                            id = raw.id,
                            chatId = raw.chatId,
                            senderId = raw.senderId,
                            content = "[Encrypted message — unable to decrypt]",
                            timestamp = raw.timestamp,
                            status = runCatching { MessageStatus.valueOf(raw.status) }.getOrDefault(MessageStatus.SENT)
                        )
                    }
                }
            }
            .onEach { messages ->
                // Persist decrypted content to Room so subsequent Firestore snapshots
                // hit the cache instead of re-decrypting.
                messageDao.insertMessages(messages.map { MessageEntity.fromDomain(it) })
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
}
