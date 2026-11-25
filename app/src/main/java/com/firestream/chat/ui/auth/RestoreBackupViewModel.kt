package com.firestream.chat.ui.auth

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.firestream.chat.di.ApplicationScope
import com.firestream.chat.domain.model.AppError
import com.firestream.chat.domain.model.RemoteBackupSummary
import com.firestream.chat.domain.model.RestoreProgress
import com.firestream.chat.domain.model.RestoreStage
import com.firestream.chat.domain.model.WrongPassphraseException
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

data class RestoreBackupUiState(
    val isLoadingSummary: Boolean = true,
    val summary: RemoteBackupSummary? = null,
    val summaryError: AppError? = null,
    val progress: RestoreProgress = RestoreProgress(),
    val error: AppError? = null,
    val restoreSucceeded: Boolean = false,
) {
    val isRestoring: Boolean
        get() = progress.stage in ACTIVE_RESTORE_STAGES

    companion object {
        private val ACTIVE_RESTORE_STAGES = setOf(
            RestoreStage.FETCHING_MANIFEST,
            RestoreStage.VERIFYING_PASSPHRASE,
            RestoreStage.DOWNLOADING_DB,
            RestoreStage.DECRYPTING_DB,
            RestoreStage.VERIFYING_DB,
            RestoreStage.REPLACING_DB,
        )
    }
}

@HiltViewModel
class RestoreBackupViewModel @Inject constructor(
    private val backupRepository: BackupRepository,
    @ApplicationScope private val appScope: CoroutineScope,
) : ViewModel() {

    private val _uiState = MutableStateFlow(RestoreBackupUiState())
    val uiState: StateFlow<RestoreBackupUiState> = _uiState.asStateFlow()

    private var restoreJob: Job? = null

    init {
        viewModelScope.launch {
            backupRepository.restoreProgress.collect { progress ->
                _uiState.update { it.copy(progress = progress) }
            }
        }
        loadSummary()
    }

    fun loadSummary() {
        _uiState.update { it.copy(isLoadingSummary = true, summaryError = null) }
        viewModelScope.launch {
            backupRepository.fetchRemoteBackupSummary()
                .onSuccess { summary ->
                    _uiState.update { it.copy(isLoadingSummary = false, summary = summary) }
                }
                .onFailure { err ->
                    _uiState.update {
                        it.copy(
                            isLoadingSummary = false,
                            summaryError = AppError.from(err),
                        )
                    }
                }
        }
    }

    fun restore(passphrase: String) {
        if (_uiState.value.isRestoring || _uiState.value.restoreSucceeded || restoreJob?.isActive == true) return
        if (passphrase.isEmpty()) {
            _uiState.update { it.copy(error = AppError.Validation("Enter your backup passphrase.")) }
            return
        }
        _uiState.update { it.copy(error = null) }
        restoreJob = appScope.launch {
            backupRepository.restoreBackup(passphrase)
                .onSuccess {
                    _uiState.update { it.copy(restoreSucceeded = true, error = null) }
                }
                .onFailure { err ->
                    val appError = when (err) {
                        is WrongPassphraseException ->
                            AppError.Validation("Incorrect passphrase. Please try again.")
                        else -> AppError.from(err)
                    }
                    _uiState.update { it.copy(error = appError) }
                }
        }
    }

    fun markSkipped() {
        appScope.launch {
            backupRepository.markRestorePromptResolved()
        }
    }
}
