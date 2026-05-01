package com.firestream.chat.data.worker

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.Uri
import android.os.Build
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.content.FileProvider
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.ForegroundInfo
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import com.firestream.chat.data.util.ApkDownloader
import com.firestream.chat.data.util.ApkInstaller
import com.firestream.chat.domain.model.AppUpdate
import com.firestream.chat.domain.repository.DownloadProgress
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import kotlinx.coroutines.CancellationException
import java.io.File

/**
 * Foreground download of the release APK. Lives in a service so the OS does
 * not throttle network I/O while the screen is locked — the original
 * inline-in-`viewModelScope` flow stalled mid-stream during Doze.
 *
 * The whole `doWork()` is wrapped in a catch-all so any unexpected throwable
 * surfaces as `Result.failure` with a real `KEY_FAILURE_MESSAGE` instead of
 * crashing the host process. `setForeground` itself is wrapped because on
 * Android 14+ the call can be rejected (FGS-from-background restrictions or
 * manifest-merger gaps in WorkManager); when that happens we degrade to a
 * plain background worker — the download still completes, the user just
 * doesn't see the persistent notification.
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
    private val apkDownloader: ApkDownloader,
    private val apkInstaller: ApkInstaller
) : CoroutineWorker(context, params) {

    private var channelCreated = false

    override suspend fun doWork(): Result = try {
        runDownload()
    } catch (e: CancellationException) {
        throw e
    } catch (t: Throwable) {
        Log.e(TAG, "ApkDownloadWorker crashed", t)
        Result.failure(workDataOf(KEY_FAILURE_MESSAGE to friendlyMessage(t)))
    }

    private suspend fun runDownload(): Result {
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
        if (update.apkUrl.isBlank() || update.sha256.isBlank()) {
            return Result.failure(workDataOf(KEY_FAILURE_MESSAGE to "Invalid update manifest"))
        }

        // Try to promote to foreground service so the OS doesn't kill us during
        // Doze or backgrounding. If FGS start is rejected, we fall back to a
        // regular background worker — the download still completes.
        var fgsActive = tryPromoteForeground(0L, -1L)

        var terminal: DownloadProgress = DownloadProgress.Failed("No progress emitted")
        apkDownloader.download(update).collect { progress ->
            terminal = progress
            if (progress is DownloadProgress.InProgress) {
                setProgress(workDataOf(
                    KEY_BYTES to progress.bytesDownloaded,
                    KEY_TOTAL to progress.totalBytes
                ))
                if (fgsActive) {
                    fgsActive = tryPromoteForeground(progress.bytesDownloaded, progress.totalBytes)
                }
            }
        }
        return when (val t = terminal) {
            is DownloadProgress.Done -> {
                postInstallReadyNotification(t.apkFile)
                Result.success(workDataOf(
                    KEY_VERSION_CODE to update.versionCode,
                    KEY_VERSION_NAME to update.versionName
                ))
            }
            is DownloadProgress.Failed -> {
                // On the first checksum mismatch the downloader has already
                // wiped the partial file; let WorkManager retry once with
                // backoff so a stale-CDN or mid-write-truncation case
                // self-heals before we surface a hard error to the user.
                val isChecksumMismatch = t.message.startsWith("Checksum mismatch")
                if (isChecksumMismatch && runAttemptCount < 1) {
                    Result.retry()
                } else if (isChecksumMismatch) {
                    Result.failure(workDataOf(
                        KEY_FAILURE_MESSAGE to "Update file is corrupt — please report to the team"
                    ))
                } else {
                    Result.failure(workDataOf(KEY_FAILURE_MESSAGE to t.message))
                }
            }
            is DownloadProgress.InProgress -> Result.failure(
                workDataOf(KEY_FAILURE_MESSAGE to "Download ended without completion")
            )
        }
    }

    private suspend fun tryPromoteForeground(bytes: Long, total: Long): Boolean = try {
        setForeground(buildForegroundInfo(bytes, total))
        true
    } catch (e: CancellationException) {
        throw e
    } catch (t: Throwable) {
        Log.w(TAG, "setForeground rejected — continuing as background worker", t)
        false
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

    private fun postInstallReadyNotification(apkFile: File) {
        ensureChannel()
        val uri: Uri = FileProvider.getUriForFile(context, apkInstaller.authority, apkFile)
        val installIntent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, "application/vnd.android.package-archive")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        // PendingIntent.getActivity from a notification tap qualifies for the
        // background-activity-launch exemption; broadcasts do not. Using the
        // activity PI lets the user install even when the app is backgrounded.
        val pendingIntent = PendingIntent.getActivity(
            context,
            REQUEST_INSTALL,
            installIntent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val notif = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_sys_download_done)
            .setContentTitle("Update ready")
            .setContentText("Tap to install")
            .setContentIntent(pendingIntent)
            .setAutoCancel(true)
            .setOngoing(false)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .build()
        context.getSystemService(NotificationManager::class.java).notify(NOTIF_ID, notif)
    }

    private fun progressText(bytes: Long, total: Long): String {
        val mbDone = bytes / 1024 / 1024
        return if (total > 0) "$mbDone MB / ${total / 1024 / 1024} MB" else "$mbDone MB"
    }

    private fun ensureChannel() {
        if (channelCreated) return
        context.getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel(CHANNEL_ID, "App updates", NotificationManager.IMPORTANCE_LOW)
        )
        channelCreated = true
    }

    private fun friendlyMessage(t: Throwable): String =
        t.message?.takeIf { it.isNotBlank() } ?: t.javaClass.simpleName

    companion object {
        private const val TAG = "ApkDownloadWorker"
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
        private const val REQUEST_INSTALL = 0xFC_5A_E0
    }
}
