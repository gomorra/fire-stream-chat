package com.firestream.chat.data.worker

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.pm.ServiceInfo
import android.os.Build
import android.util.Log
import androidx.work.CoroutineWorker
import androidx.work.ForegroundInfo
import kotlinx.coroutines.CancellationException

/**
 * Promotes the worker to a foreground service, or carries on without one.
 *
 * `setForeground` can be rejected — Android 12+ refuses a foreground start from
 * the background (`ForegroundServiceStartNotAllowedException`), Android 14 adds
 * type checks — and the work is still worth finishing: the download or upload
 * simply runs inside WorkManager's ten-minute budget without a notification.
 * Returns whether the promotion took.
 */
internal suspend fun CoroutineWorker.tryPromoteForeground(tag: String, info: ForegroundInfo): Boolean = try {
    setForeground(info)
    true
} catch (e: CancellationException) {
    throw e
} catch (t: Throwable) {
    Log.w(tag, "setForeground rejected — continuing as background work", t)
    false
}

/**
 * The [ForegroundInfo] for a data-sync notification. Android 14+ requires the
 * type, and the manifest merges `dataSync` into WorkManager's
 * `SystemForegroundService` for it; below, an untyped one is the only kind.
 */
internal fun dataSyncForegroundInfo(notificationId: Int, notification: Notification): ForegroundInfo =
    if (Build.VERSION.SDK_INT >= 34) {
        ForegroundInfo(notificationId, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
    } else {
        ForegroundInfo(notificationId, notification)
    }

/** Creates a low-importance channel; creating one that exists again is a no-op, and its importance is frozen at creation. */
internal fun Context.ensureLowImportanceChannel(channelId: String, name: String) {
    getSystemService(NotificationManager::class.java).createNotificationChannel(
        NotificationChannel(channelId, name, NotificationManager.IMPORTANCE_LOW)
    )
}
