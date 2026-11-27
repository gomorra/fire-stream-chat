// region: AGENT-NOTE
// Responsibility: Schedules, reconciles, observes, or cancels periodic and
//   one-time manual encrypted cloud backups via WorkManager (`BackupWorker`).
//   Enforces Wi-Fi (`UNMETERED`) by default or `CONNECTED` when mobile data is
//   opted in, plus charging on periodic runs, and updates active manual jobs
//   immediately when the user toggles mobile data.
// Collaborators: BackupManager (calls schedule/enqueueManual/cancel/observe),
//   FireStreamApp (reconciles periodic schedule on startup with KEEP),
//   WorkManager (injected via Provider to avoid circular init with FireStreamApp).
// endregion

package com.firestream.chat.data.worker

import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.workDataOf
import com.firestream.chat.data.util.rethrowIfCancellation
import com.firestream.chat.domain.model.BackupFrequency
import com.firestream.chat.domain.model.ManualBackupWorkState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Provider
import javax.inject.Singleton

@Singleton
class BackupScheduler @Inject constructor(
    private val workManagerProvider: Provider<WorkManager>,
) {
    /** User changed the backup cadence or mobile-data policy — replaces any existing schedule. */
    fun schedule(frequency: BackupFrequency, allowMobileData: Boolean = false) {
        enqueuePeriodic(frequency, allowMobileData, ExistingPeriodicWorkPolicy.UPDATE)
    }

    /** Startup reconciliation — keeps an already-scheduled job without resetting its timer. */
    fun reconcile(frequency: BackupFrequency, allowMobileData: Boolean = false) {
        enqueuePeriodic(frequency, allowMobileData, ExistingPeriodicWorkPolicy.KEEP)
    }

    /** Enqueues a user-triggered manual backup with network constraints (no charging requirement). */
    fun enqueueManual(allowMobileData: Boolean = false, replaceExisting: Boolean = false) {
        val workManager = workManagerProvider.get()
        val constraints = Constraints.Builder()
            .setRequiredNetworkType(networkTypeFor(allowMobileData))
            .build()
        val request = OneTimeWorkRequestBuilder<BackupWorker>()
            .setInputData(workDataOf(BackupWorker.KEY_TRIGGER to BackupWorker.TRIGGER_MANUAL))
            .setConstraints(constraints)
            .build()
        workManager.enqueueUniqueWork(
            BackupWorker.UNIQUE_MANUAL_NAME,
            if (replaceExisting) ExistingWorkPolicy.REPLACE else ExistingWorkPolicy.KEEP,
            request,
        )
    }

    /**
     * Re-applies the network constraint to an in-flight or waiting manual backup
     * when the user flips "Back up over mobile data":
     * - Turning mobile data ON while waiting for Wi-Fi replaces the queued job so it starts immediately.
     * - Turning mobile data OFF while queued or running replaces it with `UNMETERED` so cellular uploads pause.
     */
    suspend fun updateManualConstraintIfActive(allowMobileData: Boolean) {
        val states = try {
            withContext(Dispatchers.IO) {
                workManagerProvider.get()
                    .getWorkInfosForUniqueWork(BackupWorker.UNIQUE_MANUAL_NAME)
                    .get()
                    .orEmpty()
                    .map { it.state }
            }
        } catch (e: Exception) {
            e.rethrowIfCancellation()
            return
        }
        val shouldReplace = if (allowMobileData) {
            states.any { it == WorkInfo.State.ENQUEUED || it == WorkInfo.State.BLOCKED }
        } else {
            states.any {
                it == WorkInfo.State.ENQUEUED ||
                    it == WorkInfo.State.BLOCKED ||
                    it == WorkInfo.State.RUNNING
            }
        }
        if (shouldReplace) {
            enqueueManual(allowMobileData = allowMobileData, replaceExisting = true)
        }
    }

    fun observeManualBackupState(): Flow<ManualBackupWorkState> =
        workManagerProvider.get()
            .getWorkInfosForUniqueWorkFlow(BackupWorker.UNIQUE_MANUAL_NAME)
            .map { infos -> mapManualWorkState(infos) }
            .distinctUntilChanged()

    /** Cancels only the one-time manual backup, leaving any Daily/Weekly schedule intact. */
    fun cancelManual() {
        workManagerProvider.get().cancelUniqueWork(BackupWorker.UNIQUE_MANUAL_NAME)
    }

    /** Cancels all scheduled and manual backup work (used on sign-out). */
    fun cancel() {
        val workManager = workManagerProvider.get()
        workManager.cancelUniqueWork(BackupWorker.UNIQUE_NAME)
        workManager.cancelUniqueWork(BackupWorker.UNIQUE_MANUAL_NAME)
    }

    private fun enqueuePeriodic(
        frequency: BackupFrequency,
        allowMobileData: Boolean,
        policy: ExistingPeriodicWorkPolicy,
    ) {
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
            .setRequiredNetworkType(networkTypeFor(allowMobileData))
            .setRequiresCharging(true)
            .build()
        val request = PeriodicWorkRequestBuilder<BackupWorker>(intervalHours, TimeUnit.HOURS)
            .setInitialDelay(INITIAL_DELAY_HOURS, TimeUnit.HOURS)
            .setInputData(workDataOf(BackupWorker.KEY_TRIGGER to BackupWorker.TRIGGER_SCHEDULED))
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

        internal fun networkTypeFor(allowMobileData: Boolean): NetworkType =
            if (allowMobileData) NetworkType.CONNECTED else NetworkType.UNMETERED

        internal fun mapManualWorkState(infos: List<WorkInfo>?): ManualBackupWorkState {
            if (infos.isNullOrEmpty()) return ManualBackupWorkState.IDLE
            return when {
                infos.any { it.state == WorkInfo.State.RUNNING } -> ManualBackupWorkState.RUNNING
                infos.any { it.state == WorkInfo.State.ENQUEUED || it.state == WorkInfo.State.BLOCKED } ->
                    ManualBackupWorkState.WAITING_FOR_NETWORK
                else -> ManualBackupWorkState.IDLE
            }
        }
    }
}
