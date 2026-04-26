package com.firestream.chat.ui.settings

import android.content.Context
import androidx.lifecycle.Observer
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.work.Constraints
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.workDataOf
import com.firestream.chat.data.worker.MediaBackfillWorker
import com.firestream.chat.data.local.AppTheme
import com.firestream.chat.data.local.AutoDownloadOption
import com.firestream.chat.data.local.NotificationSound
import com.firestream.chat.data.local.PreferencesDataStore
import com.firestream.chat.domain.model.AppError
import com.firestream.chat.domain.model.User
import com.firestream.chat.domain.repository.AuthRepository
import com.firestream.chat.domain.repository.UserRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.launch
import java.io.File
import javax.inject.Inject

data class SettingsUiState(
    val currentUser: User? = null,
    val appTheme: AppTheme = AppTheme.SYSTEM,
    val isLoading: Boolean = true,
    val error: AppError? = null,
    // Privacy
    val readReceipts: Boolean = true,
    val lastSeenVisible: Boolean = true,
    val screenSecurity: Boolean = false,
    val e2eEncryption: Boolean = true,
    // Notifications
    val messageNotifications: Boolean = true,
    val groupNotifications: Boolean = true,
    val mentionOnlyNotifications: Boolean = false,
    val notificationSound: NotificationSound = NotificationSound.DEFAULT,
    val vibration: Boolean = true,
    // Storage
    val cacheSize: Long = 0L,
    val autoDownload: AutoDownloadOption = AutoDownloadOption.WIFI_ONLY,
    val sendImagesFullQuality: Boolean = false,
    // Media backfill
    val mediaBackfillProgress: Pair<Int, Int>? = null,
    val mediaBackfillRunning: Boolean = false
)

private const val WORK_MEDIA_BACKFILL = "media_backfill"

@HiltViewModel
class SettingsViewModel @Inject constructor(
    private val authRepository: AuthRepository,
    private val userRepository: UserRepository,
    private val preferencesDataStore: PreferencesDataStore,
    @ApplicationContext private val appContext: Context
) : ViewModel() {

    private val _uiState = MutableStateFlow(SettingsUiState())
    val uiState: StateFlow<SettingsUiState> = _uiState.asStateFlow()

    private var backfillObserver: Observer<List<WorkInfo>>? = null
    private var backfillLiveData: androidx.lifecycle.LiveData<List<WorkInfo>>? = null

    init {
        loadCurrentUser()
        observePreferences()
        calculateCacheSize()
        observeBackfillProgress()
    }

    private fun loadCurrentUser() {
        val uid = authRepository.currentUserId ?: return
        viewModelScope.launch {
            userRepository.observeUser(uid)
                .catch { e -> _uiState.value = _uiState.value.copy(error = AppError.from(e), isLoading = false) }
                .collect { user ->
                    _uiState.value = _uiState.value.copy(currentUser = user, isLoading = false)
                }
        }
    }

    private fun observePreferences() {
        viewModelScope.launch {
            preferencesDataStore.appThemeFlow.collect { theme ->
                _uiState.value = _uiState.value.copy(appTheme = theme)
            }
        }
        viewModelScope.launch {
            preferencesDataStore.readReceiptsFlow.collect { enabled ->
                _uiState.value = _uiState.value.copy(readReceipts = enabled)
            }
        }
        viewModelScope.launch {
            preferencesDataStore.lastSeenVisibleFlow.collect { visible ->
                _uiState.value = _uiState.value.copy(lastSeenVisible = visible)
            }
        }
        viewModelScope.launch {
            preferencesDataStore.screenSecurityFlow.collect { enabled ->
                _uiState.value = _uiState.value.copy(screenSecurity = enabled)
            }
        }
        viewModelScope.launch {
            preferencesDataStore.e2eEncryptionEnabledFlow.collect { enabled ->
                _uiState.value = _uiState.value.copy(e2eEncryption = enabled)
            }
        }
        viewModelScope.launch {
            preferencesDataStore.messageNotificationsFlow.collect { enabled ->
                _uiState.value = _uiState.value.copy(messageNotifications = enabled)
            }
        }
        viewModelScope.launch {
            preferencesDataStore.groupNotificationsFlow.collect { enabled ->
                _uiState.value = _uiState.value.copy(groupNotifications = enabled)
            }
        }
        viewModelScope.launch {
            preferencesDataStore.mentionOnlyNotificationsFlow.collect { enabled ->
                _uiState.value = _uiState.value.copy(mentionOnlyNotifications = enabled)
            }
        }
        viewModelScope.launch {
            preferencesDataStore.notificationSoundFlow.collect { sound ->
                _uiState.value = _uiState.value.copy(notificationSound = sound)
            }
        }
        viewModelScope.launch {
            preferencesDataStore.vibrationFlow.collect { enabled ->
                _uiState.value = _uiState.value.copy(vibration = enabled)
            }
        }
        viewModelScope.launch {
            preferencesDataStore.autoDownloadFlow.collect { option ->
                _uiState.value = _uiState.value.copy(autoDownload = option)
            }
        }
        viewModelScope.launch {
            preferencesDataStore.sendImagesFullQualityFlow.collect { enabled ->
                _uiState.value = _uiState.value.copy(sendImagesFullQuality = enabled)
            }
        }
    }

    fun setTheme(theme: AppTheme) {
        viewModelScope.launch { preferencesDataStore.setAppTheme(theme) }
    }

    // Privacy
    fun setReadReceipts(enabled: Boolean) {
        viewModelScope.launch {
            preferencesDataStore.setReadReceipts(enabled)
            userRepository.updateReadReceipts(enabled)
        }
    }

    fun setLastSeenVisible(visible: Boolean) {
        viewModelScope.launch { preferencesDataStore.setLastSeenVisible(visible) }
    }

    fun setScreenSecurity(enabled: Boolean) {
        viewModelScope.launch { preferencesDataStore.setScreenSecurity(enabled) }
    }

    fun setE2eEncryption(enabled: Boolean) {
        viewModelScope.launch { preferencesDataStore.setE2eEncryptionEnabled(enabled) }
    }

    // Notifications
    fun setMessageNotifications(enabled: Boolean) {
        viewModelScope.launch { preferencesDataStore.setMessageNotifications(enabled) }
    }

    fun setGroupNotifications(enabled: Boolean) {
        viewModelScope.launch { preferencesDataStore.setGroupNotifications(enabled) }
    }

    fun setMentionOnlyNotifications(enabled: Boolean) {
        viewModelScope.launch { preferencesDataStore.setMentionOnlyNotifications(enabled) }
    }

    fun setNotificationSound(sound: NotificationSound) {
        viewModelScope.launch { preferencesDataStore.setNotificationSound(sound) }
    }

    fun setVibration(enabled: Boolean) {
        viewModelScope.launch { preferencesDataStore.setVibration(enabled) }
    }

    // Storage
    fun setAutoDownload(option: AutoDownloadOption) {
        viewModelScope.launch { preferencesDataStore.setAutoDownload(option) }
    }

    fun setSendImagesFullQuality(enabled: Boolean) {
        viewModelScope.launch { preferencesDataStore.setSendImagesFullQuality(enabled) }
    }

    fun startMediaBackfill() {
        val request = OneTimeWorkRequestBuilder<MediaBackfillWorker>()
            .setInputData(workDataOf("manual" to true))
            .setConstraints(
                Constraints.Builder()
                    .setRequiredNetworkType(NetworkType.CONNECTED)
                    .build()
            )
            .build()
        WorkManager.getInstance(appContext)
            .enqueueUniqueWork(WORK_MEDIA_BACKFILL, ExistingWorkPolicy.REPLACE, request)
    }

    fun clearCache() {
        viewModelScope.launch {
            try {
                appContext.cacheDir.deleteRecursively()
                appContext.cacheDir.mkdirs()
                calculateCacheSize()
            } catch (_: Exception) { }
        }
    }

    private fun calculateCacheSize() {
        viewModelScope.launch {
            val size = getDirSize(appContext.cacheDir)
            _uiState.value = _uiState.value.copy(cacheSize = size)
        }
    }

    private fun observeBackfillProgress() {
        try {
            val liveData = WorkManager.getInstance(appContext)
                .getWorkInfosForUniqueWorkLiveData(WORK_MEDIA_BACKFILL)
            val observer = Observer<List<WorkInfo>> { workInfos ->
                val workInfo = workInfos?.firstOrNull()
                if (workInfo == null) {
                    _uiState.value = _uiState.value.copy(
                        mediaBackfillRunning = false,
                        mediaBackfillProgress = null
                    )
                    return@Observer
                }
                when (workInfo.state) {
                    WorkInfo.State.ENQUEUED -> {
                        _uiState.value = _uiState.value.copy(
                            mediaBackfillRunning = true,
                            mediaBackfillProgress = null
                        )
                    }
                    WorkInfo.State.RUNNING -> {
                        val done = workInfo.progress.getInt("done", 0)
                        val total = workInfo.progress.getInt("total", 0)
                        _uiState.value = _uiState.value.copy(
                            mediaBackfillRunning = true,
                            mediaBackfillProgress = if (total > 0) Pair(done, total) else null
                        )
                    }
                    else -> {
                        _uiState.value = _uiState.value.copy(
                            mediaBackfillRunning = false,
                            mediaBackfillProgress = null
                        )
                    }
                }
            }
            backfillObserver = observer
            backfillLiveData = liveData
            liveData.observeForever(observer)
        } catch (_: IllegalStateException) {
            // WorkManager not initialized (e.g., in unit tests)
        }
    }

    override fun onCleared() {
        super.onCleared()
        backfillObserver?.let { observer ->
            try {
                backfillLiveData?.removeObserver(observer)
            } catch (_: IllegalStateException) { }
        }
    }

    private fun getDirSize(dir: File): Long {
        var size = 0L
        dir.listFiles()?.forEach { file ->
            size += if (file.isDirectory) getDirSize(file) else file.length()
        }
        return size
    }

    fun signOut() {
        viewModelScope.launch {
            authRepository.signOut()
        }
    }
}
