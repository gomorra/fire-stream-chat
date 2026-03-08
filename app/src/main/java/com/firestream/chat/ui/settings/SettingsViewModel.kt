package com.firestream.chat.ui.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.firestream.chat.data.local.AppTheme
import com.firestream.chat.data.local.PreferencesDataStore
import com.firestream.chat.domain.model.User
import com.firestream.chat.domain.repository.AuthRepository
import com.firestream.chat.domain.repository.UserRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.launch
import javax.inject.Inject

data class SettingsUiState(
    val currentUser: User? = null,
    val appTheme: AppTheme = AppTheme.SYSTEM,
    val isLoading: Boolean = true,
    val error: String? = null
)

@HiltViewModel
class SettingsViewModel @Inject constructor(
    private val authRepository: AuthRepository,
    private val userRepository: UserRepository,
    private val preferencesDataStore: PreferencesDataStore
) : ViewModel() {

    private val _uiState = MutableStateFlow(SettingsUiState())
    val uiState: StateFlow<SettingsUiState> = _uiState.asStateFlow()

    init {
        loadCurrentUser()
        observeTheme()
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

    private fun observeTheme() {
        viewModelScope.launch {
            preferencesDataStore.appThemeFlow.collect { theme ->
                _uiState.value = _uiState.value.copy(appTheme = theme)
            }
        }
    }

    fun setTheme(theme: AppTheme) {
        viewModelScope.launch {
            preferencesDataStore.setAppTheme(theme)
        }
    }

    fun signOut() {
        authRepository.signOut()
    }
}
