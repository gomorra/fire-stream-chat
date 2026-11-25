// region: AGENT-NOTE
// Responsibility: Schedules, reconciles, or cancels periodic encrypted cloud
//   backups via WorkManager (`BackupWorker`) with Wi-Fi (UNMETERED) + charging
//   constraints and an initial delay so switching frequencies never triggers an
//   immediate duplicate run.
// Collaborators: BackupManager (calls schedule on frequency change and cancel
//   on sign-out), FireStreamApp (reconciles on startup with KEEP), WorkManager
//   (injected via Provider to avoid circular init with FireStreamApp).
// endregion

package com.firestream.chat.data.worker

import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import com.firestream.chat.domain.model.BackupFrequency
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Provider
import javax.inject.Singleton

@Singleton
class BackupScheduler @Inject constructor(
    private val workManagerProvider: Provider<WorkManager>,
) {
    /** User changed the backup cadence — replaces any existing schedule. */
    fun schedule(frequency: BackupFrequency) {
        enqueue(frequency, ExistingPeriodicWorkPolicy.UPDATE)
    }

    /** Startup reconciliation — keeps an already-scheduled job without resetting its timer. */
    fun reconcile(frequency: BackupFrequency) {
        enqueue(frequency, ExistingPeriodicWorkPolicy.KEEP)
    }

    fun cancel() {
        workManagerProvider.get().cancelUniqueWork(BackupWorker.UNIQUE_NAME)
    }

    private fun enqueue(frequency: BackupFrequency, policy: ExistingPeriodicWorkPolicy) {
        val workManager = workManagerProvider.get()
        val intervalHours = when (frequency) {
            BackupFrequency.OFF -> {
                workManager.cancelUniqueWork(BackupWorker.UNIQUE_NAME)
                return
            }
            BackupFrequency.DAILY -> 24L
            BackupFrequency.WEEKLY -> 24L * 7L
        }
        val constraints = Constraints.Builder()
            .setRequiredNetworkType(NetworkType.UNMETERED)
            .setRequiresCharging(true)
            .build()
        val request = PeriodicWorkRequestBuilder<BackupWorker>(intervalHours, TimeUnit.HOURS)
            .setInitialDelay(INITIAL_DELAY_HOURS, TimeUnit.HOURS)
            .setConstraints(constraints)
            .build()
        workManager.enqueueUniquePeriodicWork(
            BackupWorker.UNIQUE_NAME,
            policy,
            request,
        )
    }

    companion object {
        private const val INITIAL_DELAY_HOURS = 1L
    }
}
