// region: AGENT-NOTE
// Responsibility: The WorkManager side of a queued send — one unique work per
//   message id, the constraint, the backoff, the expedited rule, and the
//   requeue of every queued own row on app start.
// Owns: the unique-work naming (outbox-<messageId>), the KEEP-on-compose /
//   REPLACE-on-retry policies, the expedited decision (runsExpedited), and the
//   sweep of staged inputs no queued row owns.
// Collaborators: MessageRepositoryImpl (enqueue after every insert, retryNow on a
//   manual retry and a queued delete), FireStreamApp (requeueAll on start),
//   OutboxWorker (what the work runs), MessageDao, AuthSource, OutboxFiles.
// Don't put here: anything the attempt itself does (OutboxWorker, OutboxSender),
//   the give-up count (OutboxWorker reads it off the row), what a row is queued
//   for (OutboxJob). Cites "Sends are idempotent by client id and drained by
//   OutboxWorker" (docs/PATTERNS.md).
// endregion

package com.firestream.chat.data.outbox

import android.os.Build
import android.util.Log
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.OutOfQuotaPolicy
import androidx.work.WorkManager
import androidx.work.workDataOf
import com.firestream.chat.data.local.dao.MessageDao
import com.firestream.chat.data.remote.source.AuthSource
import com.firestream.chat.data.worker.OutboxWorker
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Provider
import javax.inject.Singleton

private const val TAG = "OutboxScheduler"

/**
 * Enqueues the [OutboxWorker] run that delivers a row, and re-enqueues every
 * queued row on app start.
 *
 * Each message is one unique work, `outbox-<messageId>`, constrained to a
 * connected network and backed off exponentially from 10 s, so sends run in
 * parallel per message and never wait on each other. [enqueue] keeps existing
 * work (a second enqueue of a row already queued is a no-op); [retryNow]
 * replaces it, so a manual retry runs at once even while a backed-off attempt
 * is waiting — `OutboxSender`'s per-id lock keeps the two from overlapping.
 *
 * Expedited only where it does not cost a notification: on API 31+ an expedited
 * job runs without one, so every send is expedited there; below, expedited work
 * is a foreground service, so only a send that uploads media — which wants the
 * foreground anyway — asks for it, and a text send runs as ordinary work.
 */
@Singleton
class OutboxScheduler @Inject constructor(
    private val workManager: Provider<WorkManager>,
    private val messageDao: MessageDao,
    private val authSource: AuthSource,
    private val outboxFiles: OutboxFiles,
) {

    /** Queues the send of [messageId]; a row already queued keeps its work. [uploads]: see `MessageEntity.needsUpload`. */
    fun enqueue(messageId: String, uploads: Boolean) = enqueue(messageId, uploads, ExistingWorkPolicy.KEEP)

    /** Runs [messageId]'s job now, replacing any queued or backed-off attempt — a manual retry, or a queued delete's tombstone. */
    fun retryNow(messageId: String, uploads: Boolean) = enqueue(messageId, uploads, ExistingWorkPolicy.REPLACE)

    /**
     * On app start: every own row still queued (`OutboxJob`) of a type the outbox
     * sends is queued again (KEEP — WorkManager usually still holds the work
     * across a process death or reboot, and this is the belt to its braces). A
     * SENDING row of any other type — a timer whose await died with the process —
     * has nothing to drain it and is failed so its retry affordance returns.
     * Staged inputs no queued row owns are swept.
     */
    suspend fun requeueAll() {
        val uid = authSource.currentUserId ?: return
        val sendable = OutboxSender.SENDABLE_TYPES.map { it.name }
        val queued = messageDao.getQueuedMessages(uid, sendable)
        queued.forEach { enqueue(it.id, uploads = it.needsUpload) }
        val stranded = messageDao.failQueuedOfOtherTypes(uid, sendable)
        outboxFiles.retainOnly(queued.mapTo(HashSet()) { it.id })
        Log.i(TAG, "requeueAll: ${queued.size} queued, $stranded stranded")
    }

    private fun enqueue(messageId: String, uploads: Boolean, policy: ExistingWorkPolicy) {
        val request = OneTimeWorkRequestBuilder<OutboxWorker>()
            .setInputData(workDataOf(OutboxWorker.KEY_MESSAGE_ID to messageId))
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, INITIAL_BACKOFF_SECONDS, TimeUnit.SECONDS)
            .addTag(TAG_OUTBOX)
            .apply {
                if (runsExpedited(Build.VERSION.SDK_INT, uploads)) {
                    setExpedited(OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST)
                }
            }
            .build()
        workManager.get().enqueueUniqueWork(uniqueName(messageId), policy, request)
        Log.d(TAG, "enqueued msg=$messageId policy=$policy")
    }

    companion object {
        const val TAG_OUTBOX = "outbox"
        const val INITIAL_BACKOFF_SECONDS = 10L

        fun uniqueName(messageId: String) = "outbox-$messageId"

        /** The expedited rule, as a function of the API level and whether the attempt uploads a file. */
        fun runsExpedited(sdkInt: Int, uploads: Boolean): Boolean = sdkInt >= Build.VERSION_CODES.S || uploads
    }
}
