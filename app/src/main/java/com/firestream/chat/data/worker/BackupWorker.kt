// region: AGENT-NOTE
// Responsibility: WorkManager worker executing both manual ("Back up now") and
//   scheduled (Daily/Weekly) encrypted cloud backups. Promotes itself to a
//   `dataSync` foreground service with a live progress notification (degrading
//   gracefully if Android 12+ refuses background FGS start on a scheduled run)
//   so screen-off / app-switching never interrupts an active backup.
// Collaborators: BackupRepository (BackupManager), BackupScheduler,
//   WorkerForeground.
// endregion

package com.firestream.chat.data.worker

import android.app.Notification
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import androidx.core.app.NotificationCompat
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.firestream.chat.data.backup.UserCancelledBackupException
import com.firestream.chat.domain.model.BackupProgress
import com.firestream.chat.domain.model.BackupStage
import com.firestream.chat.domain.repository.BackupRepository
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

@HiltWorker
class BackupWorker @AssistedInject constructor(
    @Assisted private val context: Context,
    @Assisted workerParams: WorkerParameters,
    private val backupRepository: BackupRepository,
) : CoroutineWorker(context, workerParams) {

    private var channelCreated = false

    override suspend fun doWork(): Result {
        if (!backupRepository.isSupported) return Result.success()
        if (!backupRepository.hasConfiguredPassphrase()) return Result.success()

        val isManual = inputData.getString(KEY_TRIGGER) == TRIGGER_MANUAL
        val initialProgress = BackupProgress(stage = BackupStage.SYNCING_CHATS, fraction = 0f)
        val fgsActive = tryPromoteForeground(
            TAG,
            dataSyncForegroundInfo(NOTIF_ID, buildNotification(initialProgress, isManual)),
        )

        return try {
            coroutineScope {
                val progressJob = if (fgsActive) {
                    launch {
                        val nm = context.getSystemService(NotificationManager::class.java)
                        var lastKey: String? = null
                        backupRepository.backupProgress.collect { progress ->
                            if (progress.stage in TERMINAL_OR_IDLE_STAGES) return@collect
                            val percent = (progress.fraction.coerceIn(0f, 1f) * 100).toInt()
                            val key = "${progress.stage}:${progress.completedItems}/${progress.totalItems}:$percent"
                            if (key == lastKey) return@collect
                            lastKey = key
                            nm?.notify(NOTIF_ID, buildNotification(progress, isManual))
                        }
                    }
                } else {
                    null
                }
                try {
                    backupRepository.runBackup().fold(
                        onSuccess = { Result.success() },
                        onFailure = {
                            if (!isManual && runAttemptCount < MAX_RETRY_ATTEMPTS) {
                                Result.retry()
                            } else {
                                Result.failure()
                            }
                        },
                    )
                } finally {
                    progressJob?.cancel()
                }
            }
        } catch (e: UserCancelledBackupException) {
            if (!currentCoroutineContext().isActive) throw e
            Result.success()
        } catch (e: CancellationException) {
            throw e
        }
    }

    private fun buildNotification(progress: BackupProgress, isManual: Boolean): Notification {
        ensureChannel()
        val percent = (progress.fraction.coerceIn(0f, 1f) * 100).toInt()
        val indeterminate = percent <= 0
        val builder = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_sys_upload)
            .setContentTitle("Backing up chats")
            .setContentText(notificationTextFor(progress))
            .setProgress(if (indeterminate) 0 else 100, percent, indeterminate)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)

        val launchIntent = context.packageManager?.getLaunchIntentForPackage(context.packageName)
        if (launchIntent != null) {
            val contentIntent = PendingIntent.getActivity(
                context,
                REQUEST_OPEN_APP,
                launchIntent,
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            )
            builder.setContentIntent(contentIntent)
        }

        // Only manual one-time work attaches WorkManager's cancel PendingIntent:
        // cancelling a periodic work UUID would unschedule Daily/Weekly backups.
        if (isManual) {
            val cancelIntent = runCatching {
                WorkManager.getInstance(context).createCancelPendingIntent(id)
            }.getOrNull()
            if (cancelIntent != null) {
                builder.addAction(0, "Cancel", cancelIntent)
            }
        }
        return builder.build()
    }

    private fun ensureChannel() {
        if (channelCreated) return
        context.ensureLowImportanceChannel(CHANNEL_ID, "Chat Backup")
        channelCreated = true
    }

    companion object {
        private const val TAG = "BackupWorker"
        const val UNIQUE_NAME = "encrypted_chat_backup"
        const val UNIQUE_MANUAL_NAME = "encrypted_chat_backup_manual"
        const val KEY_TRIGGER = "trigger"
        const val TRIGGER_MANUAL = "manual"
        const val TRIGGER_SCHEDULED = "scheduled"
        private const val CHANNEL_ID = "fire_stream_backup"
        private const val NOTIF_ID = 0xFC_5A_E1
        private const val REQUEST_OPEN_APP = 0xFC_5A_E2
        private const val MAX_RETRY_ATTEMPTS = 3

        private val TERMINAL_OR_IDLE_STAGES = setOf(
            BackupStage.IDLE,
            BackupStage.COMPLETED,
            BackupStage.FAILED,
        )

        internal fun notificationTextFor(progress: BackupProgress): String = when (progress.stage) {
            BackupStage.SYNCING_CHATS -> "Syncing chats…"
            BackupStage.DOWNLOADING_LEGACY_MEDIA ->
                if (progress.totalItems > 0) "Downloading legacy media (${progress.completedItems}/${progress.totalItems})…"
                else "Checking legacy media…"
            BackupStage.UPLOADING_LEGACY_MEDIA ->
                if (progress.totalItems > 0) "Uploading legacy media (${progress.completedItems}/${progress.totalItems})…"
                else "Checking legacy media…"
            BackupStage.SNAPSHOTTING_DB -> "Preparing database snapshot…"
            BackupStage.ENCRYPTING_DB -> "Encrypting database…"
            BackupStage.UPLOADING_DB -> "Uploading encrypted backup…"
            BackupStage.FINALIZING -> "Finalizing backup…"
            BackupStage.COMPLETED -> "Backup complete"
            BackupStage.FAILED -> progress.errorMessage ?: "Backup failed"
            BackupStage.IDLE -> "Preparing backup…"
        }
    }
}
