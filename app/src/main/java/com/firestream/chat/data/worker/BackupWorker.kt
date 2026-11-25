// region: AGENT-NOTE
// Responsibility: Periodic background encrypted backup worker scheduled by
//   BackupScheduler when the user opts into Daily or Weekly automatic backups
//   (constrained to Wi-Fi + charging). Uses the Keystore-wrapped backup master
//   key already stored by BackupKeyStore so no passphrase prompt is needed.
// Collaborators: BackupRepository (BackupManager), BackupScheduler.
// endregion

package com.firestream.chat.data.worker

import android.content.Context
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.firestream.chat.domain.repository.BackupRepository
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject

@HiltWorker
class BackupWorker @AssistedInject constructor(
    @Assisted context: Context,
    @Assisted workerParams: WorkerParameters,
    private val backupRepository: BackupRepository,
) : CoroutineWorker(context, workerParams) {

    override suspend fun doWork(): Result {
        if (!backupRepository.isSupported) return Result.success()
        if (!backupRepository.hasConfiguredPassphrase()) return Result.success()

        return backupRepository.runBackup().fold(
            onSuccess = { Result.success() },
            onFailure = {
                if (runAttemptCount < MAX_RETRY_ATTEMPTS) Result.retry() else Result.failure()
            },
        )
    }

    companion object {
        const val UNIQUE_NAME = "encrypted_chat_backup"
        private const val MAX_RETRY_ATTEMPTS = 3
    }
}
