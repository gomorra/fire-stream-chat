package com.firestream.chat.domain.repository

import com.firestream.chat.domain.model.BackupFrequency
import com.firestream.chat.domain.model.BackupProgress
import com.firestream.chat.domain.model.LocalBackupStatus
import com.firestream.chat.domain.model.RemoteBackupSummary
import com.firestream.chat.domain.model.RestoreProgress
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow

interface BackupRepository {
    val isSupported: Boolean
    val backupProgress: StateFlow<BackupProgress>
    val restoreProgress: StateFlow<RestoreProgress>
    val localStatus: Flow<LocalBackupStatus>

    suspend fun configurePassphrase(passphrase: String): Result<Unit>
    suspend fun hasConfiguredPassphrase(): Boolean
    suspend fun runBackup(): Result<RemoteBackupSummary>
    suspend fun fetchRemoteBackupSummary(): Result<RemoteBackupSummary?>
    suspend fun hasLocalMessages(): Boolean

    /**
     * Downloads, verifies, and atomically swaps the restored `fire_stream_chat.db`
     * into place. Because this closes the `@Singleton` Room database, the caller
     * **must** trigger a clean process restart immediately upon success so Room
     * reopens the restored file and applies any forward migrations.
     */
    suspend fun restoreBackup(passphrase: String): Result<Unit>
    suspend fun setBackupFrequency(frequency: BackupFrequency)
    fun resetProgress()
}
