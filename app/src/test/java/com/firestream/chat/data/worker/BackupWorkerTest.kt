package com.firestream.chat.data.worker

import android.app.NotificationManager
import android.content.Context
import android.content.pm.ServiceInfo
import android.os.Build
import androidx.concurrent.futures.CallbackToFutureAdapter
import androidx.core.app.NotificationCompat
import androidx.test.core.app.ApplicationProvider
import androidx.work.ForegroundInfo
import androidx.work.ListenableWorker
import androidx.work.WorkerFactory
import androidx.work.WorkerParameters
import androidx.work.testing.TestListenableWorkerBuilder
import androidx.work.workDataOf
import com.firestream.chat.data.backup.UserCancelledBackupException
import com.firestream.chat.domain.model.BackupProgress
import com.firestream.chat.domain.model.BackupStage
import com.firestream.chat.domain.model.RemoteBackupSummary
import com.firestream.chat.domain.repository.BackupRepository
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.yield
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.io.IOException

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [31], manifest = Config.NONE, application = android.app.Application::class)
class BackupWorkerTest {

    private val context: Context = ApplicationProvider.getApplicationContext()
    private var promotedTo: ForegroundInfo? = null

    private fun worker(
        repo: BackupRepository,
        attemptCount: Int = 0,
        trigger: String = BackupWorker.TRIGGER_SCHEDULED,
        rejectForeground: Boolean = false,
    ): BackupWorker {
        promotedTo = null
        return TestListenableWorkerBuilder<BackupWorker>(context)
            .setInputData(workDataOf(BackupWorker.KEY_TRIGGER to trigger))
            .setRunAttemptCount(attemptCount)
            .setWorkerFactory(object : WorkerFactory() {
                override fun createWorker(
                    appContext: Context,
                    workerClassName: String,
                    workerParameters: WorkerParameters,
                ): ListenableWorker = BackupWorker(appContext, workerParameters, repo)
            })
            .setForegroundUpdater { _, _, info ->
                if (rejectForeground) {
                    CallbackToFutureAdapter.getFuture<Void> {
                        it.setException(IllegalStateException("FGS start not allowed from background"))
                        "rejected"
                    }
                } else {
                    promotedTo = info
                    CallbackToFutureAdapter.getFuture<Void> {
                        it.set(null)
                        "promoted"
                    }
                }
            }
            .build()
    }

    private fun mockRepo(): BackupRepository = mockk {
        every { isSupported } returns true
        every { backupProgress } returns MutableStateFlow(BackupProgress())
    }

    @Test
    fun `skips cleanly when passphrase is not configured`() = runTest {
        val repo = mockRepo()
        coEvery { repo.hasConfiguredPassphrase() } returns false

        val result = worker(repo).doWork()

        assertEquals(ListenableWorker.Result.success(), result)
        coVerify(exactly = 0) { repo.runBackup() }
    }

    @Test
    fun `returns success and promotes to foreground notification for both manual and scheduled runs`() = runTest {
        val repo = mockRepo()
        coEvery { repo.hasConfiguredPassphrase() } returns true
        coEvery { repo.runBackup() } returns Result.success(
            RemoteBackupSummary(createdAt = 1_000L, dbCipherSizeBytes = 100L, legacyMediaComplete = true)
        )

        assertEquals(ListenableWorker.Result.success(), worker(repo, trigger = BackupWorker.TRIGGER_SCHEDULED).doWork())
        assertNotNull("Expected scheduled backup to promote foreground notification", promotedTo)

        assertEquals(ListenableWorker.Result.success(), worker(repo, trigger = BackupWorker.TRIGGER_MANUAL).doWork())
        assertNotNull("Expected manual backup to promote foreground notification", promotedTo)
        if (Build.VERSION.SDK_INT >= 34) {
            assertEquals(ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC, promotedTo!!.foregroundServiceType)
        }
        coVerify(exactly = 2) { repo.runBackup() }
    }

    @Test
    fun `posts and clears progress notification even when foreground service promotion is rejected`() = runTest {
        val progressFlow = MutableStateFlow(BackupProgress())
        val repo = mockk<BackupRepository> {
            every { isSupported } returns true
            every { backupProgress } returns progressFlow
        }
        val nm = context.getSystemService(NotificationManager::class.java)!!
        val shadowNm = shadowOf(nm)
        nm.cancelAll()

        var seenDuringRun: String? = null
        coEvery { repo.hasConfiguredPassphrase() } returns true
        coEvery { repo.runBackup() } coAnswers {
            // Confirm initial notification is posted even when setForeground was rejected
            // (e.g., WorkManager resuming from background after flight mode ends).
            assertNotNull("Expected progress notification during background resume", shadowNm.allNotifications.firstOrNull())
            progressFlow.value = BackupProgress(
                stage = BackupStage.UPLOADING_LEGACY_MEDIA,
                completedItems = 4,
                totalItems = 10,
                fraction = 0.45f,
            )
            repeat(5) { yield() }
            seenDuringRun = shadowNm.allNotifications.firstOrNull()
                ?.extras
                ?.getCharSequence(NotificationCompat.EXTRA_TEXT)
                ?.toString()
            Result.success(
                RemoteBackupSummary(createdAt = 1_000L, dbCipherSizeBytes = 100L, legacyMediaComplete = true)
            )
        }

        val result = worker(repo, trigger = BackupWorker.TRIGGER_MANUAL, rejectForeground = true).doWork()

        assertEquals(ListenableWorker.Result.success(), result)
        assertEquals("Uploading legacy media (4/10)…", seenDuringRun)
        assertNull(
            "Fallback notification must be cleared once doWork() finishes",
            shadowNm.allNotifications.firstOrNull(),
        )
    }

    @Test
    fun `clears fallback progress notification when cancelled mid-run`() = runTest {
        val repo = mockRepo()
        val nm = context.getSystemService(NotificationManager::class.java)!!
        val shadowNm = shadowOf(nm)
        nm.cancelAll()

        coEvery { repo.hasConfiguredPassphrase() } returns true
        coEvery { repo.runBackup() } coAnswers {
            assertNotNull(shadowNm.allNotifications.firstOrNull())
            throw CancellationException("flight mode")
        }

        var cancelled = false
        try {
            worker(repo, trigger = BackupWorker.TRIGGER_MANUAL, rejectForeground = true).doWork()
        } catch (_: CancellationException) {
            cancelled = true
        }

        assertTrue(cancelled)
        assertNull(
            "Fallback notification must be cleared when WorkManager stops the worker",
            shadowNm.allNotifications.firstOrNull(),
        )
    }

    @Test
    fun `degrades gracefully to background execution when foreground promotion is rejected`() = runTest {
        val repo = mockRepo()
        coEvery { repo.hasConfiguredPassphrase() } returns true
        coEvery { repo.runBackup() } returns Result.success(
            RemoteBackupSummary(createdAt = 1_000L, dbCipherSizeBytes = 100L, legacyMediaComplete = true)
        )

        val result = worker(repo, trigger = BackupWorker.TRIGGER_SCHEDULED, rejectForeground = true).doWork()

        assertEquals(ListenableWorker.Result.success(), result)
        coVerify(exactly = 1) { repo.runBackup() }
    }

    @Test
    fun `scheduled backup retries on transient failure and fails after max attempts`() = runTest {
        val repo = mockRepo()
        coEvery { repo.hasConfiguredPassphrase() } returns true
        coEvery { repo.runBackup() } returns Result.failure(IOException("offline"))

        assertEquals(ListenableWorker.Result.retry(), worker(repo, attemptCount = 1).doWork())
        assertEquals(ListenableWorker.Result.failure(), worker(repo, attemptCount = 3).doWork())
    }

    @Test
    fun `manual backup surfaces failure immediately without WorkManager backoff retries`() = runTest {
        val repo = mockRepo()
        coEvery { repo.hasConfiguredPassphrase() } returns true
        coEvery { repo.runBackup() } returns Result.failure(IOException("storage denied"))

        val result = worker(repo, attemptCount = 0, trigger = BackupWorker.TRIGGER_MANUAL).doWork()

        assertEquals(ListenableWorker.Result.failure(), result)
    }

    @Test
    fun `user-initiated cancel returns success while WorkManager cancellation propagates`() = runTest {
        val repo = mockRepo()
        coEvery { repo.hasConfiguredPassphrase() } returns true
        coEvery { repo.runBackup() } throws UserCancelledBackupException()

        assertEquals(ListenableWorker.Result.success(), worker(repo).doWork())

        coEvery { repo.runBackup() } throws CancellationException("constraint lost")
        var propagated = false
        try {
            worker(repo).doWork()
        } catch (_: CancellationException) {
            propagated = true
        }
        assertTrue(propagated)
    }
}
