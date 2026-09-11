// region: AGENT-NOTE
// Responsibility: Delivers one own message that is already a Room row to the
//   backend — compress / transcode, video thumbnail, upload, the encrypted-or-plain
//   write, the SENT swap and the chat preview. Reads the row and skips every step
//   it already records, so a first attempt and a retry run the same code.
//   Covers TEXT, IMAGE, VIDEO, DOCUMENT, VOICE, LOCATION.
// Owns: uploadProgress (MessageRepository re-exposes it); the BuildConfig.DEBUG /
//   e2e opt-out encryption gate (sendEncryptedOrPlain); the if-absent decision
//   for a re-attempt.
// Collaborators: MessageRepositoryImpl (only caller — send* after the optimistic
//   insert and block check, retryFailedMessage after the FAILED→SENDING flip;
//   forwardMessage and the broadcast fan-out borrow sendEncryptedOrPlain),
//   MessageDao, ChatDao, MessageSource, StorageSource, SignalManager,
//   ImageCompressor, VideoTranscoder, MediaFileManager, PreferencesDataStore.
// Don't put here: validation, the optimistic insert, the block check and FAILED
//   marking — they stay in MessageRepositoryImpl, whose call site is where a
//   definite block and an unanswerable block check part ways
//   (.claude/plans/offline-outbox.md §2.5). No semaphore of its own either —
//   "MediaProcessingLimiter owns the concurrency bound" (docs/PATTERNS.md).
// endregion

package com.firestream.chat.data.outbox

import android.net.Uri
import com.firestream.chat.BuildConfig
import com.firestream.chat.data.crypto.SignalManager
import com.firestream.chat.data.local.PreferencesDataStore
import com.firestream.chat.data.local.dao.ChatDao
import com.firestream.chat.data.local.dao.MessageDao
import com.firestream.chat.data.local.entity.MessageEntity
import com.firestream.chat.data.remote.source.MessageSource
import com.firestream.chat.data.remote.source.StorageSource
import com.firestream.chat.data.util.ImageCompressor
import com.firestream.chat.data.util.MediaFileManager
import com.firestream.chat.data.util.VideoTranscoder
import com.firestream.chat.domain.model.Message
import com.firestream.chat.domain.model.MessageStatus
import com.firestream.chat.domain.model.MessageType
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

/** Types the pipeline re-encodes into a media file the app writes itself. */
private val LOCAL_FILE_TYPES = setOf(MessageType.IMAGE, MessageType.VIDEO)
private const val VOICE_MIME_TYPE = "audio/aac"
private const val FALLBACK_MIME_TYPE = "application/octet-stream"

/** What an IMAGE (and a video thumbnail) or a VIDEO is re-encoded to — the one owner of the jpg/mp4 rule. */
private enum class LocalFormat(val extension: String, val mimeType: String) {
    JPEG("jpg", "image/jpeg"),
    MP4("mp4", "video/mp4");

    companion object {
        fun of(type: MessageType) = if (type == MessageType.VIDEO) MP4 else JPEG
    }
}

/** An encoder's output in cacheDir, with the dimensions (and duration) the row needs from it. */
private class Encoded(val file: File, val width: Int, val height: Int, val durationSec: Int?)

/**
 * Sends an own message row the repository has already inserted, or flipped back
 * to SENDING for a retry.
 *
 * Each step writes its result back to the row before the next one starts, and
 * is skipped when the row already carries it:
 *
 * | Step | Skipped when | Persists |
 * |---|---|---|
 * | compress (IMAGE) / transcode (VIDEO) | `mediaWidth != null` | `localUri`, dimensions, `duration` |
 * | video thumbnail | `mediaThumbnailUrl != null` | `mediaThumbnailUrl` |
 * | upload (media, VOICE) | `mediaUrl != null` | `mediaUrl` |
 * | message write | — | status SENT |
 *
 * A retry therefore never re-encodes or re-uploads what an earlier attempt
 * finished. Runs in the caller's coroutine: cancellation leaves the row, still
 * SENDING, as far as it got.
 */
@Singleton
class OutboxSender @Inject constructor(
    private val messageDao: MessageDao,
    private val chatDao: ChatDao,
    private val messageSource: MessageSource,
    private val storageSource: StorageSource,
    private val signalManager: SignalManager,
    private val imageCompressor: ImageCompressor,
    private val videoTranscoder: VideoTranscoder,
    private val mediaFileManager: MediaFileManager,
    private val preferencesDataStore: PreferencesDataStore,
) {

    private val _uploadProgress = MutableStateFlow<Map<String, Float>>(emptyMap())
    val uploadProgress: StateFlow<Map<String, Float>> = _uploadProgress.asStateFlow()

    /**
     * Runs the pipeline for the row at [messageId] and returns it SENT. Throws on
     * any failure; marking the row FAILED is the caller's job.
     *
     * @param recipientId the 1:1 peer to encrypt for; empty for group and
     *   broadcast chats (see `MessageRepositoryImpl.sendMessage`). Not on the row.
     * @param isRetry an earlier attempt may already have landed: the write is
     *   create-if-absent (see [MessageSource]), and the chat preview is only
     *   rebound when it still points at this message rather than overwritten.
     * @param sourceMimeType the picked file's type. Only a DOCUMENT uploads under
     *   it — images and videos are re-encoded to JPEG / MP4. The row does not
     *   store it, so a retry passes null and uploads as `application/octet-stream`.
     */
    suspend fun send(
        messageId: String,
        recipientId: String,
        isRetry: Boolean = false,
        sourceMimeType: String? = null,
    ): Message {
        val stored = messageDao.getMessageById(messageId)?.toDomain()
            ?: throw IllegalStateException("Cannot send unknown message $messageId")
        val row = when (stored.type) {
            MessageType.TEXT, MessageType.LOCATION -> stored
            MessageType.IMAGE, MessageType.VIDEO, MessageType.DOCUMENT -> prepareMedia(stored, sourceMimeType)
            MessageType.VOICE -> uploadIfNeeded(stored, VOICE_MIME_TYPE)
            else -> throw IllegalStateException("Send not supported for message type ${stored.type}")
        }

        val remoteId = sendEncryptedOrPlain(
            chatId = row.chatId,
            senderId = row.senderId,
            // LOCATION has always been written in plaintext.
            recipientId = if (row.type == MessageType.LOCATION) "" else recipientId,
            messageId = row.id,
            plaintext = row.content,
            type = row.type,
            timestamp = row.timestamp,
            replyToId = row.replyToId,
            mentions = row.mentions,
            emojiSizes = row.emojiSizes,
            mediaUrl = row.mediaUrl,
            mediaThumbnailUrl = row.mediaThumbnailUrl,
            mediaWidth = row.mediaWidth,
            mediaHeight = row.mediaHeight,
            duration = row.duration,
            latitude = row.latitude,
            longitude = row.longitude,
            isForwarded = row.isForwarded,
            isHd = row.isHd,
            ifAbsent = isRetry,
        )

        val sent = row.copy(
            id = remoteId,
            status = MessageStatus.SENT,
            // A document's localUri is the picked URI, not a file the app owns;
            // the SENT row drops it, which lets the media backfill fetch a copy.
            localUri = if (row.type == MessageType.DOCUMENT) null else row.localUri,
        )
        messageDao.replaceMessage(row.id, MessageEntity.fromDomain(sent))

        val preview = messageSource.lastContentFor(row.type, row.content)
        if (isRetry) {
            rebindLastMessageIfMatches(row.chatId, row.id, remoteId, preview, row.timestamp)
        } else {
            chatDao.updateLastMessage(row.chatId, remoteId, preview, row.timestamp)
        }

        return if (remoteId != row.id && row.type in LOCAL_FILE_TYPES) {
            sent.copy(localUri = renameLocalFile(row, remoteId))
        } else {
            sent
        }
    }

    private suspend fun prepareMedia(stored: Message, sourceMimeType: String?): Message {
        var row = stored
        if (row.type in LOCAL_FILE_TYPES && row.mediaWidth == null) {
            row = persist(encodeToLocalFile(row))
        }
        if (row.type == MessageType.VIDEO && row.mediaThumbnailUrl == null) {
            val thumbFile = videoTranscoder.extractThumbnail(localUriOf(row))
            val thumbnailUrl = try {
                storageSource.uploadMedia(
                    row.chatId, thumbStorageId(row.id), Uri.fromFile(thumbFile), LocalFormat.JPEG.mimeType
                )
            } finally {
                thumbFile.delete()
            }
            row = persist(row.copy(mediaThumbnailUrl = thumbnailUrl))
        }
        val uploadMimeType = if (row.type in LOCAL_FILE_TYPES) {
            LocalFormat.of(row.type).mimeType
        } else {
            sourceMimeType ?: FALLBACK_MIME_TYPE
        }
        return uploadIfNeeded(row, uploadMimeType)
    }

    /** Compresses an IMAGE or transcodes a VIDEO, then copies the result into the app's media dir. */
    private suspend fun encodeToLocalFile(row: Message): Message {
        val source = localUriOf(row)
        val encoded = if (row.type == MessageType.IMAGE) {
            val result = imageCompressor.processImage(source, row.isHd)
            Encoded(result.file, result.width, result.height, durationSec = null)
        } else {
            // Read again rather than handed over from the repository's pre-insert
            // guard: a retry has only the row. Cheap next to the transcode itself.
            val metadata = videoTranscoder.ensureWithinLimits(source)
            val targetHeight = preferencesDataStore.videoQualityFlow.first().targetHeight
            val result = videoTranscoder.transcode(source, targetHeight, metadata)
            Encoded(result.file, result.width, result.height, result.durationSec)
        }
        // The encoder output lives in cacheDir; the upload reads the local copy.
        val localFile = try {
            mediaFileManager.copyToLocal(
                row.chatId, row.id, Uri.fromFile(encoded.file), LocalFormat.of(row.type).extension
            )
        } finally {
            encoded.file.delete()
        }
        return row.copy(
            localUri = localFile.absolutePath,
            mediaWidth = encoded.width,
            mediaHeight = encoded.height,
            duration = encoded.durationSec,
        )
    }

    /** Uploads the row's local file unless an earlier attempt already did, then persists `mediaUrl`. */
    private suspend fun uploadIfNeeded(row: Message, mimeType: String): Message {
        if (row.mediaUrl != null) return row
        // Voice sends have never reported upload progress.
        val onProgress: ((Float) -> Unit)? = if (row.type == MessageType.VOICE) null else { progress ->
            _uploadProgress.update { it + (row.id to progress) }
        }
        val mediaUrl = try {
            storageSource.uploadMedia(row.chatId, row.id, localUriOf(row), mimeType, onProgress)
        } finally {
            _uploadProgress.update { it - row.id }
        }
        return persist(row.copy(mediaUrl = mediaUrl))
    }

    /** Writes a finished step back to Room, still SENDING, so the next attempt resumes after it. */
    private suspend fun persist(row: Message): Message {
        val sending = row.copy(status = MessageStatus.SENDING)
        messageDao.replaceMessage(row.id, MessageEntity.fromDomain(sending))
        return sending
    }

    /** The row's `localUri` as a [Uri] — a bare path is a file the app wrote, anything else is already a URI. */
    private fun localUriOf(row: Message): Uri {
        val localUri = row.localUri
            ?: throw IllegalStateException("Cannot send ${row.type} message ${row.id}: local file is missing")
        val parsed = Uri.parse(localUri)
        return if (parsed.scheme == null) Uri.fromFile(File(localUri)) else parsed
    }

    /**
     * A backend that mints its own ids (PocketBase) swaps the row's id on SENT;
     * rename the local media file to match so it is not orphaned. Returns the
     * path the row ends up pointing at.
     */
    private suspend fun renameLocalFile(row: Message, remoteId: String): String? {
        val current = row.localUri?.let(::File) ?: return null
        val renamed = mediaFileManager.getLocalFile(row.chatId, remoteId, LocalFormat.of(row.type).extension)
        if (!current.renameTo(renamed)) return current.absolutePath
        messageDao.updateLocalUri(remoteId, renamed.absolutePath)
        return renamed.absolutePath
    }

    // After a retry swaps the row's id, the chat's lastMessageId may still point
    // at the old one. Only rebind it when the chat's lastMessage was this row —
    // otherwise a newer message exists and we'd downgrade the preview.
    private suspend fun rebindLastMessageIfMatches(
        chatId: String,
        oldMessageId: String,
        newMessageId: String,
        previewContent: String,
        timestamp: Long,
    ) {
        val chat = chatDao.getChatById(chatId) ?: return
        if (chat.lastMessageId == oldMessageId) {
            chatDao.updateLastMessage(chatId, newMessageId, previewContent, timestamp)
        }
    }

    /** Storage object id for a video's JPEG thumbnail, derived from the media message id. */
    private fun thumbStorageId(messageId: String) = "${messageId}_thumb"

    /**
     * Routes a write through Signal encryption or the plaintext branch based on
     * the build flavor and whether a 1:1 recipient is known — the one place the
     * "encrypt for recipient unless debug/empty-recipient/opted-out" decision is
     * made. `MessageRepositoryImpl.forwardMessage` and the broadcast fan-out call
     * it directly: they write without a retryable row.
     *
     * [messageId] becomes the remote id on a backend that keys by client id (see
     * [MessageSource]); [ifAbsent] = true marks a re-attempt, so a write that
     * landed after its await was cancelled is never duplicated. The returned id
     * is what the backend actually used — the same id on Firebase, a server id on
     * PocketBase — which is why callers still swap the row to it.
     */
    suspend fun sendEncryptedOrPlain(
        chatId: String,
        senderId: String,
        recipientId: String,
        messageId: String,
        plaintext: String,
        type: MessageType,
        timestamp: Long,
        replyToId: String? = null,
        mentions: List<String> = emptyList(),
        emojiSizes: Map<Int, Float> = emptyMap(),
        mediaUrl: String? = null,
        mediaThumbnailUrl: String? = null,
        mediaWidth: Int? = null,
        mediaHeight: Int? = null,
        duration: Int? = null,
        latitude: Double? = null,
        longitude: Double? = null,
        isForwarded: Boolean = false,
        isHd: Boolean = false,
        ifAbsent: Boolean = false,
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
                messageId = messageId,
                ciphertext = encrypted.ciphertext,
                signalType = encrypted.signalType,
                type = type,
                replyToId = replyToId,
                timestamp = timestamp,
                mediaUrl = mediaUrl,
                mediaThumbnailUrl = mediaThumbnailUrl,
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
                ifAbsent = ifAbsent,
            )
        } else {
            messageSource.sendPlainMessage(
                chatId = chatId,
                senderId = senderId,
                messageId = messageId,
                content = plaintext,
                type = type,
                replyToId = replyToId,
                timestamp = timestamp,
                mediaUrl = mediaUrl,
                mediaThumbnailUrl = mediaThumbnailUrl,
                isForwarded = isForwarded,
                duration = duration,
                mentions = mentions,
                emojiSizes = emojiSizes,
                mediaWidth = mediaWidth,
                mediaHeight = mediaHeight,
                latitude = latitude,
                longitude = longitude,
                isHd = isHd,
                ifAbsent = ifAbsent,
            )
        }
    }
}
