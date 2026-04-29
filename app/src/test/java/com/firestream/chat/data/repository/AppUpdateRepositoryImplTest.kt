package com.firestream.chat.data.repository

import com.firestream.chat.BuildConfig
import com.firestream.chat.data.remote.update.UpdateManifestSource
import com.firestream.chat.data.util.ApkDownloader
import com.firestream.chat.data.util.ApkInstaller
import com.firestream.chat.domain.model.AppUpdate
import com.firestream.chat.domain.model.UpdateCheckResult
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException

class AppUpdateRepositoryImplTest {

    private val manifestSource = mockk<UpdateManifestSource>()
    private val apkDownloader = mockk<ApkDownloader>()
    private val apkInstaller = mockk<ApkInstaller>()

    private val repository = AppUpdateRepositoryImpl(
        manifestSource = manifestSource,
        apkDownloader = apkDownloader,
        apkInstaller = apkInstaller
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
}
