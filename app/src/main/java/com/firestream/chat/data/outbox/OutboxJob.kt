// region: AGENT-NOTE
// Responsibility: The one rule for what the outbox still owes the backend for a
//   row — a send, a tombstone, or nothing — and whether its next attempt uploads.
// Owns: the Kotlin half of the queue predicate (MessageDao.getQueuedMessages is
//   the SQL half; keep them in step) and the upload-types list.
// Collaborators: OutboxWorker (gates each run on it), OutboxSender (decides the
//   job under its lock), OutboxScheduler (the expedited rule), MessageRepositoryImpl
//   (the delete path).
// Don't put here: the transitions themselves — they are MessageDao's (markSent,
//   acknowledge, failQueued). Cites "Sends are idempotent by client id and drained
//   by OutboxWorker" (docs/PATTERNS.md).
// endregion

package com.firestream.chat.data.outbox

import com.firestream.chat.data.local.entity.MessageEntity
import com.firestream.chat.domain.model.MessageStatus
import com.firestream.chat.domain.model.MessageType

/** What the outbox still owes the backend for a row. */
enum class OutboxJob {
    /** Deliver the message: the pipeline's remaining steps, then the write. */
    SEND,

    /** The message was deleted while queued or given up on: tombstone the document, if it exists. */
    TOMBSTONE,
}

private val UNACKNOWLEDGED = setOf(MessageStatus.SENDING.name, MessageStatus.FAILED.name)

/** Types whose first send uploads a file. */
private val UPLOAD_TYPES = setOf(MessageType.IMAGE, MessageType.VIDEO, MessageType.DOCUMENT, MessageType.VOICE).map { it.name }

/** Whether the backend has not acknowledged this own row: still queued, in flight, or given up on. */
val MessageEntity.isUnacknowledged: Boolean
    get() = status in UNACKNOWLEDGED

/**
 * The job a row is queued for, or `null` when the backend owes nothing more.
 * The Kotlin half of `MessageDao.getQueuedMessages`'s predicate: an own row is
 * queued while it is SENDING, and a deleted one while it is SENDING or FAILED.
 * A deleted row the backend had acknowledged was tombstoned the direct way.
 */
val MessageEntity.outboxJob: OutboxJob?
    get() = when {
        deletedAt != null -> OutboxJob.TOMBSTONE.takeIf { isUnacknowledged }
        status == MessageStatus.SENDING.name -> OutboxJob.SEND
        else -> null
    }

/** Whether the row's next attempt uploads a file — the case that wants the foreground on every API level. */
val MessageEntity.needsUpload: Boolean
    get() = outboxJob == OutboxJob.SEND && mediaUrl == null && type in UPLOAD_TYPES
