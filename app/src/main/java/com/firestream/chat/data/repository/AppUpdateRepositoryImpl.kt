package com.firestream.chat.data.repository

import android.content.Context
import androidx.work.Constraints
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.workDataOf
import com.firestream.chat.BuildConfig
import com.firestream.chat.data.remote.update.NoReleasePublishedException
import com.firestream.chat.data.remote.update.UpdateManifestSource
import com.firestream.chat.data.util.ApkInstaller
import com.firestream.chat.data.worker.ApkDownloadWorker
import com.firestream.chat.data.worker.ApkDownloadWorker.Companion.KEY_APK_URL
import com.firestream.chat.data.worker.ApkDownloadWorker.Companion.KEY_BYTES
import com.firestream.chat.data.worker.ApkDownloadWorker.Companion.KEY_FAILURE_MESSAGE
import com.firestream.chat.data.worker.ApkDownloadWorker.Companion.KEY_SHA256
import com.firestream.chat.data.worker.ApkDownloadWorker.Companion.KEY_TOTAL
import com.firestream.chat.data.worker.ApkDownloadWorker.Companion.KEY_VERSION_CODE
import com.firestream.chat.data.worker.ApkDownloadWorker.Companion.KEY_VERSION_NAME
import com.firestream.chat.data.worker.ApkDownloadWorker.Companion.WORK_APK_DOWNLOAD
import com.firestream.chat.domain.model.AppUpdate
import com.firestream.chat.domain.model.UpdateCheckResult
import com.firestream.chat.domain.repository.AppUpdateRepository
import com.firestream.chat.domain.repository.DownloadProgress
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.mapNotNull
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class AppUpdateRepositoryImpl @Inject constructor(
    private val manifestSource: UpdateManifestSource,
    private val apkInstaller: ApkInstaller,
    @ApplicationContext private val context: Context
) : AppUpdateRepository {

    override suspend fun checkForUpdate(): Result<UpdateCheckResult> = runCatching {
        val latest = try {
            manifestSource.fetchLatest()
        } catch (_: NoReleasePublishedException) {
            return@runCatching UpdateCheckResult.UpToDate
        }
        if (latest.versionCode > BuildConfig.VERSION_CODE) {
            UpdateCheckResult.Available(latest)
        } else {
            UpdateCheckResult.UpToDate
        }
    }

    override fun downloadUpdate(update: AppUpdate): Flow<DownloadProgress> {
        val wm = WorkManager.getInstance(context)
        val request = OneTimeWorkRequestBuilder<ApkDownloadWorker>()
            .setInputData(workDataOf(
                KEY_VERSION_CODE to update.versionCode,
                KEY_VERSION_NAME to update.versionName,
                KEY_APK_URL to update.apkUrl,
                KEY_SHA256 to update.sha256
            ))
            .setConstraints(
                Constraints.Builder()
                    .setRequiredNetworkType(NetworkType.CONNECTED)
                    .build()
            )
            .build()
        // KEEP — re-entry to Settings during a download must rejoin the existing
        // worker, not cancel it and restart from byte 0.
        wm.enqueueUniqueWork(WORK_APK_DOWNLOAD, ExistingWorkPolicy.KEEP, request)
        return progressFlow()
    }

    override fun observeUpdateDownload(): Flow<DownloadProgress> = progressFlow()

    override fun cancelUpdateDownload() {
        WorkManager.getInstance(context).cancelUniqueWork(WORK_APK_DOWNLOAD)
    }

    override suspend fun installUpdate(apkFile: File): Result<Unit> = runCatching {
        apkInstaller.install(apkFile)
    }

    private fun progressFlow(): Flow<DownloadProgress> =
        WorkManager.getInstance(context)
            .getWorkInfosForUniqueWorkFlow(WORK_APK_DOWNLOAD)
            .mapNotNull { it.firstOrNull() }
            .map { wi -> translate(wi, context.cacheDir) }
            .distinctUntilChanged()

    companion object {
        /**
         * Pure translator from `WorkInfo` to `DownloadProgress`. Pulled out so the
         * state machine is testable without `WorkManager`. Reads version metadata
         * from `wi.inputData` so the same flow rehydrates a download started in a
         * prior VM lifetime.
         */
        internal fun translate(wi: WorkInfo, cacheDir: File): DownloadProgress = when (wi.state) {
            WorkInfo.State.ENQUEUED, WorkInfo.State.BLOCKED ->
                DownloadProgress.InProgress(0L, -1L)
            WorkInfo.State.RUNNING -> DownloadProgress.InProgress(
                bytesDownloaded = wi.progress.getLong(KEY_BYTES, 0L),
                totalBytes = wi.progress.getLong(KEY_TOTAL, -1L)
            )
            WorkInfo.State.SUCCEEDED -> {
                val versionName = wi.outputData.getString(KEY_VERSION_NAME).orEmpty()
                val versionCode = wi.outputData.getInt(KEY_VERSION_CODE, 0)
                DownloadProgress.Done(
                    File(cacheDir, "apk_updates/firestream-v$versionName-$versionCode.apk")
                )
            }
            WorkInfo.State.FAILED -> DownloadProgress.Failed(
                wi.outputData.getString(KEY_FAILURE_MESSAGE) ?: "Network error"
            )
            WorkInfo.State.CANCELLED -> DownloadProgress.Failed("Cancelled")
        }
    }
}
