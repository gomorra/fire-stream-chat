package com.firestream.chat.data.worker

import androidx.concurrent.futures.CallbackToFutureAdapter
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequest
import androidx.work.PeriodicWorkRequest
import androidx.work.WorkInfo
import androidx.work.WorkManager
import com.firestream.chat.domain.model.BackupFrequency
import com.firestream.chat.domain.model.ManualBackupWorkState
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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
        assertEquals(BackupWorker.TRIGGER_SCHEDULED, spec.input.getString(BackupWorker.KEY_TRIGGER))
    }

    @Test
    fun `schedule DAILY with mobile data allowed uses CONNECTED network constraint`() {
        val requestSlot = slot<PeriodicWorkRequest>()
        scheduler.schedule(BackupFrequency.DAILY, allowMobileData = true)

        verify(exactly = 1) {
            workManager.enqueueUniquePeriodicWork(
                BackupWorker.UNIQUE_NAME,
                ExistingPeriodicWorkPolicy.UPDATE,
                capture(requestSlot),
            )
        }
        assertEquals(NetworkType.CONNECTED, requestSlot.captured.workSpec.constraints.requiredNetworkType)
        assertTrue(requestSlot.captured.workSpec.constraints.requiresCharging())
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

    @Test
    fun `enqueueManual uses UNMETERED by default without charging and KEEP policy`() {
        val requestSlot = slot<OneTimeWorkRequest>()
        scheduler.enqueueManual(allowMobileData = false)

        verify(exactly = 1) {
            workManager.enqueueUniqueWork(
                BackupWorker.UNIQUE_MANUAL_NAME,
                ExistingWorkPolicy.KEEP,
                capture(requestSlot),
            )
        }
        val spec = requestSlot.captured.workSpec
        assertEquals(NetworkType.UNMETERED, spec.constraints.requiredNetworkType)
        assertFalse(spec.constraints.requiresCharging())
        assertEquals(BackupWorker.TRIGGER_MANUAL, spec.input.getString(BackupWorker.KEY_TRIGGER))
    }

    @Test
    fun `enqueueManual with mobile data and replaceExisting uses CONNECTED and REPLACE`() {
        val requestSlot = slot<OneTimeWorkRequest>()
        scheduler.enqueueManual(allowMobileData = true, replaceExisting = true)

        verify(exactly = 1) {
            workManager.enqueueUniqueWork(
                BackupWorker.UNIQUE_MANUAL_NAME,
                ExistingWorkPolicy.REPLACE,
                capture(requestSlot),
            )
        }
        assertEquals(NetworkType.CONNECTED, requestSlot.captured.workSpec.constraints.requiredNetworkType)
    }

    @Test
    fun `updateManualConstraintIfActive replaces ENQUEUED work when enabling mobile data and leaves RUNNING work alone`() = runTest {
        val enqueuedInfo = mockk<WorkInfo> { every { state } returns WorkInfo.State.ENQUEUED }
        every { workManager.getWorkInfosForUniqueWork(BackupWorker.UNIQUE_MANUAL_NAME) } returns
            CallbackToFutureAdapter.getFuture { it.set(listOf(enqueuedInfo)); "enqueued" }

        scheduler.updateManualConstraintIfActive(allowMobileData = true)

        verify(exactly = 1) {
            workManager.enqueueUniqueWork(
                BackupWorker.UNIQUE_MANUAL_NAME,
                ExistingWorkPolicy.REPLACE,
                any<OneTimeWorkRequest>(),
            )
        }

        // Enabling mobile data while already RUNNING must not interrupt the active upload.
        val runningInfo = mockk<WorkInfo> { every { state } returns WorkInfo.State.RUNNING }
        every { workManager.getWorkInfosForUniqueWork(BackupWorker.UNIQUE_MANUAL_NAME) } returns
            CallbackToFutureAdapter.getFuture { it.set(listOf(runningInfo)); "running" }

        scheduler.updateManualConstraintIfActive(allowMobileData = true)

        verify(exactly = 1) {
            workManager.enqueueUniqueWork(
                BackupWorker.UNIQUE_MANUAL_NAME,
                ExistingWorkPolicy.REPLACE,
                any<OneTimeWorkRequest>(),
            )
        }

        // Disabling mobile data while RUNNING replaces with UNMETERED so cellular upload pauses immediately.
        val capturedRequests = mutableListOf<OneTimeWorkRequest>()
        scheduler.updateManualConstraintIfActive(allowMobileData = false)

        verify(exactly = 2) {
            workManager.enqueueUniqueWork(
                BackupWorker.UNIQUE_MANUAL_NAME,
                ExistingWorkPolicy.REPLACE,
                capture(capturedRequests),
            )
        }
        assertEquals(NetworkType.CONNECTED, capturedRequests[0].workSpec.constraints.requiredNetworkType)
        assertEquals(NetworkType.UNMETERED, capturedRequests[1].workSpec.constraints.requiredNetworkType)
    }

    @Test
    fun `cancelManual cancels only manual work while cancel cancels both periodic and manual`() {
        scheduler.cancelManual()
        verify(exactly = 1) { workManager.cancelUniqueWork(BackupWorker.UNIQUE_MANUAL_NAME) }
        verify(exactly = 0) { workManager.cancelUniqueWork(BackupWorker.UNIQUE_NAME) }

        scheduler.cancel()
        verify(exactly = 1) { workManager.cancelUniqueWork(BackupWorker.UNIQUE_NAME) }
        verify(exactly = 2) { workManager.cancelUniqueWork(BackupWorker.UNIQUE_MANUAL_NAME) }
    }

    @Test
    fun `mapManualWorkState maps WorkInfo states cleanly`() {
        val running = mockk<WorkInfo> { every { state } returns WorkInfo.State.RUNNING }
        val enqueued = mockk<WorkInfo> { every { state } returns WorkInfo.State.ENQUEUED }
        val succeeded = mockk<WorkInfo> { every { state } returns WorkInfo.State.SUCCEEDED }

        assertEquals(ManualBackupWorkState.IDLE, BackupScheduler.mapManualWorkState(emptyList()))
        assertEquals(ManualBackupWorkState.IDLE, BackupScheduler.mapManualWorkState(listOf(succeeded)))
        assertEquals(ManualBackupWorkState.WAITING_FOR_NETWORK, BackupScheduler.mapManualWorkState(listOf(enqueued)))
        assertEquals(ManualBackupWorkState.RUNNING, BackupScheduler.mapManualWorkState(listOf(enqueued, running)))
    }
}
