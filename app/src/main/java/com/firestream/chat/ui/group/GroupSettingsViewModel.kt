package com.firestream.chat.ui.group

import android.net.Uri
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.firestream.chat.domain.model.AppError
import com.firestream.chat.domain.model.Chat
import com.firestream.chat.domain.model.GroupRole
import com.firestream.chat.domain.model.User
import com.firestream.chat.domain.repository.AuthRepository
import com.firestream.chat.domain.repository.ChatRepository
import com.firestream.chat.domain.repository.UserRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.launch
import javax.inject.Inject

data class GroupSettingsUiState(
    val chat: Chat? = null,
    val members: List<MemberInfo> = emptyList(),
    val pendingMembers: List<MemberInfo> = emptyList(),
    val currentUserRole: GroupRole = GroupRole.MEMBER,
    val isLoading: Boolean = true,
    val error: AppError? = null,
    val inviteLinkGenerated: Boolean = false,
    val leftGroup: Boolean = false,
    val isUploading: Boolean = false,
    val uploadError: String? = null,
    val isSavingName: Boolean = false,
    val isSavingDescription: Boolean = false
)

data class MemberInfo(
    val userId: String,
    val displayName: String,
    val avatarUrl: String? = null,
    val role: GroupRole
)

@HiltViewModel
class GroupSettingsViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    private val chatRepository: ChatRepository,
    private val userRepository: UserRepository,
    private val authRepository: AuthRepository
) : ViewModel() {

    val chatId: String = checkNotNull(savedStateHandle["chatId"])
    private val currentUserId = authRepository.currentUserId ?: ""

    private val _uiState = MutableStateFlow(GroupSettingsUiState())
    val uiState: StateFlow<GroupSettingsUiState> = _uiState.asStateFlow()

    init {
        loadGroupDetails()
    }

    private fun loadGroupDetails() {
        viewModelScope.launch {
            chatRepository.getChatById(chatId)
                .onSuccess { chat ->
                    val role = when {
                        chat.createdBy == currentUserId -> GroupRole.OWNER
                        currentUserId in chat.admins -> GroupRole.ADMIN
                        else -> GroupRole.MEMBER
                    }
                    _uiState.value = _uiState.value.copy(
                        chat = chat,
                        currentUserRole = role,
                        isLoading = false
                    )
                    loadMembers(chat)
                    loadPendingMembers(chat)
                }
                .onFailure { e ->
                    _uiState.value = _uiState.value.copy(
                        isLoading = false,
                        error = AppError.from(e)
                    )
                }
        }
    }

    private fun loadMembers(chat: Chat) {
        viewModelScope.launch {
            val members = chat.participants.map { userId ->
                async {
                    val user = userRepository.getUserById(userId).getOrNull()
                    val role = when {
                        chat.createdBy == userId -> GroupRole.OWNER
                        userId in chat.admins -> GroupRole.ADMIN
                        else -> GroupRole.MEMBER
                    }
                    MemberInfo(
                        userId = userId,
                        displayName = user?.displayName ?: userId,
                        avatarUrl = user?.avatarUrl,
                        role = role
                    )
                }
            }.awaitAll().sortedBy { it.role.ordinal }
            _uiState.value = _uiState.value.copy(members = members)
        }
    }

    private fun loadPendingMembers(chat: Chat) {
        viewModelScope.launch {
            val pending = chat.pendingMembers.map { userId ->
                async {
                    val user = userRepository.getUserById(userId).getOrNull()
                    MemberInfo(
                        userId = userId,
                        displayName = user?.displayName ?: userId,
                        avatarUrl = user?.avatarUrl,
                        role = GroupRole.MEMBER
                    )
                }
            }.awaitAll()
            _uiState.value = _uiState.value.copy(pendingMembers = pending)
        }
    }

    fun uploadGroupAvatar(uri: Uri) {
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(isUploading = true, uploadError = null)
            chatRepository.uploadGroupAvatar(chatId, uri.toString())
                .onSuccess { url ->
                    _uiState.value = _uiState.value.copy(
                        isUploading = false,
                        chat = _uiState.value.chat?.copy(avatarUrl = url)
                    )
                }
                .onFailure { e ->
                    _uiState.value = _uiState.value.copy(isUploading = false, uploadError = e.message)
                }
        }
    }

    fun updateGroupName(name: String) {
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(isSavingName = true)
            chatRepository.updateGroup(chatId, name = name, avatarUrl = null)
                .onSuccess {
                    _uiState.value = _uiState.value.copy(
                        isSavingName = false,
                        chat = _uiState.value.chat?.copy(name = name)
                    )
                }
                .onFailure { e ->
                    _uiState.value = _uiState.value.copy(isSavingName = false, error = AppError.from(e))
                }
        }
    }

    fun updateDescription(description: String) {
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(isSavingDescription = true)
            chatRepository.updateGroupDescription(chatId, description)
                .onSuccess {
                    _uiState.value = _uiState.value.copy(
                        isSavingDescription = false,
                        chat = _uiState.value.chat?.copy(description = description)
                    )
                }
                .onFailure { e ->
                    _uiState.value = _uiState.value.copy(isSavingDescription = false, error = AppError.from(e))
                }
        }
    }

    fun generateInviteLink() {
        viewModelScope.launch {
            chatRepository.generateInviteLink(chatId)
                .onSuccess { token ->
                    _uiState.value = _uiState.value.copy(
                        chat = _uiState.value.chat?.copy(inviteLink = token),
                        inviteLinkGenerated = true
                    )
                }
                .onFailure { e -> _uiState.value = _uiState.value.copy(error = AppError.from(e)) }
        }
    }

    fun revokeInviteLink() {
        viewModelScope.launch {
            chatRepository.revokeInviteLink(chatId)
                .onSuccess {
                    _uiState.value = _uiState.value.copy(
                        chat = _uiState.value.chat?.copy(inviteLink = null),
                        inviteLinkGenerated = false
                    )
                }
                .onFailure { e -> _uiState.value = _uiState.value.copy(error = AppError.from(e)) }
        }
    }

    fun setRequireApproval(enabled: Boolean) {
        viewModelScope.launch {
            chatRepository.setRequireApproval(chatId, enabled)
                .onSuccess {
                    _uiState.value = _uiState.value.copy(
                        chat = _uiState.value.chat?.copy(requireApproval = enabled)
                    )
                }
                .onFailure { e -> _uiState.value = _uiState.value.copy(error = AppError.from(e)) }
        }
    }

    fun approveMember(userId: String) {
        viewModelScope.launch {
            chatRepository.approveMember(chatId, userId)
                .onSuccess { loadGroupDetails() }
                .onFailure { e -> _uiState.value = _uiState.value.copy(error = AppError.from(e)) }
        }
    }

    fun rejectMember(userId: String) {
        viewModelScope.launch {
            chatRepository.rejectMember(chatId, userId)
                .onSuccess { loadGroupDetails() }
                .onFailure { e -> _uiState.value = _uiState.value.copy(error = AppError.from(e)) }
        }
    }

    fun removeGroupMember(userId: String) {
        viewModelScope.launch {
            chatRepository.removeGroupMember(chatId, userId)
                .onSuccess { loadGroupDetails() }
                .onFailure { e -> _uiState.value = _uiState.value.copy(error = AppError.from(e)) }
        }
    }

    fun leaveGroup() {
        viewModelScope.launch {
            chatRepository.leaveGroup(chatId)
                .onSuccess {
                    _uiState.value = _uiState.value.copy(leftGroup = true)
                }
                .onFailure { e -> _uiState.value = _uiState.value.copy(error = AppError.from(e)) }
        }
    }

    fun clearError() {
        _uiState.value = _uiState.value.copy(error = null)
    }
}
