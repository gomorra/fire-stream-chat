package com.firestream.chat.data.worker

import android.content.Context
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.ForegroundInfo
import androidx.work.WorkerParameters
import com.firestream.chat.data.local.dao.MessageDao
import com.firestream.chat.data.local.entity.MessageEntity
import com.firestream.chat.data.outbox.BlockCheck
import com.firestream.chat.data.outbox.OutboxJob
import com.firestream.chat.data.outbox.OutboxSender
import com.firestream.chat.data.outbox.needsUpload
import com.firestream.chat.data.outbox.outboxJob
import com.firestream.chat.data.remote.source.SendErrorClassifier
import com.firestream.chat.data.remote.source.SendFailure
import com.firestream.chat.domain.model.RecipientBlockedException
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import kotlinx.coroutines.CancellationException

/**
 * One attempt at one queued message — the work `OutboxScheduler` enqueues per
 * row, run by WorkManager whenever the network constraint holds, across the chat
 * being left, the process being killed and a reboot.
 *
 * The row is the state. A run reads it and asks [OutboxJob] what is owed: nothing
 * (an acknowledged echo healed it meanwhile, or it was failed), a [OutboxJob.SEND]
 * or a [OutboxJob.TOMBSTONE]. A send first asks the block list once more for a 1:1
 * peer — the repository's check may have been unanswerable offline — and promotes
 * itself to the foreground for an upload; then [OutboxSender] resumes past every
 * step an earlier attempt finished. A failure is sorted by [SendErrorClassifier]:
 * transient → `Result.retry()` with WorkManager's backoff, permanent → FAILED and
 * the retry button. Eight executed attempts — `outboxAttempts`, which
 * `OutboxSender` counts before anything else — give up the same way; there is no
 * wall clock, since attempts only run while connected, and a tombstone has no
 * budget. Cancellation (the constraint lost, the work replaced) leaves the row as
 * it was.
 */
@HiltWorker
class OutboxWorker @AssistedInject constructor(
    @Assisted private val context: Context,
    @Assisted params: WorkerParameters,
    private val messageDao: MessageDao,
    private val outboxSender: OutboxSender,
    private val blockCheck: BlockCheck,
    private val errorClassifier: SendErrorClassifier,
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val messageId = inputData.getString(KEY_MESSAGE_ID) ?: return Result.failure()
        val row = messageDao.getMessageById(messageId) ?: return Result.success()
        val job = row.outboxJob ?: return Result.success()
        if (job == OutboxJob.SEND && row.outboxAttempts >= MAX_ATTEMPTS) {
            return failRow(messageId, "gave up after ${row.outboxAttempts} attempts")
        }

        return try {
            if (job == OutboxJob.SEND) {
                refuseIfBlocked(row)
                if (row.needsUpload) tryPromoteForeground(TAG, foregroundInfo())
            }
            outboxSender.send(messageId)
            Result.success()
        } catch (e: CancellationException) {
            throw e
        } catch (t: Throwable) {
            when (errorClassifier.classify(t)) {
                SendFailure.PERMANENT -> failRow(messageId, "failed for good", t)
                SendFailure.TRANSIENT -> {
                    // OutboxSender counted this run before anything else could fail.
                    val attempts = messageDao.getMessageById(messageId)?.outboxAttempts ?: 0
                    if (job == OutboxJob.SEND && attempts >= MAX_ATTEMPTS) {
                        failRow(messageId, "gave up after $attempts attempts", t)
                    } else {
                        Log.w(TAG, "attempt $attempts for msg=$messageId failed — will retry", t)
                        Result.retry()
                    }
                }
            }
        }
    }

    /**
     * The authoritative block check. The repository asked before enqueueing and
     * let the row through when the answer could not be fetched; this run is
     * online by constraint, so an answer is available, and a throw here is the
     * backend's to classify. A cache hit when the repository's answer is recent.
     */
    private suspend fun refuseIfBlocked(row: MessageEntity) {
        val peer = row.sendTarget?.peerId ?: return
        if (blockCheck.isBlocked(row.senderId, peer)) throw RecipientBlockedException()
    }

    /** FAILED — for a row that is still queued; an echo may have healed it to SENT while this run failed. */
    private suspend fun failRow(messageId: String, reason: String, cause: Throwable? = null): Result {
        Log.w(TAG, "msg=$messageId $reason — marking FAILED", cause)
        messageDao.failQueued(messageId)
        return Result.failure()
    }

    /** Mandatory for expedited work: below API 31 WorkManager runs it as a foreground service with this notification. */
    override suspend fun getForegroundInfo(): ForegroundInfo = foregroundInfo()

    private fun foregroundInfo(): ForegroundInfo {
        context.ensureLowImportanceChannel(CHANNEL_ID, "Sending messages")
        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_sys_upload)
            .setContentTitle("Sending")
            .setContentText("Uploading media")
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setOnlyAlertOnce(true)
            .build()
        return dataSyncForegroundInfo(NOTIF_ID, notification)
    }

    companion object {
        private const val TAG = "OutboxWorker"
        const val KEY_MESSAGE_ID = "messageId"

        /** Executed attempts after which a row is failed instead of retried; a manual retry starts a fresh budget. */
        const val MAX_ATTEMPTS = 8

        private const val CHANNEL_ID = "fire_stream_outbox"
        private const val NOTIF_ID = 0xFC_5A_E1
    }
}
