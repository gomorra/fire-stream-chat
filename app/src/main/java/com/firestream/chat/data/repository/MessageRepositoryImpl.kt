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
import com.firestream.chat.domain.model.Poll
import com.firestream.chat.domain.model.PollOption
import com.firestream.chat.domain.repository.ChatRepository
import com.firestream.chat.domain.repository.MessageRepository
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

private const val POLL_CONTENT = "📊 Poll"

@Singleton
class MessageRepositoryImpl @Inject constructor(
    private val messageDao: MessageDao,
    private val messageSource: FirestoreMessageSource,
    private val authSource: FirebaseAuthSource,
    private val signalManager: SignalManager,
    private val storageSource: FirebaseStorageSource,
    private val chatRepository: dagger.Lazy<ChatRepository>
) : MessageRepository {

    override fun getMessages(chatId: String): Flow<List<Message>> {
        val currentUid = authSource.currentUserId ?: ""

        return channelFlow {
            launch {
                signalManager.ensureInitialized()
                messageSource.observeMessages(chatId).collectLatest { rawList ->
                    for (raw in rawList) {
                        val existing = messageDao.getMessageById(raw.id)

                        // Handle deletion update for any message (own or incoming)
                        if (existing != null && existing.deletedAt == null && raw.deletedAt != null) {
                            messageDao.softDeleteMessage(raw.id, raw.deletedAt!!)
                            continue
                        }

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
                                deliveredTo = raw.deliveredTo,
                                pollData = raw.pollData?.let { parsePollFromFirestore(it) },
                                mentions = raw.mentions,
                                deletedAt = raw.deletedAt
                            )
                            messageDao.insertMessage(MessageEntity.fromDomain(message))
                            continue
                        }

                        if (existing != null && existing.editedAt == raw.editedAt && existing.deletedAt == raw.deletedAt) continue

                        // Determine whether this message needs Signal decryption.
                        val needsDecryption = raw.ciphertext != null && raw.signalType != null
                                && !(raw.editedAt != null && raw.content != null)

                        // Guard: skip messages with no usable content (unless deleted).
                        if (raw.deletedAt == null && !needsDecryption && raw.content == null) continue

                        // Wrap decrypt+save in NonCancellable so that a collectLatest
                        // cancellation (from a new Firestore snapshot) cannot interrupt
                        // between Signal decryption (which advances the ratchet) and the
                        // Room insert (which records that decryption happened). Without
                        // this, a re-emitted snapshot would attempt to decrypt the same
                        // ciphertext again against an already-advanced ratchet, causing
                        // sporadic "unable to decrypt" errors.
                        withContext(NonCancellable) {
                            val content = when {
                                raw.deletedAt != null -> ""
                                else -> try {
                                    when {
                                        raw.editedAt != null && raw.content != null -> raw.content
                                        needsDecryption ->
                                            signalManager.decrypt(
                                                raw.senderId,
                                                EncryptedMessage(raw.ciphertext!!, raw.signalType!!)
                                            )
                                        else -> raw.content!!
                                    }
                                } catch (_: Exception) {
                                    "[Encrypted message — unable to decrypt]"
                                }
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
                                deliveredTo = raw.deliveredTo,
                                pollData = raw.pollData?.let { parsePollFromFirestore(it) },
                                mentions = raw.mentions,
                                deletedAt = raw.deletedAt
                            )
                            messageDao.insertMessage(MessageEntity.fromDomain(message))
                        }
                    }
                }
            }

            messageDao.getMessagesByChatId(chatId)
                .conflate()
                .map { entities -> entities.map { it.toDomain() } }
                .collect { send(it) }
        }
    }

    override suspend fun sendMessage(
        chatId: String,
        content: String,
        recipientId: String,
        replyToId: String?,
        mentions: List<String>
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
                replyToId = replyToId,
                mentions = mentions
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
                    timestamp = timestamp,
                    mentions = mentions
                )
            } else {
                remoteId = messageSource.sendPlainMessage(
                    chatId = chatId,
                    senderId = senderId,
                    content = content,
                    type = MessageType.TEXT,
                    replyToId = replyToId,
                    timestamp = timestamp,
                    mentions = mentions
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
            val deletedAt = System.currentTimeMillis()
            messageSource.deleteMessage(chatId, messageId)
            messageDao.softDeleteMessage(messageId, deletedAt)
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
                } catch (_: Exception) { }
            }
            // Batch-update Room in one shot so the DAO flow emits only once
            messageDao.updateMessageStatusBatch(messageIds, MessageStatus.DELIVERED.name)
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
                } catch (_: Exception) { }
            }
            // Batch-update Room in one shot so the DAO flow emits only once
            messageDao.updateMessageStatusBatch(messageIds, MessageStatus.READ.name)
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

    override suspend fun sendPoll(
        chatId: String,
        question: String,
        options: List<String>,
        isMultipleChoice: Boolean,
        isAnonymous: Boolean
    ): Result<Message> {
        return try {
            val senderId = authSource.currentUserId ?: throw Exception("Not authenticated")
            val timestamp = System.currentTimeMillis()

            val pollOptions = options.mapIndexed { index, text ->
                PollOption(id = "opt_$index", text = text)
            }
            val poll = Poll(
                question = question,
                options = pollOptions,
                isMultipleChoice = isMultipleChoice,
                isAnonymous = isAnonymous
            )

            val pollDataMap = buildPollFirestoreMap(poll)

            val remoteId = messageSource.sendPollMessage(
                chatId = chatId,
                senderId = senderId,
                pollData = pollDataMap,
                timestamp = timestamp
            )

            val message = Message(
                id = remoteId,
                chatId = chatId,
                senderId = senderId,
                content = POLL_CONTENT,
                type = MessageType.POLL,
                status = MessageStatus.SENT,
                timestamp = timestamp,
                pollData = poll
            )
            messageDao.insertMessage(MessageEntity.fromDomain(message))

            Result.success(message)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    override suspend fun votePoll(chatId: String, messageId: String, optionIds: List<String>): Result<Unit> {
        return try {
            val userId = authSource.currentUserId ?: throw Exception("Not authenticated")
            val entity = messageDao.getMessageById(messageId) ?: throw Exception("Message not found")
            val poll = entity.toDomain().pollData ?: throw Exception("Not a poll message")
            if (poll.isClosed) throw Exception("Poll is closed")

            messageSource.votePoll(chatId, messageId, userId, optionIds)

            // Update local cache
            val updatedOptions = poll.options.map { option ->
                val voters = option.voterIds.toMutableList()
                if (optionIds.contains(option.id)) {
                    if (!voters.contains(userId)) voters.add(userId)
                } else if (!poll.isMultipleChoice) {
                    voters.remove(userId)
                }
                option.copy(voterIds = voters)
            }
            val updatedPoll = poll.copy(options = updatedOptions)
            val updatedMessage = entity.toDomain().copy(pollData = updatedPoll)
            messageDao.insertMessage(MessageEntity.fromDomain(updatedMessage))

            Result.success(Unit)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    override suspend fun closePoll(chatId: String, messageId: String): Result<Unit> {
        return try {
            messageSource.closePoll(chatId, messageId)

            val entity = messageDao.getMessageById(messageId)
            if (entity != null) {
                val msg = entity.toDomain()
                val updatedPoll = msg.pollData?.copy(isClosed = true)
                messageDao.insertMessage(MessageEntity.fromDomain(msg.copy(pollData = updatedPoll)))
            }

            Result.success(Unit)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    @Suppress("UNCHECKED_CAST")
    private fun parsePollFromFirestore(data: Map<String, Any?>): Poll {
        val options = (data["options"] as? List<Map<String, Any?>>)?.map { opt ->
            PollOption(
                id = opt["id"] as? String ?: "",
                text = opt["text"] as? String ?: "",
                voterIds = (opt["voterIds"] as? List<String>) ?: emptyList()
            )
        } ?: emptyList()

        return Poll(
            question = data["question"] as? String ?: "",
            options = options,
            isMultipleChoice = data["isMultipleChoice"] as? Boolean ?: false,
            isAnonymous = data["isAnonymous"] as? Boolean ?: false,
            isClosed = data["isClosed"] as? Boolean ?: false
        )
    }

    override suspend fun sendBroadcastMessage(
        broadcastChatId: String,
        content: String,
        recipientIds: List<String>
    ): Result<Message> {
        return try {
            val senderId = authSource.currentUserId ?: throw Exception("Not authenticated")
            val timestamp = System.currentTimeMillis()

            // 1. Save message to broadcast chat (sender's record)
            val broadcastRemoteId = messageSource.sendPlainMessage(
                chatId = broadcastChatId,
                senderId = senderId,
                content = content,
                type = MessageType.TEXT,
                replyToId = null,
                timestamp = timestamp
            )
            val broadcastMessage = Message(
                id = broadcastRemoteId,
                chatId = broadcastChatId,
                senderId = senderId,
                content = content,
                type = MessageType.TEXT,
                status = MessageStatus.SENT,
                timestamp = timestamp
            )
            messageDao.insertMessage(MessageEntity.fromDomain(broadcastMessage))

            // 2. Fan out to each recipient's individual chat
            val semaphore = kotlinx.coroutines.sync.Semaphore(5)
            kotlinx.coroutines.coroutineScope {
                recipientIds.map { recipientId ->
                    async {
                        semaphore.acquire()
                        try {
                            // Get or create the 1:1 chat with each recipient
                            val chatResult = chatRepository.get().getOrCreateChat(recipientId)
                            val individualChat = chatResult.getOrThrow()
                            // Send as encrypted 1:1 message
                            signalManager.ensureInitialized()
                            val encrypted = signalManager.encrypt(recipientId, content)
                            messageSource.sendMessage(
                                chatId = individualChat.id,
                                senderId = senderId,
                                ciphertext = encrypted.ciphertext,
                                signalType = encrypted.signalType,
                                type = MessageType.TEXT,
                                replyToId = null,
                                timestamp = timestamp
                            )
                        } catch (_: Exception) {
                            // Best-effort delivery to each recipient
                        } finally {
                            semaphore.release()
                        }
                    }
                }.awaitAll()
            }

            Result.success(broadcastMessage)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    private fun buildPollFirestoreMap(poll: Poll): Map<String, Any?> {
        return mapOf(
            "question" to poll.question,
            "isMultipleChoice" to poll.isMultipleChoice,
            "isAnonymous" to poll.isAnonymous,
            "isClosed" to poll.isClosed,
            "options" to poll.options.map { option ->
                mapOf(
                    "id" to option.id,
                    "text" to option.text,
                    "voterIds" to option.voterIds
                )
            }
        )
    }
}
