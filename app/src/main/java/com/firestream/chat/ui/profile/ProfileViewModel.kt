package com.firestream.chat.ui.profile

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
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
    val error: String? = null,
    val isCurrentUser: Boolean = false,
    val isBlocked: Boolean = false,
    val isBlockLoading: Boolean = false,
    val sharedMedia: List<Message> = emptyList()
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
                .catch { e -> _uiState.value = _uiState.value.copy(error = e.message, isLoading = false) }
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
                        error = e.message ?: "Failed to block user",
                        isBlockLoading = false
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
                        error = e.message ?: "Failed to unblock user",
                        isBlockLoading = false
                    )
                }
        }
    }
}
