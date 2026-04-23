package com.firestream.chat.ui.profile

import android.net.Uri
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.firestream.chat.domain.model.AppError
import com.firestream.chat.domain.model.Message
import com.firestream.chat.domain.model.User
import com.firestream.chat.domain.repository.AuthRepository
import com.firestream.chat.domain.repository.MessageRepository
import com.firestream.chat.domain.repository.UserRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.launch
import javax.inject.Inject

data class ProfileUiState(
    val user: User? = null,
    val isLoading: Boolean = true,
    val error: AppError? = null,
    val isCurrentUser: Boolean = false,
    val isBlocked: Boolean = false,
    val isBlockLoading: Boolean = false,
    val sharedMedia: List<Message> = emptyList(),
    val isUploading: Boolean = false,
    val uploadError: String? = null,
    val isSavingProfile: Boolean = false,
    val profileSaveError: String? = null
)

@HiltViewModel
class ProfileViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    private val userRepository: UserRepository,
    private val authRepository: AuthRepository,
    private val messageRepository: MessageRepository
) : ViewModel() {

    private val userId: String = checkNotNull(savedStateHandle["userId"])

    private val _uiState = MutableStateFlow(ProfileUiState())
    val uiState: StateFlow<ProfileUiState> = _uiState.asStateFlow()

    init {
        val currentUserId = authRepository.currentUserId
        _uiState.value = _uiState.value.copy(isCurrentUser = userId == currentUserId)
        loadUser()
        if (userId != currentUserId) {
            checkBlockStatus()
        }
        loadSharedMedia()
    }

    private fun loadUser() {
        viewModelScope.launch {
            userRepository.observeUser(userId)
                .catch { e -> _uiState.value = _uiState.value.copy(error = AppError.from(e), isLoading = false) }
                .collect { user ->
                    _uiState.value = _uiState.value.copy(user = user, isLoading = false)
                }
        }
    }

    private fun checkBlockStatus() {
        viewModelScope.launch {
            val blocked = userRepository.isUserBlocked(userId)
            _uiState.value = _uiState.value.copy(isBlocked = blocked)
        }
    }

    private fun loadSharedMedia() {
        viewModelScope.launch {
            messageRepository.getSharedMediaForUser(userId)
                .catch { /* ignore errors */ }
                .collect { media ->
                    _uiState.value = _uiState.value.copy(sharedMedia = media)
                }
        }
    }

    fun blockUser() {
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(isBlockLoading = true)
            userRepository.blockUser(userId)
                .onSuccess {
                    _uiState.value = _uiState.value.copy(isBlocked = true, isBlockLoading = false)
                }
                .onFailure { e ->
                    _uiState.value = _uiState.value.copy(
                        error = AppError.from(e),
                        isBlockLoading = false
                    )
                }
        }
    }

    fun uploadAvatar(uri: Uri) {
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(isUploading = true, uploadError = null)
            userRepository.uploadAvatar(uri.toString())
                .onSuccess { _uiState.value = _uiState.value.copy(isUploading = false) }
                .onFailure { e ->
                    _uiState.value = _uiState.value.copy(isUploading = false, uploadError = e.message)
                }
        }
    }

    fun updateDisplayName(displayName: String) {
        val trimmed = displayName.trim()
        if (trimmed == _uiState.value.user?.displayName) return
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(isSavingProfile = true, profileSaveError = null)
            userRepository.updateProfile(trimmed, null, null)
                .onSuccess { _uiState.value = _uiState.value.copy(isSavingProfile = false) }
                .onFailure { e ->
                    _uiState.value = _uiState.value.copy(
                        isSavingProfile = false,
                        profileSaveError = e.message ?: "Failed to save profile"
                    )
                }
        }
    }

    fun updateStatusText(statusText: String) {
        val trimmed = statusText.trim()
        if (trimmed == _uiState.value.user?.statusText) return
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(isSavingProfile = true, profileSaveError = null)
            userRepository.updateProfile(null, trimmed, null)
                .onSuccess { _uiState.value = _uiState.value.copy(isSavingProfile = false) }
                .onFailure { e ->
                    _uiState.value = _uiState.value.copy(
                        isSavingProfile = false,
                        profileSaveError = e.message ?: "Failed to save profile"
                    )
                }
        }
    }

    fun unblockUser() {
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(isBlockLoading = true)
            userRepository.unblockUser(userId)
                .onSuccess {
                    _uiState.value = _uiState.value.copy(isBlocked = false, isBlockLoading = false)
                }
                .onFailure { e ->
                    _uiState.value = _uiState.value.copy(
                        error = AppError.from(e),
                        isBlockLoading = false
                    )
                }
        }
    }
}
