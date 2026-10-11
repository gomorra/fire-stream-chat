package com.firestream.chat.data.worker

import androidx.work.BackoffPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequest
import androidx.work.WorkManager
import com.firestream.chat.data.local.PreferencesDataStore
import com.firestream.chat.data.remote.source.StickerNameSource
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import java.io.IOException
import java.util.concurrent.TimeUnit
import javax.inject.Provider

/** What the scheduler asks WorkManager for, and when it asks for nothing. */
class StickerNameSchedulerTest {

    private val workManager = mockk<WorkManager>(relaxed = true)
    private val nameSource = mockk<StickerNameSource>()
    private val preferences = mockk<PreferencesDataStore>()

    private val scheduler = StickerNameScheduler(Provider { workManager }, nameSource, preferences)

    private val name = slot<String>()
    private val policy = slot<ExistingWorkPolicy>()
    private val request = slot<OneTimeWorkRequest>()

    @Before
    fun setUp() {
        every { nameSource.isSupported } returns true
        coEvery { preferences.autoNameStickers() } returns true
        every { workManager.enqueueUniqueWork(capture(name), capture(policy), capture(request)) } returns mockk()
    }

    private fun verifyNothingQueued() =
        verify(exactly = 0) { workManager.enqueueUniqueWork(any(), any<ExistingWorkPolicy>(), any<OneTimeWorkRequest>()) }

    @Test
    fun `a wanted run is one unique work that waits for a connection and backs off`() = runTest {
        scheduler.runIfWanted()

        assertEquals(StickerNameScheduler.WORK_NAME, name.captured)
        assertEquals("a new reason to run does not wait for a backoff", ExistingWorkPolicy.REPLACE, policy.captured)
        val spec = request.captured.workSpec
        assertEquals(StickerNameWorker::class.java.name, spec.workerClassName)
        assertEquals(NetworkType.CONNECTED, spec.constraints.requiredNetworkType)
        assertEquals(BackoffPolicy.EXPONENTIAL, spec.backoffPolicy)
        assertEquals(TimeUnit.SECONDS.toMillis(StickerNameScheduler.INITIAL_BACKOFF_SECONDS), spec.backoffDelayDuration)
    }

    @Test
    fun `nothing is queued while naming is switched off`() = runTest {
        coEvery { preferences.autoNameStickers() } returns false

        scheduler.runIfWanted()

        verifyNothingQueued()
    }

    @Test
    fun `nothing is queued on a backend without a naming service`() = runTest {
        every { nameSource.isSupported } returns false

        scheduler.runIfWanted()

        verifyNothingQueued()
    }

    @Test
    fun `a switch that cannot be read queues nothing and is not the caller's failure`() = runTest {
        coEvery { preferences.autoNameStickers() } throws IOException("disk")

        scheduler.runIfWanted()

        verifyNothingQueued()
    }

    @Test
    fun `cancel stops the unique run`() {
        scheduler.cancel()

        verify { workManager.cancelUniqueWork(StickerNameScheduler.WORK_NAME) }
    }
}
