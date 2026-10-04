package com.firestream.chat.data.worker

import androidx.work.BackoffPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequest
import androidx.work.WorkManager
import com.firestream.chat.data.local.dao.StickerDao
import com.firestream.chat.data.remote.source.StickerPackSource
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import java.util.concurrent.TimeUnit
import javax.inject.Provider

/** What the scheduler asks WorkManager for, and when it asks for nothing. */
class StickerSyncSchedulerTest {

    private val workManager = mockk<WorkManager>(relaxed = true)
    private val stickerDao = mockk<StickerDao>()
    private val packSource = mockk<StickerPackSource>()

    private val scheduler = StickerSyncScheduler(Provider { workManager }, stickerDao, packSource)

    private val name = slot<String>()
    private val policy = slot<ExistingWorkPolicy>()
    private val request = slot<OneTimeWorkRequest>()

    @Before
    fun setUp() {
        every { packSource.isSupported } returns true
        coEvery { stickerDao.countUnsyncedPacks() } returns 1
        every { workManager.enqueueUniqueWork(capture(name), capture(policy), capture(request)) } returns mockk()
    }

    @Test
    fun `an unsynced pack queues one unique run that waits for a connection and backs off`() = runTest {
        scheduler.syncIfPending()

        assertEquals(StickerSyncScheduler.WORK_NAME, name.captured)
        assertEquals("a burst of changes is one run", ExistingWorkPolicy.KEEP, policy.captured)
        val spec = request.captured.workSpec
        assertEquals(StickerSyncWorker::class.java.name, spec.workerClassName)
        assertEquals(NetworkType.CONNECTED, spec.constraints.requiredNetworkType)
        assertEquals(BackoffPolicy.EXPONENTIAL, spec.backoffPolicy)
        assertEquals(TimeUnit.SECONDS.toMillis(StickerSyncScheduler.INITIAL_BACKOFF_SECONDS), spec.backoffDelayDuration)
    }

    @Test
    fun `nothing is queued while every pack is synced`() = runTest {
        coEvery { stickerDao.countUnsyncedPacks() } returns 0

        scheduler.syncIfPending()

        verify(exactly = 0) { workManager.enqueueUniqueWork(any(), any<ExistingWorkPolicy>(), any<OneTimeWorkRequest>()) }
    }

    @Test
    fun `nothing is queued on a backend that keeps no packs`() = runTest {
        every { packSource.isSupported } returns false

        scheduler.syncIfPending()

        verify(exactly = 0) { workManager.enqueueUniqueWork(any(), any<ExistingWorkPolicy>(), any<OneTimeWorkRequest>()) }
    }

    @Test
    fun `a run that cannot be queued is not the caller's failure`() = runTest {
        every {
            workManager.enqueueUniqueWork(any(), any<ExistingWorkPolicy>(), any<OneTimeWorkRequest>())
        } throws IllegalStateException("WorkManager is not ready")

        // The edit that asked is in Room either way. The pack stays pending for the next call.
        scheduler.syncIfPending()
    }

    @Test
    fun `cancel stops the unique run`() {
        scheduler.cancel()

        verify { workManager.cancelUniqueWork(StickerSyncScheduler.WORK_NAME) }
    }
}
