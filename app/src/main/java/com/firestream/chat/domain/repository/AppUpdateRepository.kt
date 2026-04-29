package com.firestream.chat.domain.repository

import com.firestream.chat.domain.model.AppUpdate
import com.firestream.chat.domain.model.UpdateCheckResult
import kotlinx.coroutines.flow.Flow
import java.io.File

interface AppUpdateRepository {
    /** Fetches the latest manifest and compares it against the installed `versionCode`. */
    suspend fun checkForUpdate(): Result<UpdateCheckResult>

    /**
     * Downloads the APK to app-private storage and verifies its SHA-256 against
     * the manifest. Emits progress; the terminal emission is either
     * [DownloadProgress.Done] with the on-disk file or [DownloadProgress.Failed].
     */
    fun downloadUpdate(update: AppUpdate): Flow<DownloadProgress>

    /** Hands the verified APK to the system installer (FileProvider + ACTION_VIEW). */
    suspend fun installUpdate(apkFile: File): Result<Unit>
}

sealed interface DownloadProgress {
    data class InProgress(val bytesDownloaded: Long, val totalBytes: Long) : DownloadProgress
    data class Done(val apkFile: File) : DownloadProgress
    data class Failed(val message: String) : DownloadProgress
}
