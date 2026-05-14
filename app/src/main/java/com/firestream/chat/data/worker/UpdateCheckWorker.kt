package com.firestream.chat.data.worker

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.firestream.chat.MainActivity
import com.firestream.chat.domain.model.UpdateCheckResult
import com.firestream.chat.domain.repository.AppUpdateRepository
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject

/**
 * Periodic background check (24h cadence, scheduled in [com.firestream.chat.FireStreamApp]).
 * On finding a newer release, posts a low-priority notification deep-linking
 * to MainActivity; the user opens Settings → Check for updates to install.
 *
 * Per the project's notification-only update UX: no start-up dialogs, no nag.
 * If the user dismisses the notification it's gone — a duplicate notification
 * may post on the next 24h tick, which is acceptable.
 */
@HiltWorker
class UpdateCheckWorker @AssistedInject constructor(
    @Assisted private val context: Context,
    @Assisted workerParams: WorkerParameters,
    private val appUpdateRepository: AppUpdateRepository
) : CoroutineWorker(context, workerParams) {

    override suspend fun doWork(): Result {
        val outcome = appUpdateRepository.checkForUpdate().getOrElse {
            return Result.retry()
        }
        if (outcome is UpdateCheckResult.Available) {
            postNotification(outcome.update.versionName)
        }
        return Result.success()
    }

    private fun postNotification(versionName: String) {
        val nm = context.getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(
                CHANNEL_ID,
                "App updates",
                NotificationManager.IMPORTANCE_LOW
            )
        )
        val intent = Intent(context, MainActivity::class.java).apply {
            putExtra("openSettings", true)
            putExtra("focusUpdate", true)
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
        }
        val pendingIntent = PendingIntent.getActivity(
            context, NOTIF_ID, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_sys_download_done)
            .setContentTitle("FireStream Chat $versionName available")
            .setContentText("Tap to download and install.")
            .setStyle(
                NotificationCompat.BigTextStyle()
                    .bigText("A new version is ready to install. Tap to download.")
            )
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setContentIntent(pendingIntent)
            .setAutoCancel(true)
            .build()
        nm.notify(NOTIF_ID, notification)
    }

    companion object {
        const val UNIQUE_NAME = "app_update_check"
        private const val CHANNEL_ID = "fire_stream_app_updates"
        private const val NOTIF_ID = 0xFC_5A_DE
    }
}
