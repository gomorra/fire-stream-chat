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
import com.firestream.chat.domain.model.ManualBackupWorkState
import com.firestream.chat.domain.repository.BackupRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

data class BackupUiState(
    val status: LocalBackupStatus = LocalBackupStatus(),
    val progress: BackupProgress = BackupProgress(),
    val manualWorkState: ManualBackupWorkState = ManualBackupWorkState.IDLE,
    val showPassphraseDialog: Boolean = false,
    val passphraseError: AppError? = null,
    val isSavingPassphrase: Boolean = false,
    val snackbarMessage: String? = null,
) {
    val isBackupRunning: Boolean
        get() = progress.stage in ACTIVE_BACKUP_STAGES

    val isWaitingForNetwork: Boolean
        get() = !isBackupRunning && manualWorkState == ManualBackupWorkState.WAITING_FOR_NETWORK

    val isBackupActive: Boolean
        get() = isBackupRunning || manualWorkState != ManualBackupWorkState.IDLE

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

    private var pendingBackupAfterPassphrase: Boolean = false
    private var observedActiveBackup: Boolean = false

    init {
        viewModelScope.launch {
            backupRepository.localStatus.collect { status ->
                _uiState.update { it.copy(status = status) }
            }
        }
        viewModelScope.launch {
            backupRepository.manualBackupWorkState.collect { workState ->
                if (workState != ManualBackupWorkState.IDLE) {
                    observedActiveBackup = true
                }
                _uiState.update { it.copy(manualWorkState = workState) }
            }
        }
        viewModelScope.launch {
            backupRepository.backupProgress.collect { progress ->
                val active = progress.stage in setOf(
                    BackupStage.SYNCING_CHATS,
                    BackupStage.DOWNLOADING_LEGACY_MEDIA,
                    BackupStage.SNAPSHOTTING_DB,
                    BackupStage.ENCRYPTING_DB,
                    BackupStage.UPLOADING_DB,
                    BackupStage.UPLOADING_LEGACY_MEDIA,
                    BackupStage.FINALIZING,
                )
                if (active) {
                    observedActiveBackup = true
                }
                val completionSnackbar = if (progress.stage == BackupStage.COMPLETED && observedActiveBackup) {
                    observedActiveBackup = false
                    val complete = progress.legacyMediaComplete
                        ?: _uiState.value.status.lastBackupLegacyMediaComplete
                    if (complete) {
                        "Encrypted backup complete."
                    } else {
                        "Backup saved, but some legacy media could not be backed up. Tap Back up now to retry."
                    }
                } else {
                    if (progress.stage == BackupStage.FAILED) {
                        observedActiveBackup = false
                    }
                    null
                }
                _uiState.update { current ->
                    current.copy(
                        progress = progress,
                        snackbarMessage = completionSnackbar ?: current.snackbarMessage,
                    )
                }
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

    fun savePassphrase(
        passphrase: String,
        confirmation: String,
        initialFrequency: BackupFrequency? = null,
    ) {
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
        val wasAlreadyConfigured = _uiState.value.status.isPassphraseConfigured
        val wasAlreadyBackedUp = _uiState.value.status.lastBackupAt != null
        val shouldStartBackup = wasAlreadyBackedUp || pendingBackupAfterPassphrase
        pendingBackupAfterPassphrase = false
        _uiState.update { it.copy(isSavingPassphrase = true, passphraseError = null) }
        appScope.launch {
            backupRepository.configurePassphrase(passphrase)
                .onSuccess {
                    if (!wasAlreadyConfigured && initialFrequency != null) {
                        backupRepository.setBackupFrequency(initialFrequency)
                    }
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
        if (state.isBackupActive) return
        if (!state.status.isPassphraseConfigured) {
            pendingBackupAfterPassphrase = true
            _uiState.update { it.copy(showPassphraseDialog = true, passphraseError = null) }
            return
        }
        launchBackup()
    }

    private fun launchBackup() {
        observedActiveBackup = true
        appScope.launch {
            backupRepository.enqueueManualBackup()
        }
    }

    fun cancelBackup() {
        observedActiveBackup = false
        appScope.launch {
            backupRepository.cancelBackup()
        }
        _uiState.update { it.copy(snackbarMessage = "Backup canceled.") }
    }

    fun setFrequency(frequency: BackupFrequency) {
        appScope.launch {
            backupRepository.setBackupFrequency(frequency)
        }
    }

    fun setAllowMobileData(allowed: Boolean) {
        appScope.launch {
            backupRepository.setBackupOverMobileData(allowed)
        }
    }

    fun consumeSnackbar() {
        _uiState.update { it.copy(snackbarMessage = null) }
    }
}
