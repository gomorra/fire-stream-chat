// region: AGENT-NOTE
// Responsibility: Message CRUD across all message types — text / image / voice /
//   document / poll / location / list / call. Routes through Signal encryption
//   in release builds (with user opt-out from PreferencesDataStore) and plaintext
//   in debug. Owns the local-first send pipeline (Room first, upload + remoteId
//   rename second), media download with in-flight dedup, per-chat backfill scan,
//   block-state filtering on receive.
// Owns: MessageEntity rows + uploadProgress: StateFlow<Map<String, Float>>.
// Collaborators: MessageDao, ChatDao, FirestoreMessageSource, FirestoreUserSource,
//   FirebaseStorageSource, SignalManager (release encrypt/decrypt path),
//   PreferencesDataStore (encryption opt-out, AutoDownloadOption), MediaFileManager,
//   ImageCompressor, ConnectivityManager (WiFi-only download check).
// Don't put here: poll vote/close (PollRepositoryImpl), list mutations
//   (ListRepositoryImpl), call signalling (CallRepositoryImpl). Class is large
//   (~1100 LOC) — Phase 2 plan adds a section-comment TOC and 1100-LOC ceiling.
//   See docs/PATTERNS.md for the encryption-guard and AppError-wrap conventions.
// endregion

package com.firestream.chat.data.repository

import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.Uri
import android.util.Log
import com.firestream.chat.data.crypto.EncryptedMessage
import com.firestream.chat.data.crypto.SignalManager
import com.firestream.chat.data.local.AutoDownloadOption
import com.firestream.chat.data.local.PreferencesDataStore
import com.firestream.chat.data.local.dao.ChatDao
import com.firestream.chat.data.local.dao.MessageDao
import com.firestream.chat.data.local.entity.MessageEntity
import com.firestream.chat.data.remote.source.AuthSource
import com.firestream.chat.data.remote.source.StorageSource
import com.firestream.chat.data.remote.source.MessageSource
import com.firestream.chat.data.remote.source.UserSource
import com.firestream.chat.data.util.ImageCompressor
import com.firestream.chat.data.util.MediaFileManager
import com.firestream.chat.data.util.resultOf
import com.firestream.chat.domain.model.ListDiff
import com.firestream.chat.domain.model.Message
import com.firestream.chat.domain.model.MessageStatus
import com.firestream.chat.domain.model.MessageType
import com.firestream.chat.domain.repository.ChatRepository
import com.firestream.chat.domain.repository.ListRepository
import com.firestream.chat.domain.repository.MessageRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import com.firestream.chat.BuildConfig
import java.io.File
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

private val AUTO_DOWNLOAD_TYPES = setOf(MessageType.IMAGE, MessageType.VIDEO, MessageType.DOCUMENT)
private const val ERR_NOT_AUTHENTICATED = "Not authenticated"
private const val ERR_USER_BLOCKED = "Cannot send messages to a blocked user"
private const val VOICE_MESSAGE_CONTENT = "Voice message"
private const val LOCATION_DEFAULT_CONTENT = "Shared location"
private const val TAG = "MessageRepo"

// How long a list-update bubble stays "open" for further merging. Once the gap
// between the previous list update and the next one exceeds this window, the
// next update starts a fresh bubble instead of silently extending the old one.
private const val LIST_MESSAGE_MERGE_WINDOW_MS = 10L * 60L * 1000L

@Singleton
class MessageRepositoryImpl @Inject constructor(
    private val messageDao: MessageDao,
    private val chatDao: ChatDao,
    private val messageSource: MessageSource,
    private val authSource: AuthSource,
    private val signalManager: SignalManager,
    private val storageSource: StorageSource,
    private val chatRepository: dagger.Lazy<ChatRepository>,
    private val listRepository: dagger.Lazy<ListRepository>,
    private val mediaFileManager: MediaFileManager,
    private val imageCompressor: ImageCompressor,
    private val preferencesDataStore: PreferencesDataStore,
    private val connectivityManager: ConnectivityManager,
    private val userSource: UserSource
) : MessageRepository {

    private val downloadScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val _uploadProgress = MutableStateFlow<Map<String, Float>>(emptyMap())
    override val uploadProgress: StateFlow<Map<String, Float>> = _uploadProgress.asStateFlow()

    override fun getMessages(chatId: String): Flow<List<Message>> {
        val currentUid = authSource.currentUserId ?: ""

        return channelFlow {
            downloadPendingMediaForChat(chatId)
            launch {
                try {
                try { signalManager.ensureInitialized() } catch (_: Throwable) { }
                messageSource.observeMessages(chatId).collectLatest { rawList ->
                    val blockedUserIds = try {
                        if (currentUid.isNotEmpty()) userSource.getBlockedUserIds(currentUid) else emptySet()
                    } catch (_: Exception) { emptySet() }
                    for (raw in rawList) {
                        // Skip messages from users the current user has blocked.
                        // Log so "message isn't appearing" scenarios are diagnosable via logcat.
                        if (raw.senderId != currentUid && raw.senderId in blockedUserIds) {
                            Log.d(TAG, "observeMessages: filtered blocked sender=${raw.senderId} msg=${raw.id} chat=$chatId")
                            continue
                        }

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
                                deletedAt = raw.deletedAt,
                                emojiSizes = raw.emojiSizes,
                                listId = raw.listId,
                                listDiff = raw.listDiff?.let { ListDiff.fromMap(it) },
                                isPinned = raw.isPinned,
                                mediaWidth = raw.mediaWidth,
                                mediaHeight = raw.mediaHeight,
                                latitude = raw.latitude,
                                longitude = raw.longitude,
                                isHd = raw.isHd
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

                            // Preserve local-only fields that are not stored in Firestore
                            val preservedLocalUri = existing?.localUri
                            val preservedIsStarred = existing?.isStarred ?: false

                            val message = Message(
                                id = raw.id,
                                chatId = raw.chatId,
                                senderId = raw.senderId,
                                content = content,
                                type = runCatching { MessageType.valueOf(raw.type) }.getOrDefault(MessageType.TEXT),
                                mediaUrl = raw.mediaUrl,
                                mediaThumbnailUrl = raw.mediaThumbnailUrl,
                                localUri = preservedLocalUri,
                                isStarred = preservedIsStarred,
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
                                deletedAt = raw.deletedAt,
                                emojiSizes = raw.emojiSizes,
                                listId = raw.listId,
                                listDiff = raw.listDiff?.let { ListDiff.fromMap(it) },
                                isPinned = raw.isPinned,
                                mediaWidth = raw.mediaWidth,
                                mediaHeight = raw.mediaHeight,
                                latitude = raw.latitude,
                                longitude = raw.longitude,
                                isHd = raw.isHd
                            )
                            messageDao.insertMessage(MessageEntity.fromDomain(message))

                            // Auto-download media for incoming messages
                            if (message.mediaUrl != null && message.localUri == null &&
                                message.type in AUTO_DOWNLOAD_TYPES
                            ) {
                                tryAutoDownload(message)
                            }

                            // Sync shared/unshared list to Room so ListsScreen updates immediately
                            if (message.type == MessageType.LIST && message.listId != null &&
                                (message.listDiff?.shared == true || message.listDiff?.unshared == true)
                            ) {
                                listRepository.get().fetchAndCacheList(message.listId!!)
                            }
                        }
                    }
                }
            } catch (_: Throwable) { }
            }

            messageDao.getMessagesByChatId(chatId)
                .conflate()
                .map { entities -> entities.map { it.toDomain() } }
                .collect { send(it) }
        }
    }

    /**
     * Routes a send through Signal encryption or the plaintext branch based on the
     * build flavor and whether a 1:1 recipient is known. Encapsulates the
     * "encrypt for recipient unless debug/empty-recipient" decision that was
     * previously duplicated at every send site in this class.
     *
     * Callers pass all optional fields (mediaUrl, mentions, etc.) — unused ones
     * fall through to the underlying `FirestoreMessageSource` defaults.
     */
    private suspend fun sendEncryptedOrPlain(
        chatId: String,
        senderId: String,
        recipientId: String,
        plaintext: String,
        type: MessageType,
        timestamp: Long,
        replyToId: String? = null,
        mentions: List<String> = emptyList(),
        emojiSizes: Map<Int, Float> = emptyMap(),
        mediaUrl: String? = null,
        mediaWidth: Int? = null,
        mediaHeight: Int? = null,
        duration: Int? = null,
        latitude: Double? = null,
        longitude: Double? = null,
        isForwarded: Boolean = false,
        isHd: Boolean = false,
    ): String {
        return if (
            recipientId.isNotEmpty() &&
            BuildConfig.SUPPORTS_SIGNAL &&
            !BuildConfig.DEBUG &&
            preferencesDataStore.e2eEncryptionEnabledFlow.first()
        ) {
            signalManager.ensureInitialized()
            val encrypted = signalManager.encrypt(recipientId, plaintext)
            messageSource.sendMessage(
                chatId = chatId,
                senderId = senderId,
                ciphertext = encrypted.ciphertext,
                signalType = encrypted.signalType,
                type = type,
                replyToId = replyToId,
                timestamp = timestamp,
                mediaUrl = mediaUrl,
                isForwarded = isForwarded,
                duration = duration,
                mentions = mentions,
                plainContent = plaintext,
                emojiSizes = emojiSizes,
                mediaWidth = mediaWidth,
                mediaHeight = mediaHeight,
                latitude = latitude,
                longitude = longitude,
                isHd = isHd,
            )
        } else {
            messageSource.sendPlainMessage(
                chatId = chatId,
                senderId = senderId,
                content = plaintext,
                type = type,
                replyToId = replyToId,
                timestamp = timestamp,
                mediaUrl = mediaUrl,
                isForwarded = isForwarded,
                duration = duration,
                mentions = mentions,
                emojiSizes = emojiSizes,
                mediaWidth = mediaWidth,
                mediaHeight = mediaHeight,
                latitude = latitude,
                longitude = longitude,
                isHd = isHd,
            )
        }
    }

    /**
     * Send a text message to a chat.
     *
     * @param recipientId The 1:1 peer user id for INDIVIDUAL chats, used by the
     *   block check and Signal encryption. **For GROUP and BROADCAST chats,
     *   callers must pass an empty string** — Signal sessions are 1:1, so
     *   group/broadcast messages must travel through the plaintext branch
     *   below. Passing an arbitrary group member as the recipient will encrypt
     *   the message for that single member and leave every other participant
     *   unable to read it.
     */
    override suspend fun sendMessage(
        chatId: String,
        content: String,
        recipientId: String,
        replyToId: String?,
        mentions: List<String>,
        emojiSizes: Map<Int, Float>
    ): Result<Message> = resultOf {
        val senderId = authSource.currentUserId ?: throw Exception(ERR_NOT_AUTHENTICATED)
        if (recipientId.isNotEmpty() && userSource.isUserBlocked(senderId, recipientId)) {
            throw Exception(ERR_USER_BLOCKED)
        }
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
            mentions = mentions,
            emojiSizes = emojiSizes
        )
        messageDao.insertMessage(MessageEntity.fromDomain(optimisticMessage))

        val remoteId = sendEncryptedOrPlain(
            chatId = chatId,
            senderId = senderId,
            recipientId = recipientId,
            plaintext = content,
            type = MessageType.TEXT,
            timestamp = timestamp,
            replyToId = replyToId,
            mentions = mentions,
            emojiSizes = emojiSizes,
        )

        val sentMessage = optimisticMessage.copy(id = remoteId, status = MessageStatus.SENT)
        messageDao.replaceMessage(tempId, MessageEntity.fromDomain(sentMessage))
        chatDao.updateLastMessage(chatId, remoteId, messageSource.lastContentFor(MessageType.TEXT, content), timestamp)
        sentMessage
    }

    override suspend fun deleteMessage(chatId: String, messageId: String): Result<Unit> = resultOf {
        val deletedAt = System.currentTimeMillis()
        messageSource.deleteMessage(chatId, messageId)
        messageDao.softDeleteMessage(messageId, deletedAt)
    }

    override suspend fun updateMessageStatus(chatId: String, messageId: String, status: String): Result<Unit> = resultOf {
        messageSource.updateMessageStatus(chatId, messageId, status)
        messageDao.updateMessageStatus(messageId, status)
    }

    override suspend fun editMessage(chatId: String, messageId: String, newContent: String): Result<Unit> = resultOf {
        val editedAt = System.currentTimeMillis()
        messageSource.editMessage(chatId, messageId, newContent, editedAt)
        messageDao.editMessage(messageId, newContent, editedAt)
    }

    /**
     * Send a media (image / video / document) message to a chat.
     *
     * @param recipientId See [sendMessage] — must be an empty string for GROUP
     *   and BROADCAST chats so the plaintext branch is used; Signal sessions
     *   are 1:1 and cannot address a group.
     */
    override suspend fun sendMediaMessage(chatId: String, uri: String, mimeType: String, recipientId: String, caption: String): Result<Message> = resultOf {
        val senderId = authSource.currentUserId ?: throw Exception(ERR_NOT_AUTHENTICATED)
        if (recipientId.isNotEmpty() && userSource.isUserBlocked(senderId, recipientId)) {
            throw Exception(ERR_USER_BLOCKED)
        }
        val parsedUri = Uri.parse(uri)
        val tempId = UUID.randomUUID().toString()
        val timestamp = System.currentTimeMillis()
        val isImage = mimeType.startsWith("image/")
        val messageType = if (isImage) MessageType.IMAGE else MessageType.DOCUMENT

        // For images: compress/process and store locally
        val localFile: File?
        val mediaWidth: Int?
        val mediaHeight: Int?
        val uploadUri: Uri
        val uploadMimeType: String
        var tempCompressedFile: File? = null
        var isHd = false

        if (isImage) {
            val fullQuality = preferencesDataStore.sendImagesFullQualityFlow.first()
            val result = imageCompressor.processImage(parsedUri, fullQuality)
            tempCompressedFile = result.file
            isHd = fullQuality

            // Copy processed image to permanent local storage
            localFile = mediaFileManager.copyToLocal(
                chatId, tempId, Uri.fromFile(result.file), "jpg"
            )
            mediaWidth = result.width
            mediaHeight = result.height
            uploadUri = Uri.fromFile(localFile)
            uploadMimeType = result.mimeType
        } else {
            localFile = null
            mediaWidth = null
            mediaHeight = null
            uploadUri = parsedUri
            uploadMimeType = mimeType
        }

        val optimisticMessage = Message(
            id = tempId,
            chatId = chatId,
            senderId = senderId,
            content = caption,
            type = messageType,
            status = MessageStatus.SENDING,
            timestamp = timestamp,
            localUri = localFile?.absolutePath ?: uri,
            mediaWidth = mediaWidth,
            mediaHeight = mediaHeight,
            isHd = isHd
        )
        messageDao.insertMessage(MessageEntity.fromDomain(optimisticMessage))

        val downloadUrl = try {
            storageSource.uploadMedia(chatId, tempId, uploadUri, uploadMimeType) { progress ->
                _uploadProgress.update { map -> map + (tempId to progress) }
            }
        } finally {
            _uploadProgress.update { it - tempId }
            // Clean up temp compressed file from cacheDir/compressed/
            tempCompressedFile?.let { if (it.exists()) it.delete() }
        }

        val remoteId = sendEncryptedOrPlain(
            chatId = chatId,
            senderId = senderId,
            recipientId = recipientId,
            plaintext = caption,
            type = messageType,
            timestamp = timestamp,
            mediaUrl = downloadUrl,
            mediaWidth = mediaWidth,
            mediaHeight = mediaHeight,
            isHd = isHd,
        )

        val sentMessage = optimisticMessage.copy(
            id = remoteId,
            status = MessageStatus.SENT,
            mediaUrl = downloadUrl,
            localUri = localFile?.absolutePath,
            mediaWidth = mediaWidth,
            mediaHeight = mediaHeight,
            isHd = isHd
        )
        messageDao.replaceMessage(tempId, MessageEntity.fromDomain(sentMessage))
        chatDao.updateLastMessage(chatId, remoteId, messageSource.lastContentFor(messageType, caption), timestamp)

        // Rename local file from tempId to remoteId to prevent orphaned files
        val finalLocalUri: String? = if (localFile != null) {
            val newFile = mediaFileManager.getLocalFile(chatId, remoteId, "jpg")
            if (localFile.renameTo(newFile)) {
                messageDao.updateLocalUri(remoteId, newFile.absolutePath)
                newFile.absolutePath
            } else {
                localFile.absolutePath
            }
        } else null

        sentMessage.copy(localUri = finalLocalUri)
    }

    override suspend fun addReaction(chatId: String, messageId: String, userId: String, emoji: String): Result<Unit> = resultOf {
        val existing = messageDao.getMessageById(messageId)
        val updatedReactions = (existing?.reactions ?: emptyMap()).toMutableMap()
        updatedReactions[userId] = emoji
        val reactionsJson = JSONObject().apply {
            updatedReactions.forEach { (k, v) -> put(k, v) }
        }.toString()
        messageDao.updateReactions(messageId, reactionsJson)
        messageSource.updateReactions(chatId, messageId, updatedReactions)
    }

    override suspend fun removeReaction(chatId: String, messageId: String, userId: String): Result<Unit> = resultOf {
        val existing = messageDao.getMessageById(messageId)
        val updatedReactions = (existing?.reactions ?: emptyMap()).toMutableMap()
        updatedReactions.remove(userId)
        val reactionsJson = JSONObject().apply {
            updatedReactions.forEach { (k, v) -> put(k, v) }
        }.toString()
        messageDao.updateReactions(messageId, reactionsJson)
        messageSource.updateReactions(chatId, messageId, updatedReactions)
    }

    override suspend fun forwardMessage(message: Message, targetChatId: String, recipientId: String): Result<Message> = resultOf {
        val senderId = authSource.currentUserId ?: throw Exception(ERR_NOT_AUTHENTICATED)
        if (recipientId.isNotEmpty() && userSource.isUserBlocked(senderId, recipientId)) {
            throw Exception(ERR_USER_BLOCKED)
        }
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

        val remoteId = sendEncryptedOrPlain(
            chatId = targetChatId,
            senderId = senderId,
            recipientId = recipientId,
            plaintext = message.content,
            type = message.type,
            timestamp = timestamp,
            mediaUrl = message.mediaUrl,
            mediaWidth = message.mediaWidth,
            mediaHeight = message.mediaHeight,
            isForwarded = true,
        )

        val sentMessage = optimisticMessage.copy(id = remoteId, status = MessageStatus.SENT)
        messageDao.replaceMessage(tempId, MessageEntity.fromDomain(sentMessage))
        chatDao.updateLastMessage(targetChatId, remoteId, messageSource.lastContentFor(message.type, message.content), timestamp)
        sentMessage
    }

    override suspend fun sendVoiceMessage(chatId: String, uri: String, recipientId: String, durationSeconds: Int): Result<Message> = resultOf {
        val senderId = authSource.currentUserId ?: throw Exception(ERR_NOT_AUTHENTICATED)
        if (recipientId.isNotEmpty() && userSource.isUserBlocked(senderId, recipientId)) {
            throw Exception(ERR_USER_BLOCKED)
        }
        val parsedUri = Uri.parse(uri)
        val tempId = UUID.randomUUID().toString()
        val timestamp = System.currentTimeMillis()

        val optimisticMessage = Message(
            id = tempId,
            chatId = chatId,
            senderId = senderId,
            content = VOICE_MESSAGE_CONTENT,
            type = MessageType.VOICE,
            status = MessageStatus.SENDING,
            timestamp = timestamp,
            duration = durationSeconds
        )
        messageDao.insertMessage(MessageEntity.fromDomain(optimisticMessage))

        val downloadUrl = storageSource.uploadMedia(chatId, tempId, parsedUri, "audio/aac")

        val remoteId = sendEncryptedOrPlain(
            chatId = chatId,
            senderId = senderId,
            recipientId = recipientId,
            plaintext = VOICE_MESSAGE_CONTENT,
            type = MessageType.VOICE,
            timestamp = timestamp,
            mediaUrl = downloadUrl,
            duration = durationSeconds,
        )

        val sentMessage = optimisticMessage.copy(id = remoteId, status = MessageStatus.SENT, mediaUrl = downloadUrl)
        messageDao.replaceMessage(tempId, MessageEntity.fromDomain(sentMessage))
        chatDao.updateLastMessage(chatId, remoteId, messageSource.lastContentFor(MessageType.VOICE), timestamp)
        sentMessage
    }

    override suspend fun starMessage(messageId: String, starred: Boolean): Result<Unit> = resultOf {
        messageDao.setStarred(messageId, starred)
    }

    override fun getStarredMessages(): Flow<List<Message>> {
        return messageDao.getStarredMessages().map { entities -> entities.map { it.toDomain() } }
    }

    override suspend fun searchMessages(query: String): List<Message> {
        return try {
            val regex = wordBoundaryRegex(query)
            messageDao.searchMessages(query)
                .filter { regex.containsMatchIn(it.content) }
                .map { it.toDomain() }
        } catch (_: Exception) {
            emptyList()
        }
    }

    override suspend fun searchMessagesInChat(chatId: String, query: String): List<Message> {
        return try {
            val regex = wordBoundaryRegex(query)
            messageDao.searchMessagesInChat(chatId, query)
                .filter { regex.containsMatchIn(it.content) }
                .map { it.toDomain() }
        } catch (_: Exception) {
            emptyList()
        }
    }

    private fun wordBoundaryRegex(query: String) =
        Regex("\\b${Regex.escape(query)}\\b", RegexOption.IGNORE_CASE)

    override suspend fun markChatAsDelivered(chatId: String): Result<Unit> = resultOf {
        val userId = authSource.currentUserId ?: throw Exception(ERR_NOT_AUTHENTICATED)
        val now = System.currentTimeMillis()
        val undeliveredIds = messageSource.getUndeliveredMessageIds(chatId, userId)
        for (id in undeliveredIds) {
            try {
                messageSource.markDelivered(chatId, id, userId, now)
            } catch (_: Exception) { }
        }
    }

    override suspend fun markMessagesAsDelivered(chatId: String, messageIds: List<String>): Result<Unit> = resultOf {
        val userId = authSource.currentUserId ?: throw Exception(ERR_NOT_AUTHENTICATED)
        val now = System.currentTimeMillis()
        for (id in messageIds) {
            try {
                messageSource.markDelivered(chatId, id, userId, now)
            } catch (_: Exception) { }
        }
        // Batch-update Room in one shot so the DAO flow emits only once
        messageDao.updateMessageStatusBatch(messageIds, MessageStatus.DELIVERED.name)
    }

    override suspend fun markMessagesAsRead(chatId: String, messageIds: List<String>): Result<Unit> = resultOf {
        val userId = authSource.currentUserId ?: throw Exception(ERR_NOT_AUTHENTICATED)
        val now = System.currentTimeMillis()
        for (id in messageIds) {
            try {
                messageSource.markRead(chatId, id, userId, now)
            } catch (_: Exception) { }
        }
        // Batch-update Room in one shot so the DAO flow emits only once
        messageDao.updateMessageStatusBatch(messageIds, MessageStatus.READ.name)
    }

    override fun getSharedMedia(chatId: String): Flow<List<Message>> {
        return messageDao.getSharedMedia(chatId).map { entities -> entities.map { it.toDomain() } }
    }

    override fun getSharedMediaForUser(userId: String): Flow<List<Message>> {
        return messageDao.getSharedMediaForUser(userId).map { entities -> entities.map { it.toDomain() } }
    }

    override suspend fun sendBroadcastMessage(
        broadcastChatId: String,
        content: String,
        recipientIds: List<String>
    ): Result<Message> = resultOf {
        val senderId = authSource.currentUserId ?: throw Exception(ERR_NOT_AUTHENTICATED)
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
        chatDao.updateLastMessage(broadcastChatId, broadcastRemoteId, messageSource.lastContentFor(MessageType.TEXT, content), timestamp)

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
                        // Send as 1:1 message (encrypted in release, plain in debug)
                        val fanOutRemoteId = sendEncryptedOrPlain(
                            chatId = individualChat.id,
                            senderId = senderId,
                            recipientId = recipientId,
                            plaintext = content,
                            type = MessageType.TEXT,
                            timestamp = timestamp,
                        )
                        chatDao.updateLastMessage(individualChat.id, fanOutRemoteId, messageSource.lastContentFor(MessageType.TEXT, content), timestamp)
                    } catch (_: Exception) {
                        // Best-effort delivery to each recipient
                    } finally {
                        semaphore.release()
                    }
                }
            }.awaitAll()
        }

        broadcastMessage
    }

    override suspend fun sendListMessage(
        chatId: String,
        listId: String,
        listTitle: String,
        listDiff: ListDiff?
    ): Result<Message> = resultOf {
        val senderId = authSource.currentUserId ?: throw Exception(ERR_NOT_AUTHENTICATED)
        val timestamp = System.currentTimeMillis()
        val content = when {
            listDiff?.shared == true -> "\uD83D\uDCCB Shared list: $listTitle"
            listDiff?.unshared == true -> "\uD83D\uDCCB Removed list: $listTitle"
            listDiff?.deleted == true -> "\uD83D\uDCCB Deleted list: $listTitle"
            else -> "\uD83D\uDCCB List updated: $listTitle"
        }

        // Merge into the last message if it's a diff bubble for the same list from this user,
        // but only while the previous update is still within the merge window. Once the gap
        // exceeds LIST_MESSAGE_MERGE_WINDOW_MS, a new bubble is started so later activity is
        // visible instead of silently extending a stale bubble.
        if (listDiff != null && !listDiff.deleted && !listDiff.unshared && !listDiff.shared) {
            val lastEntity = messageDao.getLastMessageByChatId(chatId)
            if (lastEntity != null) {
                val lastMessage = lastEntity.toDomain()
                if (lastMessage.type == MessageType.LIST
                    && lastMessage.listId == listId
                    && lastMessage.listDiff != null
                    && !lastMessage.listDiff.deleted
                    && !lastMessage.listDiff.unshared
                    && !lastMessage.listDiff.shared
                    && lastMessage.senderId == senderId
                    && (timestamp - lastMessage.timestamp) < LIST_MESSAGE_MERGE_WINDOW_MS
                ) {
                    val mergedDiff = ListDiff.accumulate(lastMessage.listDiff, listDiff)
                    messageSource.updateListMessageDiff(chatId, lastMessage.id, content, mergedDiff.toMap(), timestamp)
                    val updatedMessage = lastMessage.copy(
                        content = content,
                        listDiff = mergedDiff,
                        timestamp = timestamp,
                        editedAt = timestamp
                    )
                    messageDao.insertMessage(MessageEntity.fromDomain(updatedMessage))
                    chatDao.updateLastMessage(chatId, lastMessage.id, messageSource.lastContentFor(MessageType.LIST, content), timestamp)
                    return@resultOf updatedMessage
                }
            }
        }

        val remoteId = messageSource.sendListMessage(
            chatId = chatId,
            senderId = senderId,
            listId = listId,
            content = content,
            timestamp = timestamp,
            listDiff = listDiff?.toMap()
        )

        val message = Message(
            id = remoteId,
            chatId = chatId,
            senderId = senderId,
            content = content,
            type = MessageType.LIST,
            status = MessageStatus.SENT,
            timestamp = timestamp,
            listId = listId,
            listDiff = listDiff
        )
        messageDao.insertMessage(MessageEntity.fromDomain(message))
        chatDao.updateLastMessage(chatId, remoteId, messageSource.lastContentFor(MessageType.LIST, content), timestamp)
        message
    }

    override suspend fun pinMessage(
        chatId: String,
        messageId: String,
        pinned: Boolean
    ): Result<Unit> = resultOf {
        messageSource.pinMessage(chatId, messageId, pinned)
        // Update local cache
        val entity = messageDao.getMessageById(messageId)
        if (entity != null) {
            val updated = entity.toDomain().copy(isPinned = pinned)
            messageDao.insertMessage(MessageEntity.fromDomain(updated))
        }
    }

    override suspend fun sendLocationMessage(
        chatId: String,
        latitude: Double,
        longitude: Double,
        recipientId: String,
        comment: String
    ): Result<Message> = resultOf {
        val senderId = authSource.currentUserId ?: throw Exception(ERR_NOT_AUTHENTICATED)
        if (recipientId.isNotEmpty() && userSource.isUserBlocked(senderId, recipientId)) {
            throw Exception(ERR_USER_BLOCKED)
        }
        val tempId = UUID.randomUUID().toString()
        val timestamp = System.currentTimeMillis()
        val content = comment.ifBlank { LOCATION_DEFAULT_CONTENT }

        val optimisticMessage = Message(
            id = tempId,
            chatId = chatId,
            senderId = senderId,
            content = content,
            type = MessageType.LOCATION,
            status = MessageStatus.SENDING,
            timestamp = timestamp,
            latitude = latitude,
            longitude = longitude
        )
        messageDao.insertMessage(MessageEntity.fromDomain(optimisticMessage))

        val remoteId = messageSource.sendPlainMessage(
            chatId = chatId,
            senderId = senderId,
            content = content,
            type = MessageType.LOCATION,
            replyToId = null,
            timestamp = timestamp,
            latitude = latitude,
            longitude = longitude
        )

        val sentMessage = optimisticMessage.copy(id = remoteId, status = MessageStatus.SENT)
        messageDao.replaceMessage(tempId, MessageEntity.fromDomain(sentMessage))
        chatDao.updateLastMessage(chatId, remoteId, messageSource.lastContentFor(MessageType.LOCATION), timestamp)
        sentMessage
    }

    override fun getCallLog(): Flow<List<Message>> =
        messageDao.getCallMessages().map { entities -> entities.map { it.toDomain() } }

    override suspend fun syncAllChatMessages(chatIds: List<String>) {
        val currentUid = authSource.currentUserId ?: return
        if (chatIds.isEmpty()) return

        try { signalManager.ensureInitialized() } catch (_: Throwable) { }

        val blockedUserIds = try {
            userSource.getBlockedUserIds(currentUid)
        } catch (_: Exception) { emptySet() }

        val semaphore = Semaphore(3)
        coroutineScope {
            chatIds.map { chatId ->
                async(Dispatchers.IO) {
                    semaphore.withPermit {
                        try {
                            syncChatMessages(chatId, currentUid, blockedUserIds)
                        } catch (_: Throwable) { }
                    }
                }
            }.awaitAll()
        }
    }

    private suspend fun syncChatMessages(
        chatId: String,
        currentUid: String,
        blockedUserIds: Set<String>
    ) {
        val rawList = messageSource.fetchMessages(chatId)
        for (raw in rawList) {
            if (raw.senderId != currentUid && raw.senderId in blockedUserIds) {
                Log.d(TAG, "syncChatMessages: filtered blocked sender=${raw.senderId} msg=${raw.id} chat=$chatId")
                continue
            }

            val existing = messageDao.getMessageById(raw.id)

            if (existing != null && existing.deletedAt == null && raw.deletedAt != null) {
                messageDao.softDeleteMessage(raw.id, raw.deletedAt!!)
                continue
            }

            if (existing != null && existing.reactions != raw.reactions) {
                val reactionsJson = JSONObject().apply {
                    raw.reactions.forEach { (k, v) -> put(k, v) }
                }.toString()
                messageDao.updateReactions(raw.id, reactionsJson)
            }

            if (raw.senderId == currentUid) {
                if (existing != null) {
                    val remoteStatus = runCatching { MessageStatus.valueOf(raw.status) }.getOrDefault(MessageStatus.SENT)
                    if (existing.status != remoteStatus.name) {
                        messageDao.updateMessageStatus(raw.id, remoteStatus.name)
                    }
                    continue
                }
                val content = raw.content ?: "[Sent message]"
                val message = Message(
                    id = raw.id, chatId = raw.chatId, senderId = raw.senderId,
                    content = content,
                    type = runCatching { MessageType.valueOf(raw.type) }.getOrDefault(MessageType.TEXT),
                    mediaUrl = raw.mediaUrl, mediaThumbnailUrl = raw.mediaThumbnailUrl,
                    status = runCatching { MessageStatus.valueOf(raw.status) }.getOrDefault(MessageStatus.SENT),
                    replyToId = raw.replyToId, timestamp = raw.timestamp, editedAt = raw.editedAt,
                    reactions = raw.reactions, isForwarded = raw.isForwarded, duration = raw.duration,
                    readBy = raw.readBy, deliveredTo = raw.deliveredTo,
                    pollData = raw.pollData?.let { parsePollFromFirestore(it) },
                    mentions = raw.mentions, deletedAt = raw.deletedAt,
                    emojiSizes = raw.emojiSizes, listId = raw.listId,
                    listDiff = raw.listDiff?.let { ListDiff.fromMap(it) },
                    isPinned = raw.isPinned, mediaWidth = raw.mediaWidth, mediaHeight = raw.mediaHeight,
                    latitude = raw.latitude, longitude = raw.longitude,
                    isHd = raw.isHd
                )
                messageDao.insertMessage(MessageEntity.fromDomain(message))
                continue
            }

            // Incoming messages
            if (existing != null && existing.editedAt == raw.editedAt && existing.deletedAt == raw.deletedAt) continue

            val needsDecryption = raw.ciphertext != null && raw.signalType != null
                    && !(raw.editedAt != null && raw.content != null)
            if (raw.deletedAt == null && !needsDecryption && raw.content == null) continue

            val content = when {
                raw.deletedAt != null -> ""
                else -> try {
                    when {
                        raw.editedAt != null && raw.content != null -> raw.content
                        needsDecryption -> signalManager.decrypt(
                            raw.senderId, EncryptedMessage(raw.ciphertext!!, raw.signalType!!)
                        )
                        else -> raw.content!!
                    }
                } catch (_: Throwable) {
                    "[Encrypted message — unable to decrypt]"
                }
            }

            val preservedLocalUri = existing?.localUri
            val preservedIsStarred = existing?.isStarred ?: false

            val message = Message(
                id = raw.id, chatId = raw.chatId, senderId = raw.senderId,
                content = content,
                type = runCatching { MessageType.valueOf(raw.type) }.getOrDefault(MessageType.TEXT),
                mediaUrl = raw.mediaUrl, mediaThumbnailUrl = raw.mediaThumbnailUrl,
                localUri = preservedLocalUri, isStarred = preservedIsStarred,
                status = runCatching { MessageStatus.valueOf(raw.status) }.getOrDefault(MessageStatus.SENT),
                replyToId = raw.replyToId, timestamp = raw.timestamp, editedAt = raw.editedAt,
                reactions = raw.reactions, isForwarded = raw.isForwarded, duration = raw.duration,
                readBy = raw.readBy, deliveredTo = raw.deliveredTo,
                pollData = raw.pollData?.let { parsePollFromFirestore(it) },
                mentions = raw.mentions, deletedAt = raw.deletedAt,
                emojiSizes = raw.emojiSizes, listId = raw.listId,
                listDiff = raw.listDiff?.let { ListDiff.fromMap(it) },
                isPinned = raw.isPinned, mediaWidth = raw.mediaWidth, mediaHeight = raw.mediaHeight,
                latitude = raw.latitude, longitude = raw.longitude,
                isHd = raw.isHd
            )
            messageDao.insertMessage(MessageEntity.fromDomain(message))
        }
    }

    private fun downloadPendingMediaForChat(chatId: String) {
        downloadScope.launch {
            try {
                val option = preferencesDataStore.autoDownloadFlow.first()
                if (option == AutoDownloadOption.NEVER) return@launch
                if (option == AutoDownloadOption.WIFI_ONLY && !isOnWifi()) return@launch

                val pending = messageDao.getMessagesWithoutLocalMediaForChat(chatId)
                for (entity in pending) {
                    try {
                        val url = entity.mediaUrl ?: continue
                        val file = mediaFileManager.downloadAndSave(chatId, entity.id, url)
                        messageDao.updateLocalUri(entity.id, file.absolutePath)
                    } catch (_: Exception) { }
                }
            } catch (_: Exception) { }
        }
    }

    private fun tryAutoDownload(message: Message) {
        downloadScope.launch {
            try {
                val option = preferencesDataStore.autoDownloadFlow.first()
                if (option == AutoDownloadOption.NEVER) return@launch
                if (option == AutoDownloadOption.WIFI_ONLY && !isOnWifi()) return@launch

                val file = mediaFileManager.downloadAndSave(
                    message.chatId, message.id, message.mediaUrl!!
                )
                messageDao.updateLocalUri(message.id, file.absolutePath)
            } catch (_: Exception) {
                // Best-effort download; failures are silently ignored
            }
        }
    }

    private fun isOnWifi(): Boolean {
        val network = connectivityManager.activeNetwork ?: return false
        val caps = connectivityManager.getNetworkCapabilities(network) ?: return false
        return caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)
    }
}
