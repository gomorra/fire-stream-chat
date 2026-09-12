// region: AGENT-NOTE
// Responsibility: Delivers one own message that is already a Room row to the
//   backend — compress / transcode, video thumbnail, upload, encrypt once, the
//   write, the SENT transaction and the chat preview. Reads the row and skips
//   every step it already records, so a first attempt and a retry run the same
//   code. Covers TEXT, IMAGE, VIDEO, DOCUMENT, VOICE, LOCATION (SENDABLE_TYPES),
//   and the tombstone of a row deleted while it was queued.
// Owns: uploadProgress (MessageRepository re-exposes it); the outbox columns on
//   MessageEntity — the attempt count, the stored ciphertext — and the if-absent
//   decision the count drives; one in-process lock per message id, so a REPLACE
//   retry never overlaps the attempt it replaces, and the job is decided under it.
//   Cites "Sends are idempotent by client id and drained by OutboxWorker"
//   (docs/PATTERNS.md).
// Collaborators: OutboxWorker (only caller — one run per attempt, online by
//   constraint), MessageWriter, MessageDao, ChatDao, MessageSource, StorageSource,
//   OutboxFiles, ImageCompressor, VideoTranscoder, MediaFileManager,
//   PreferencesDataStore.
// Don't put here: validation, the optimistic insert, staging the input, the
//   block check and FAILED marking — they stay in MessageRepositoryImpl and
//   OutboxWorker, where a definite block and an unanswerable block check part
//   ways (.claude/plans/offline-outbox.md §2.5). The encrypt-or-plaintext
//   decision — MessageWriter. No semaphore of its own either —
//   "MediaProcessingLimiter owns the concurrency bound" (docs/PATTERNS.md).
// endregion

package com.firestream.chat.data.outbox

import android.net.Uri
import com.firestream.chat.data.crypto.EncryptedMessage
import com.firestream.chat.data.local.PreferencesDataStore
import com.firestream.chat.data.local.dao.ChatDao
import com.firestream.chat.data.local.dao.MessageDao
import com.firestream.chat.data.local.entity.MessageEntity
import com.firestream.chat.data.local.entity.MessageRecord
import com.firestream.chat.data.remote.source.MessageSource
import com.firestream.chat.data.remote.source.StorageSource
import com.firestream.chat.data.util.ImageCompressor
import com.firestream.chat.data.util.KeyedMutex
import com.firestream.chat.data.util.MediaFileManager
import com.firestream.chat.data.util.VideoTranscoder
import com.firestream.chat.domain.model.Message
import com.firestream.chat.domain.model.MessageStatus
import com.firestream.chat.domain.model.MessageType
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.withContext
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
 * Sends an own message row the repository has inserted, or flipped back to
 * SENDING for a retry, and has staged the input of.
 *
 * Each step writes its result back to the row before the next one starts, and
 * is skipped when the row already carries it:
 *
 * | Step | Skipped when | Persists |
 * |---|---|---|
 * | — | — | `outboxAttempts + 1`, before anything else |
 * | compress (IMAGE) / transcode (VIDEO) | `mediaWidth != null` | `localUri`, dimensions, `duration` |
 * | video thumbnail | `mediaThumbnailUrl != null` | `mediaThumbnailUrl` |
 * | upload (media, VOICE) | `mediaUrl != null` | `mediaUrl` |
 * | encrypt (1:1, release) | `outboxCiphertext != null` and the peer's identity is unchanged | `outboxCiphertext`, `outboxSignalType`, `outboxPeerIdentity` |
 * | message write | — | status SENT, outbox columns cleared, staged input deleted |
 *
 * A retry therefore never re-encodes, re-uploads or re-encrypts what an earlier
 * attempt finished. Steps persist with column updates; the outbox columns are
 * cleared by the SENT transaction alone. Runs in the caller's coroutine:
 * cancellation leaves the row, still SENDING, as far as it got.
 */
@Singleton
class OutboxSender @Inject constructor(
    private val messageDao: MessageDao,
    private val chatDao: ChatDao,
    private val messageSource: MessageSource,
    private val storageSource: StorageSource,
    private val messageWriter: MessageWriter,
    private val outboxFiles: OutboxFiles,
    private val imageCompressor: ImageCompressor,
    private val videoTranscoder: VideoTranscoder,
    private val mediaFileManager: MediaFileManager,
    private val preferencesDataStore: PreferencesDataStore,
) {

    private val _uploadProgress = MutableStateFlow<Map<String, Float>>(emptyMap())
    val uploadProgress: StateFlow<Map<String, Float>> = _uploadProgress.asStateFlow()

    // A manual retry REPLACEs the message's work while an attempt may still be
    // running; WorkManager cancels that attempt, but not before the new one can
    // start. Per id, so parallel sends of different messages never wait on
    // each other.
    private val locks = KeyedMutex<String>()

    /**
     * Runs the pipeline for the row at [messageId] and returns it SENT — or, for
     * a row deleted while it was queued, writes its tombstone (see [tombstone]).
     * Throws on any failure; marking the row FAILED is the caller's job.
     *
     * Everything comes from the row, read under the lock: a row the backend
     * acknowledged meanwhile (an echo healed it while the caller was still
     * deciding) owes nothing and is returned as it is. Its recorded [SendTarget]
     * is who to encrypt for; a row without one is refused, since guessing would
     * send in plaintext. `outboxAttempts` above zero means an earlier run may
     * already have landed its write, so the write is create-if-absent (see
     * [MessageSource]). The chat preview is updated the same way on every attempt:
     * `ChatDao.updateLastMessage` is newer-only, so an attempt finishing late never
     * takes it from a later message.
     */
    suspend fun send(messageId: String): Message = locks.withLock(messageId) {
        val entity = messageDao.getMessageById(messageId)
            ?: throw IllegalStateException("Cannot send unknown message $messageId")
        when (entity.outboxJob) {
            null -> return@withLock entity.toDomain()
            OutboxJob.TOMBSTONE -> return@withLock tombstone(entity)
            OutboxJob.SEND -> Unit
        }
        val stored = entity.toDomain()
        if (stored.type !in SENDABLE_TYPES) {
            throw IllegalStateException("Send not supported for message type ${stored.type}")
        }
        val target = entity.sendTarget
            ?: throw IllegalStateException("Cannot send message $messageId: no recipient recorded")

        // Counted before any step can fail, so whatever this run gets through,
        // the next one knows it is a re-attempt.
        val isReattempt = entity.outboxAttempts > 0
        messageDao.incrementOutboxAttempts(messageId)

        val row = when (stored.type) {
            MessageType.IMAGE, MessageType.VIDEO, MessageType.DOCUMENT -> prepareMedia(stored)
            MessageType.VOICE -> uploadIfNeeded(stored, VOICE_MIME_TYPE)
            else -> stored
        }

        val encrypted = reusableCiphertext(entity, target) ?: encodeOnce(row, target)

        // The commit phase runs to its end once started. A manual retry REPLACEs
        // this work and WorkManager cancels it, but a Firestore transaction the
        // SDK has begun keeps running with nobody awaiting it — outside the
        // tombstone's flush, so a message deleted meanwhile could land after its
        // tombstone had found nothing. Bounded by the ack timeout in the source.
        withContext(NonCancellable) {
            val remoteId = messageWriter.write(row, encrypted, ifAbsent = isReattempt)

            val sent = row.copy(
                id = remoteId,
                status = MessageStatus.SENT,
                // Kept only when it is a file the app keeps — the media dir copy an
                // image or video was encoded into. A staged input (a document, a voice
                // note) is deleted below, and the media backfill fetches a copy of a
                // document; a picked URI would not outlive the process anyway.
                localUri = row.localUri?.takeIf { outboxFiles.isDurable(it) },
            )
            // One transaction: the row as written, and out of the outbox — the stored
            // ciphertext and the attempt count end with the send. Declined for a row
            // deleted while this ran: the delete re-queued it, and the tombstone
            // finds the document this write just created.
            if (!messageDao.markSent(row.id, MessageRecord.fromDomain(sent), sent.localUri)) {
                return@withContext sent
            }
            outboxFiles.delete(row.id)

            // Newer-only, so a first attempt and a re-attempt update it alike: a send
            // finishing after a later one leaves that one's preview, and a backend-
            // swapped id (same timestamp) takes over the preview of its own row.
            val preview = messageSource.lastContentFor(row.type, row.content)
            chatDao.updateLastMessage(row.chatId, remoteId, preview, row.timestamp)

            if (remoteId != row.id && row.type in LOCAL_FILE_TYPES) {
                sent.copy(localUri = renameLocalFile(sent, remoteId))
            } else {
                sent
            }
        }
    }

    /**
     * A row deleted while it was queued. Cancelling its work was not enough: a
     * first attempt's write the SDK has persisted still replays. So the row stayed,
     * soft-deleted, and this run asks the backend to tombstone the document if —
     * and only if — it exists: pending writes flushed first, then a transaction
     * that never creates it. A row no run ever started (attempt count 0) has
     * nothing pending and nothing landed, so nothing is asked. Either way the row
     * leaves the outbox as SENT: deleted here, and there if it ever arrived.
     */
    private suspend fun tombstone(entity: MessageEntity): Message {
        val deletedAt = entity.deletedAt ?: error("not a deleted row")
        if (entity.outboxAttempts > 0) messageSource.deleteIfExists(entity.chatId, entity.id, deletedAt)
        messageDao.acknowledge(entity.id, MessageStatus.SENT.name)
        outboxFiles.delete(entity.id)
        return entity.toDomain().copy(status = MessageStatus.SENT)
    }

    /**
     * Encrypts the body when [MessageWriter] says so and keeps the ciphertext on
     * the row before anything is written. A later attempt reuses those bytes
     * rather than spend another ratchet step on the same message. `null`: the
     * message travels in plaintext.
     */
    private suspend fun encodeOnce(row: Message, target: SendTarget): EncryptedMessage? =
        messageWriter.encode(row, target)?.also {
            messageDao.storeOutboxCiphertext(row.id, it.ciphertext, it.signalType, it.peerIdentity)
        }

    /**
     * The ciphertext an earlier attempt kept on the row, if the peer can still
     * decrypt it. A peer who re-registered in between has a new identity and no
     * session for these bytes; writing them would land a message they can never
     * read while this side shows it SENT. Then `null`, and [encodeOnce] runs again
     * for the new identity.
     */
    private suspend fun reusableCiphertext(entity: MessageEntity, target: SendTarget): EncryptedMessage? {
        val peer = target.peerId ?: return null
        val stored = entity.storedCiphertext() ?: return null
        return stored.takeIf { messageWriter.isReusable(peer, it) }
    }

    private suspend fun prepareMedia(stored: Message): Message {
        // Already uploaded — a resumed row past its upload, or a forward, whose
        // media is the source message's. Nothing here applies: re-encoding would
        // want a local file the row may not have.
        if (stored.mediaUrl != null) return stored
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
            // A document uploads under the type its staged copy's extension carries.
            row.localUri?.let(outboxFiles::mimeTypeOf) ?: FALLBACK_MIME_TYPE
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

    /** Writes a finished step back to Room so the next attempt resumes after it. */
    private suspend fun persist(row: Message): Message {
        messageDao.updateSendProgress(
            messageId = row.id,
            localUri = row.localUri,
            mediaWidth = row.mediaWidth,
            mediaHeight = row.mediaHeight,
            duration = row.duration,
            mediaThumbnailUrl = row.mediaThumbnailUrl,
            mediaUrl = row.mediaUrl,
        )
        return row
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

    /** Storage object id for a video's JPEG thumbnail, derived from the media message id. */
    private fun thumbStorageId(messageId: String) = "${messageId}_thumb"

    companion object {
        /** Types [send] delivers; any other row is refused before any IO, and never queued (`OutboxScheduler.requeueAll`). */
        val SENDABLE_TYPES = setOf(
            MessageType.TEXT, MessageType.IMAGE, MessageType.VIDEO,
            MessageType.DOCUMENT, MessageType.VOICE, MessageType.LOCATION,
        )
    }
}

/** The ciphertext an earlier attempt encrypted and kept, if any. */
private fun MessageEntity.storedCiphertext(): EncryptedMessage? {
    val ciphertext = outboxCiphertext ?: return null
    val signalType = outboxSignalType ?: return null
    return EncryptedMessage(ciphertext, signalType, outboxPeerIdentity)
}
