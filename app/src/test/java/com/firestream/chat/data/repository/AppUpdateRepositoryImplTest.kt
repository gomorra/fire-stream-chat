package com.firestream.chat.data.repository

import android.content.Context
import androidx.work.Data
import androidx.work.WorkInfo
import com.firestream.chat.BuildConfig
import com.firestream.chat.data.remote.update.NoReleasePublishedException
import com.firestream.chat.data.remote.update.UpdateManifestSource
import com.firestream.chat.data.util.ApkInstaller
import com.firestream.chat.data.worker.ApkDownloadWorker.Companion.KEY_BYTES
import com.firestream.chat.data.worker.ApkDownloadWorker.Companion.KEY_FAILURE_MESSAGE
import com.firestream.chat.data.worker.ApkDownloadWorker.Companion.KEY_TOTAL
import com.firestream.chat.data.worker.ApkDownloadWorker.Companion.KEY_VERSION_CODE
import com.firestream.chat.data.worker.ApkDownloadWorker.Companion.KEY_VERSION_NAME
import com.firestream.chat.domain.model.AppUpdate
import com.firestream.chat.domain.model.UpdateCheckResult
import com.firestream.chat.domain.repository.DownloadProgress
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.io.IOException
import java.util.UUID

class AppUpdateRepositoryImplTest {

    private val manifestSource = mockk<UpdateManifestSource>()
    private val apkInstaller = mockk<ApkInstaller>()
    private val context = mockk<Context>(relaxed = true)

    private val repository = AppUpdateRepositoryImpl(
        manifestSource = manifestSource,
        apkInstaller = apkInstaller,
        context = context
    )

    private fun manifest(versionCode: Int) = AppUpdate(
        versionCode = versionCode,
        versionName = "1.0.0",
        apkUrl = "https://example.com/x.apk",
        sha256 = "abc",
        minSupportedVersionCode = 1,
        releaseNotes = "",
        publishedAt = "",
        mandatory = false
    )

    @Test
    fun `checkForUpdate returns Available when manifest versionCode is higher`() = runTest {
        coEvery { manifestSource.fetchLatest() } returns manifest(Int.MAX_VALUE)

        val result = repository.checkForUpdate().getOrThrow()

        assertTrue(result is UpdateCheckResult.Available)
        assertEquals(Int.MAX_VALUE, (result as UpdateCheckResult.Available).update.versionCode)
    }

    @Test
    fun `checkForUpdate returns UpToDate when manifest versionCode equals installed`() = runTest {
        coEvery { manifestSource.fetchLatest() } returns manifest(BuildConfig.VERSION_CODE)

        val result = repository.checkForUpdate().getOrThrow()

        assertEquals(UpdateCheckResult.UpToDate, result)
    }

    @Test
    fun `checkForUpdate returns UpToDate when manifest versionCode is lower`() = runTest {
        coEvery { manifestSource.fetchLatest() } returns manifest(0)

        val result = repository.checkForUpdate().getOrThrow()

        assertEquals(UpdateCheckResult.UpToDate, result)
    }

    @Test
    fun `checkForUpdate wraps fetch failures as Result_failure`() = runTest {
        coEvery { manifestSource.fetchLatest() } throws IOException("offline")

        val result = repository.checkForUpdate()

        assertTrue(result.isFailure)
        assertEquals("offline", result.exceptionOrNull()?.message)
    }

    @Test
    fun `checkForUpdate returns UpToDate when no release has been published yet`() = runTest {
        coEvery { manifestSource.fetchLatest() } throws NoReleasePublishedException

        val result = repository.checkForUpdate().getOrThrow()

        assertEquals(UpdateCheckResult.UpToDate, result)
    }

    // --- translate(WorkInfo) -> DownloadProgress ---

    private val cacheDir = File("/tmp/firestream-cache-test")

    private fun workInfo(
        state: WorkInfo.State,
        progress: Data = Data.EMPTY,
        outputData: Data = Data.EMPTY
    ): WorkInfo = WorkInfo(
        /* id = */ UUID.randomUUID(),
        /* state = */ state,
        /* tags = */ emptySet(),
        /* outputData = */ outputData,
        /* progress = */ progress,
        /* runAttemptCount = */ 0,
        /* generation = */ 0
    )

    @Test
    fun `translate ENQUEUED maps to indeterminate InProgress`() {
        val result = AppUpdateRepositoryImpl.translate(workInfo(WorkInfo.State.ENQUEUED), cacheDir)
        assertEquals(DownloadProgress.InProgress(0L, -1L), result)
    }

    @Test
    fun `translate BLOCKED maps to indeterminate InProgress`() {
        val result = AppUpdateRepositoryImpl.translate(workInfo(WorkInfo.State.BLOCKED), cacheDir)
        assertEquals(DownloadProgress.InProgress(0L, -1L), result)
    }

    @Test
    fun `translate RUNNING with progress data maps to InProgress with bytes and total`() {
        val progress = Data.Builder()
            .putLong(KEY_BYTES, 4_096L)
            .putLong(KEY_TOTAL, 8_192L)
            .build()
        val result = AppUpdateRepositoryImpl.translate(
            workInfo(WorkInfo.State.RUNNING, progress = progress), cacheDir
        )
        assertEquals(DownloadProgress.InProgress(4_096L, 8_192L), result)
    }

    @Test
    fun `translate RUNNING with empty progress maps to InProgress 0 -1`() {
        val result = AppUpdateRepositoryImpl.translate(workInfo(WorkInfo.State.RUNNING), cacheDir)
        assertEquals(DownloadProgress.InProgress(0L, -1L), result)
    }

    @Test
    fun `translate SUCCEEDED maps to Done with deterministic file path`() {
        val output = Data.Builder()
            .putString(KEY_VERSION_NAME, "1.5.0")
            .putInt(KEY_VERSION_CODE, 408)
            .build()
        val result = AppUpdateRepositoryImpl.translate(
            workInfo(WorkInfo.State.SUCCEEDED, outputData = output), cacheDir
        )
        assertTrue(result is DownloadProgress.Done)
        val done = result as DownloadProgress.Done
        assertEquals(File(cacheDir, "apk_updates/firestream-v1.5.0-408.apk"), done.apkFile)
    }

    @Test
    fun `translate FAILED with message maps to Failed with that message`() {
        val output = Data.Builder().putString(KEY_FAILURE_MESSAGE, "boom").build()
        val result = AppUpdateRepositoryImpl.translate(
            workInfo(WorkInfo.State.FAILED, outputData = output), cacheDir
        )
        assertEquals(DownloadProgress.Failed("boom"), result)
    }

    @Test
    fun `translate FAILED without message defaults to Network error`() {
        val result = AppUpdateRepositoryImpl.translate(workInfo(WorkInfo.State.FAILED), cacheDir)
        assertEquals(DownloadProgress.Failed("Network error"), result)
    }

    @Test
    fun `translate CANCELLED maps to Failed with Cancelled`() {
        val result = AppUpdateRepositoryImpl.translate(workInfo(WorkInfo.State.CANCELLED), cacheDir)
        assertEquals(DownloadProgress.Failed("Cancelled"), result)
    }
}
