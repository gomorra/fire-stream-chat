package com.firestream.chat.domain.model

const val MIN_BACKUP_PASSPHRASE_LENGTH = 12

enum class BackupFrequency { OFF, DAILY, WEEKLY }

enum class ManualBackupWorkState {
    IDLE,
    WAITING_FOR_NETWORK,
    RUNNING,
}

enum class BackupStage {
    IDLE,
    SYNCING_CHATS,
    DOWNLOADING_LEGACY_MEDIA,
    SNAPSHOTTING_DB,
    ENCRYPTING_DB,
    UPLOADING_DB,
    UPLOADING_LEGACY_MEDIA,
    FINALIZING,
    COMPLETED,
    FAILED,
}

enum class RestoreStage {
    IDLE,
    FETCHING_MANIFEST,
    VERIFYING_PASSPHRASE,
    DOWNLOADING_DB,
    DECRYPTING_DB,
    VERIFYING_DB,
    REPLACING_DB,
    COMPLETED,
    FAILED,
}

data class BackupProgress(
    val stage: BackupStage = BackupStage.IDLE,
    val completedItems: Int = 0,
    val totalItems: Int = 0,
    val fraction: Float = 0f,
    val errorMessage: String? = null,
    val legacyMediaComplete: Boolean? = null,
)

data class RestoreProgress(
    val stage: RestoreStage = RestoreStage.IDLE,
    val fraction: Float = 0f,
    val errorMessage: String? = null,
)

data class RemoteBackupSummary(
    val createdAt: Long,
    val dbCipherSizeBytes: Long,
    val legacyMediaComplete: Boolean,
)

data class LocalBackupStatus(
    val isSupported: Boolean = false,
    val isPassphraseConfigured: Boolean = false,
    val lastBackupAt: Long? = null,
    val lastBackupLegacyMediaComplete: Boolean = false,
    val lastBackupSizeBytes: Long? = null,
    val frequency: BackupFrequency = BackupFrequency.OFF,
    val allowMobileData: Boolean = false,
)

class WrongPassphraseException(
    message: String = "Incorrect backup passphrase",
    cause: Throwable? = null,
) : Exception(message, cause)

class UnsupportedBackupVersionException(
    message: String,
) : Exception(message)
