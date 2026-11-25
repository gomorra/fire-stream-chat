package com.firestream.chat.ui.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.firestream.chat.di.ApplicationScope
import com.firestream.chat.domain.model.AppError
import com.firestream.chat.domain.model.BackupFrequency
import com.firestream.chat.domain.model.BackupProgress
import com.firestream.chat.domain.model.BackupStage
import com.firestream.chat.domain.model.LocalBackupStatus
import com.firestream.chat.domain.model.MIN_BACKUP_PASSPHRASE_LENGTH
import com.firestream.chat.domain.repository.BackupRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

data class BackupUiState(
    val status: LocalBackupStatus = LocalBackupStatus(),
    val progress: BackupProgress = BackupProgress(),
    val showPassphraseDialog: Boolean = false,
    val passphraseError: AppError? = null,
    val isSavingPassphrase: Boolean = false,
    val snackbarMessage: String? = null,
) {
    val isBackupRunning: Boolean
        get() = progress.stage in ACTIVE_BACKUP_STAGES

    companion object {
        private val ACTIVE_BACKUP_STAGES = setOf(
            BackupStage.SYNCING_CHATS,
            BackupStage.DOWNLOADING_LEGACY_MEDIA,
            BackupStage.SNAPSHOTTING_DB,
            BackupStage.ENCRYPTING_DB,
            BackupStage.UPLOADING_DB,
            BackupStage.UPLOADING_LEGACY_MEDIA,
            BackupStage.FINALIZING,
        )
    }
}

@HiltViewModel
class BackupViewModel @Inject constructor(
    private val backupRepository: BackupRepository,
    @ApplicationScope private val appScope: CoroutineScope,
) : ViewModel() {

    private val _uiState = MutableStateFlow(
        BackupUiState(status = LocalBackupStatus(isSupported = backupRepository.isSupported))
    )
    val uiState: StateFlow<BackupUiState> = _uiState.asStateFlow()

    private var backupJob: Job? = null
    private var pendingBackupAfterPassphrase: Boolean = false

    init {
        viewModelScope.launch {
            backupRepository.localStatus.collect { status ->
                _uiState.update { it.copy(status = status) }
            }
        }
        viewModelScope.launch {
            backupRepository.backupProgress.collect { progress ->
                _uiState.update { it.copy(progress = progress) }
            }
        }
    }

    fun openPassphraseDialog() {
        pendingBackupAfterPassphrase = false
        _uiState.update { it.copy(showPassphraseDialog = true, passphraseError = null) }
    }

    fun dismissPassphraseDialog() {
        if (_uiState.value.isSavingPassphrase) return
        pendingBackupAfterPassphrase = false
        _uiState.update { it.copy(showPassphraseDialog = false, passphraseError = null) }
    }

    fun savePassphrase(passphrase: String, confirmation: String) {
        if (_uiState.value.isSavingPassphrase) return
        if (passphrase.length < MIN_BACKUP_PASSPHRASE_LENGTH) {
            _uiState.update {
                it.copy(
                    passphraseError = AppError.Validation(
                        "Passphrase must be at least $MIN_BACKUP_PASSPHRASE_LENGTH characters."
                    )
                )
            }
            return
        }
        if (passphrase != confirmation) {
            _uiState.update {
                it.copy(passphraseError = AppError.Validation("Passphrases do not match."))
            }
            return
        }
        val wasAlreadyBackedUp = _uiState.value.status.lastBackupAt != null
        val shouldStartBackup = wasAlreadyBackedUp || pendingBackupAfterPassphrase
        pendingBackupAfterPassphrase = false
        _uiState.update { it.copy(isSavingPassphrase = true, passphraseError = null) }
        appScope.launch {
            backupRepository.configurePassphrase(passphrase)
                .onSuccess {
                    _uiState.update {
                        it.copy(
                            isSavingPassphrase = false,
                            showPassphraseDialog = false,
                            passphraseError = null,
                            snackbarMessage = when {
                                wasAlreadyBackedUp -> "Updating backup with new passphrase…"
                                shouldStartBackup -> null
                                else -> "Backup passphrase saved."
                            },
                        )
                    }
                    if (shouldStartBackup) {
                        launchBackup()
                    }
                }
                .onFailure { err ->
                    _uiState.update {
                        it.copy(
                            isSavingPassphrase = false,
                            passphraseError = AppError.from(err),
                        )
                    }
                }
        }
    }

    fun backupNow() {
        val state = _uiState.value
        if (state.isBackupRunning || backupJob?.isActive == true) return
        if (!state.status.isPassphraseConfigured) {
            pendingBackupAfterPassphrase = true
            _uiState.update { it.copy(showPassphraseDialog = true, passphraseError = null) }
            return
        }
        launchBackup()
    }

    private fun launchBackup() {
        if (backupJob?.isActive == true) return
        backupJob = appScope.launch {
            backupRepository.runBackup()
                .onSuccess { summary ->
                    val message = if (summary.legacyMediaComplete) {
                        "Encrypted backup complete."
                    } else {
                        "Backup saved, but some legacy media could not be downloaded."
                    }
                    _uiState.update { it.copy(snackbarMessage = message) }
                }
            // Failures are surfaced via BackupProgress(stage = FAILED) error card,
            // avoiding duplicate error banners + snackbars.
        }
    }

    fun setFrequency(frequency: BackupFrequency) {
        appScope.launch {
            backupRepository.setBackupFrequency(frequency)
        }
    }

    fun consumeSnackbar() {
        _uiState.update { it.copy(snackbarMessage = null) }
    }
}
