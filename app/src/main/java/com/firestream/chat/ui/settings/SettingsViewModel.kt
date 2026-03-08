package com.firestream.chat.ui.settings

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.firestream.chat.data.local.AppTheme
import com.firestream.chat.data.local.AutoDownloadOption
import com.firestream.chat.data.local.NotificationSound
import com.firestream.chat.data.local.PreferencesDataStore
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
    val error: String? = null,
    // Privacy
    val readReceipts: Boolean = true,
    val lastSeenVisible: Boolean = true,
    val screenSecurity: Boolean = false,
    // Notifications
    val messageNotifications: Boolean = true,
    val groupNotifications: Boolean = true,
    val notificationSound: NotificationSound = NotificationSound.DEFAULT,
    val vibration: Boolean = true,
    // Storage
    val cacheSize: Long = 0L,
    val autoDownload: AutoDownloadOption = AutoDownloadOption.WIFI_ONLY
)

@HiltViewModel
class SettingsViewModel @Inject constructor(
    private val authRepository: AuthRepository,
    private val userRepository: UserRepository,
    private val preferencesDataStore: PreferencesDataStore,
    @ApplicationContext private val appContext: Context
) : ViewModel() {

    private val _uiState = MutableStateFlow(SettingsUiState())
    val uiState: StateFlow<SettingsUiState> = _uiState.asStateFlow()

    init {
        loadCurrentUser()
        observePreferences()
        calculateCacheSize()
    }

    private fun loadCurrentUser() {
        val uid = authRepository.currentUserId ?: return
        viewModelScope.launch {
            userRepository.observeUser(uid)
                .catch { e -> _uiState.value = _uiState.value.copy(error = e.message, isLoading = false) }
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

    // Notifications
    fun setMessageNotifications(enabled: Boolean) {
        viewModelScope.launch { preferencesDataStore.setMessageNotifications(enabled) }
    }

    fun setGroupNotifications(enabled: Boolean) {
        viewModelScope.launch { preferencesDataStore.setGroupNotifications(enabled) }
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
