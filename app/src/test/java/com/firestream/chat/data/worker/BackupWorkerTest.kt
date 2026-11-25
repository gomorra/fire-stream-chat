package com.firestream.chat.data.worker

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.work.ListenableWorker
import androidx.work.WorkerParameters
import com.firestream.chat.domain.model.RemoteBackupSummary
import com.firestream.chat.domain.repository.BackupRepository
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.IOException

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [31], manifest = Config.NONE, application = android.app.Application::class)
class BackupWorkerTest {

    private val context: Context = ApplicationProvider.getApplicationContext()

    private fun worker(
        repo: BackupRepository,
        attemptCount: Int = 0,
    ): BackupWorker {
        val params = mockk<WorkerParameters>(relaxed = true) {
            every { runAttemptCount } returns attemptCount
        }
        return BackupWorker(context, params, repo)
    }

    @Test
    fun `skips cleanly when passphrase is not configured`() = runTest {
        val repo = mockk<BackupRepository>()
        every { repo.isSupported } returns true
        coEvery { repo.hasConfiguredPassphrase() } returns false

        val result = worker(repo).doWork()

        assertEquals(ListenableWorker.Result.success(), result)
        coVerify(exactly = 0) { repo.runBackup() }
    }

    @Test
    fun `returns success when backup succeeds`() = runTest {
        val repo = mockk<BackupRepository>()
        every { repo.isSupported } returns true
        coEvery { repo.hasConfiguredPassphrase() } returns true
        coEvery { repo.runBackup() } returns Result.success(
            RemoteBackupSummary(createdAt = 1_000L, dbCipherSizeBytes = 100L, legacyMediaComplete = true)
        )

        val result = worker(repo).doWork()

        assertEquals(ListenableWorker.Result.success(), result)
        coVerify(exactly = 1) { repo.runBackup() }
    }

    @Test
    fun `retries on transient failure and fails after max attempts`() = runTest {
        val repo = mockk<BackupRepository>()
        every { repo.isSupported } returns true
        coEvery { repo.hasConfiguredPassphrase() } returns true
        coEvery { repo.runBackup() } returns Result.failure(IOException("offline"))

        assertEquals(ListenableWorker.Result.retry(), worker(repo, attemptCount = 1).doWork())
        assertEquals(ListenableWorker.Result.failure(), worker(repo, attemptCount = 3).doWork())
    }
}
