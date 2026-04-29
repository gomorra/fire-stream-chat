package com.firestream.chat.data.repository

import com.firestream.chat.BuildConfig
import com.firestream.chat.data.remote.update.UpdateManifestSource
import com.firestream.chat.data.util.ApkDownloader
import com.firestream.chat.data.util.ApkInstaller
import com.firestream.chat.domain.model.AppUpdate
import com.firestream.chat.domain.model.UpdateCheckResult
import com.firestream.chat.domain.repository.AppUpdateRepository
import com.firestream.chat.domain.repository.DownloadProgress
import kotlinx.coroutines.flow.Flow
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class AppUpdateRepositoryImpl @Inject constructor(
    private val manifestSource: UpdateManifestSource,
    private val apkDownloader: ApkDownloader,
    private val apkInstaller: ApkInstaller
) : AppUpdateRepository {

    override suspend fun checkForUpdate(): Result<UpdateCheckResult> = runCatching {
        val latest = manifestSource.fetchLatest()
        if (latest.versionCode > BuildConfig.VERSION_CODE) {
            UpdateCheckResult.Available(latest)
        } else {
            UpdateCheckResult.UpToDate
        }
    }

    override fun downloadUpdate(update: AppUpdate): Flow<DownloadProgress> =
        apkDownloader.download(update)

    override suspend fun installUpdate(apkFile: File): Result<Unit> = runCatching {
        apkInstaller.install(apkFile)
    }
}
