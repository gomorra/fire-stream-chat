package com.firestream.chat.data.worker

import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequest
import androidx.work.WorkManager
import com.firestream.chat.data.local.AutoDownloadOption
import com.firestream.chat.data.local.PreferencesDataStore
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import javax.inject.Provider

/** What the failed-download retry asks WorkManager for, per auto-download preference. */
class MediaBackfillSchedulerTest {

    private val workManager = mockk<WorkManager>(relaxed = true)
    private val preferencesDataStore = mockk<PreferencesDataStore>()
    private val scheduler = MediaBackfillScheduler(Provider { workManager }, preferencesDataStore)

    private val name = slot<String>()
    private val policy = slot<ExistingWorkPolicy>()
    private val request = slot<OneTimeWorkRequest>()

    @Before
    fun setUp() {
        every { workManager.enqueueUniqueWork(capture(name), capture(policy), capture(request)) } returns mockk()
    }

    private fun preference(option: AutoDownloadOption) {
        every { preferencesDataStore.autoDownloadFlow } returns flowOf(option)
    }

    @Test
    fun `queues one backfill run that keeps existing work and waits for any network`() = runTest {
        preference(AutoDownloadOption.ALWAYS)

        scheduler.retryDownloads()

        assertEquals(MediaBackfillScheduler.RETRY_WORK_NAME, name.captured)
        assertEquals(ExistingWorkPolicy.KEEP, policy.captured)
        val spec = request.captured.workSpec
        assertEquals(MediaBackfillWorker::class.java.name, spec.workerClassName)
        assertEquals(NetworkType.CONNECTED, spec.constraints.requiredNetworkType)
        // Not a manual run: the worker must keep honouring the preference.
        assertEquals(false, spec.input.getBoolean(MediaBackfillWorker.KEY_MANUAL, false))
    }

    @Test
    fun `Wi-Fi only waits for an unmetered network`() = runTest {
        preference(AutoDownloadOption.WIFI_ONLY)

        scheduler.retryDownloads()

        assertEquals(NetworkType.UNMETERED, request.captured.workSpec.constraints.requiredNetworkType)
    }

    @Test
    fun `never queues nothing`() = runTest {
        preference(AutoDownloadOption.NEVER)

        scheduler.retryDownloads()

        verify(exactly = 0) { workManager.enqueueUniqueWork(any(), any(), any<OneTimeWorkRequest>()) }
    }
}
