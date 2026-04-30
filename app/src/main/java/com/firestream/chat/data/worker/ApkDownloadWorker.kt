package com.firestream.chat.data.worker

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.pm.ServiceInfo
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.ForegroundInfo
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import com.firestream.chat.data.util.ApkDownloader
import com.firestream.chat.domain.model.AppUpdate
import com.firestream.chat.domain.repository.DownloadProgress
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject

/**
 * Foreground download of the release APK. Lives in a service so the OS does
 * not throttle network I/O while the screen is locked — the original
 * inline-in-`viewModelScope` flow stalled mid-stream during Doze.
 *
 * Notification channel `fire_stream_app_updates` was created at
 * `IMPORTANCE_LOW` by [UpdateCheckWorker]; importance is sticky, so this
 * worker reuses the same channel. `setOnlyAlertOnce(true)` prevents OEMs
 * from re-buzzing on every progress tick.
 */
@HiltWorker
class ApkDownloadWorker @AssistedInject constructor(
    @Assisted private val context: Context,
    @Assisted params: WorkerParameters,
    private val apkDownloader: ApkDownloader
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val update = AppUpdate(
            versionCode = inputData.getInt(KEY_VERSION_CODE, 0),
            versionName = inputData.getString(KEY_VERSION_NAME).orEmpty(),
            apkUrl = inputData.getString(KEY_APK_URL).orEmpty(),
            sha256 = inputData.getString(KEY_SHA256).orEmpty(),
            minSupportedVersionCode = 0,
            releaseNotes = "",
            publishedAt = "",
            mandatory = false
        )
        if (update.apkUrl.isBlank() || update.sha256.isBlank()) return Result.failure()

        // Must complete inside ~10 s of enqueue to avoid ForegroundServiceDidNotStartInTime.
        setForeground(buildForegroundInfo(0L, -1L))

        var terminal: DownloadProgress = DownloadProgress.Failed("No progress emitted")
        apkDownloader.download(update).collect { progress ->
            terminal = progress
            if (progress is DownloadProgress.InProgress) {
                setProgress(workDataOf(
                    KEY_BYTES to progress.bytesDownloaded,
                    KEY_TOTAL to progress.totalBytes
                ))
                setForeground(buildForegroundInfo(progress.bytesDownloaded, progress.totalBytes))
            }
        }
        return when (val t = terminal) {
            is DownloadProgress.Done -> Result.success(workDataOf(
                KEY_VERSION_CODE to update.versionCode,
                KEY_VERSION_NAME to update.versionName
            ))
            is DownloadProgress.Failed -> Result.failure(
                workDataOf(KEY_FAILURE_MESSAGE to t.message)
            )
            is DownloadProgress.InProgress -> Result.failure()
        }
    }

    private fun buildForegroundInfo(bytes: Long, total: Long): ForegroundInfo {
        ensureChannel()
        val indeterminate = total <= 0L
        val percent = if (!indeterminate) ((bytes * 100) / total).toInt() else 0
        val notif = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setContentTitle("Downloading update")
            .setContentText(progressText(bytes, total))
            .setProgress(if (indeterminate) 0 else 100, percent, indeterminate)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setOnlyAlertOnce(true)
            .build()
        return if (Build.VERSION.SDK_INT >= 34) {
            ForegroundInfo(NOTIF_ID, notif, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } else {
            ForegroundInfo(NOTIF_ID, notif)
        }
    }

    private fun progressText(bytes: Long, total: Long): String {
        val mbDone = bytes / 1024 / 1024
        return if (total > 0) "$mbDone MB / ${total / 1024 / 1024} MB" else "$mbDone MB"
    }

    private fun ensureChannel() {
        val nm = context.getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(
                CHANNEL_ID,
                "App updates",
                NotificationManager.IMPORTANCE_LOW
            )
        )
    }

    companion object {
        const val WORK_APK_DOWNLOAD = "apk_download"
        const val KEY_VERSION_CODE = "versionCode"
        const val KEY_VERSION_NAME = "versionName"
        const val KEY_APK_URL = "apkUrl"
        const val KEY_SHA256 = "sha256"
        const val KEY_BYTES = "bytes"
        const val KEY_TOTAL = "total"
        const val KEY_FAILURE_MESSAGE = "failureMessage"
        private const val CHANNEL_ID = "fire_stream_app_updates"
        private const val NOTIF_ID = 0xFC_5A_DF
    }
}
