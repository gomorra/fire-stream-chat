package com.firestream.chat.domain.repository

import com.firestream.chat.domain.model.AppUpdate
import com.firestream.chat.domain.model.UpdateCheckResult
import kotlinx.coroutines.flow.Flow
import java.io.File

interface AppUpdateRepository {
    /** Fetches the latest manifest and compares it against the installed `versionCode`. */
    suspend fun checkForUpdate(): Result<UpdateCheckResult>

    /**
     * Enqueues a foreground worker to download the APK to app-private storage,
     * verifying SHA-256 against the manifest. The returned flow is the worker's
     * progress stream — the terminal emission is either [DownloadProgress.Done]
     * with the on-disk file or [DownloadProgress.Failed].
     *
     * Multiple subscriptions return the same in-flight worker (re-entry on
     * Settings rehydrates the dialog rather than restarting from byte 0).
     */
    fun downloadUpdate(update: AppUpdate): Flow<DownloadProgress>

    /**
     * Observes any in-flight download enqueued by [downloadUpdate]. Used by
     * the Settings screen to rehydrate its progress dialog when the user
     * re-enters mid-download. Emits [DownloadProgress.InProgress] only when
     * a worker is actually running; otherwise the flow stays cold/empty.
     */
    fun observeUpdateDownload(): Flow<DownloadProgress>

    /** Cancels the unique APK-download worker, if any. Partial file is kept on disk. */
    fun cancelUpdateDownload()

    /** Hands the verified APK to the system installer (FileProvider + ACTION_VIEW). */
    suspend fun installUpdate(apkFile: File): Result<Unit>
}

sealed interface DownloadProgress {
    data class InProgress(val bytesDownloaded: Long, val totalBytes: Long) : DownloadProgress
    data class Done(val apkFile: File) : DownloadProgress
    data class Failed(val message: String) : DownloadProgress
}
