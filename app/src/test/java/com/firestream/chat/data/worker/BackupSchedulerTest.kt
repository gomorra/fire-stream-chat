package com.firestream.chat.data.worker

import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequest
import androidx.work.WorkManager
import com.firestream.chat.domain.model.BackupFrequency
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.TimeUnit
import javax.inject.Provider

class BackupSchedulerTest {

    private val workManager = mockk<WorkManager>(relaxed = true)
    private val scheduler = BackupScheduler(Provider { workManager })

    @Test
    fun `schedule OFF cancels unique periodic backup work`() {
        scheduler.schedule(BackupFrequency.OFF)

        verify(exactly = 1) { workManager.cancelUniqueWork(BackupWorker.UNIQUE_NAME) }
        verify(exactly = 0) { workManager.enqueueUniquePeriodicWork(any(), any(), any()) }
    }

    @Test
    fun `schedule DAILY enqueues 24h periodic work with Wi-Fi, charging, initial delay, and UPDATE policy`() {
        val requestSlot = slot<PeriodicWorkRequest>()
        scheduler.schedule(BackupFrequency.DAILY)

        verify(exactly = 1) {
            workManager.enqueueUniquePeriodicWork(
                BackupWorker.UNIQUE_NAME,
                ExistingPeriodicWorkPolicy.UPDATE,
                capture(requestSlot),
            )
        }
        val spec = requestSlot.captured.workSpec
        assertEquals(TimeUnit.HOURS.toMillis(24L), spec.intervalDuration)
        assertEquals(TimeUnit.HOURS.toMillis(1L), spec.initialDelay)
        assertEquals(NetworkType.UNMETERED, spec.constraints.requiredNetworkType)
        assertTrue(spec.constraints.requiresCharging())
    }

    @Test
    fun `reconcile WEEKLY uses KEEP policy and 7-day interval`() {
        val requestSlot = slot<PeriodicWorkRequest>()
        scheduler.reconcile(BackupFrequency.WEEKLY)

        verify(exactly = 1) {
            workManager.enqueueUniquePeriodicWork(
                BackupWorker.UNIQUE_NAME,
                ExistingPeriodicWorkPolicy.KEEP,
                capture(requestSlot),
            )
        }
        val spec = requestSlot.captured.workSpec
        assertEquals(TimeUnit.DAYS.toMillis(7L), spec.intervalDuration)
        assertEquals(NetworkType.UNMETERED, spec.constraints.requiredNetworkType)
        assertTrue(spec.constraints.requiresCharging())
    }
}
