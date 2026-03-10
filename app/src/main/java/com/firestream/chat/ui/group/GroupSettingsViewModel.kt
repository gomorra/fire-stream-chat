package com.firestream.chat.ui.group

import android.net.Uri
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.firestream.chat.domain.model.Chat
import com.firestream.chat.domain.model.GroupRole
import com.firestream.chat.domain.model.User
import com.firestream.chat.domain.repository.AuthRepository
import com.firestream.chat.domain.repository.ChatRepository
import com.firestream.chat.domain.repository.UserRepository
import com.firestream.chat.domain.usecase.chat.ApproveMemberUseCase
import com.firestream.chat.domain.usecase.chat.GenerateInviteLinkUseCase
import com.firestream.chat.domain.usecase.chat.LeaveGroupUseCase
import com.firestream.chat.domain.usecase.chat.RejectMemberUseCase
import com.firestream.chat.domain.usecase.chat.RevokeInviteLinkUseCase
import com.firestream.chat.domain.usecase.chat.SetRequireApprovalUseCase
import com.firestream.chat.domain.usecase.chat.UpdateGroupDescriptionUseCase
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
    val error: String? = null,
    val inviteLinkGenerated: Boolean = false,
    val leftGroup: Boolean = false,
    val isUploading: Boolean = false,
    val uploadError: String? = null
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
    private val authRepository: AuthRepository,
    private val updateGroupDescriptionUseCase: UpdateGroupDescriptionUseCase,
    private val generateInviteLinkUseCase: GenerateInviteLinkUseCase,
    private val revokeInviteLinkUseCase: RevokeInviteLinkUseCase,
    private val setRequireApprovalUseCase: SetRequireApprovalUseCase,
    private val approveMemberUseCase: ApproveMemberUseCase,
    private val rejectMemberUseCase: RejectMemberUseCase,
    private val leaveGroupUseCase: LeaveGroupUseCase
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
                        error = e.message
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
            chatRepository.uploadGroupAvatar(chatId, uri)
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

    fun updateDescription(description: String) {
        viewModelScope.launch {
            updateGroupDescriptionUseCase(chatId, description)
                .onSuccess {
                    _uiState.value = _uiState.value.copy(
                        chat = _uiState.value.chat?.copy(description = description)
                    )
                }
                .onFailure { e -> _uiState.value = _uiState.value.copy(error = e.message) }
        }
    }

    fun generateInviteLink() {
        viewModelScope.launch {
            generateInviteLinkUseCase(chatId)
                .onSuccess { token ->
                    _uiState.value = _uiState.value.copy(
                        chat = _uiState.value.chat?.copy(inviteLink = token),
                        inviteLinkGenerated = true
                    )
                }
                .onFailure { e -> _uiState.value = _uiState.value.copy(error = e.message) }
        }
    }

    fun revokeInviteLink() {
        viewModelScope.launch {
            revokeInviteLinkUseCase(chatId)
                .onSuccess {
                    _uiState.value = _uiState.value.copy(
                        chat = _uiState.value.chat?.copy(inviteLink = null),
                        inviteLinkGenerated = false
                    )
                }
                .onFailure { e -> _uiState.value = _uiState.value.copy(error = e.message) }
        }
    }

    fun setRequireApproval(enabled: Boolean) {
        viewModelScope.launch {
            setRequireApprovalUseCase(chatId, enabled)
                .onSuccess {
                    _uiState.value = _uiState.value.copy(
                        chat = _uiState.value.chat?.copy(requireApproval = enabled)
                    )
                }
                .onFailure { e -> _uiState.value = _uiState.value.copy(error = e.message) }
        }
    }

    fun approveMember(userId: String) {
        viewModelScope.launch {
            approveMemberUseCase(chatId, userId)
                .onSuccess { loadGroupDetails() }
                .onFailure { e -> _uiState.value = _uiState.value.copy(error = e.message) }
        }
    }

    fun rejectMember(userId: String) {
        viewModelScope.launch {
            rejectMemberUseCase(chatId, userId)
                .onSuccess { loadGroupDetails() }
                .onFailure { e -> _uiState.value = _uiState.value.copy(error = e.message) }
        }
    }

    fun removeGroupMember(userId: String) {
        viewModelScope.launch {
            chatRepository.removeGroupMember(chatId, userId)
                .onSuccess { loadGroupDetails() }
                .onFailure { e -> _uiState.value = _uiState.value.copy(error = e.message) }
        }
    }

    fun leaveGroup() {
        viewModelScope.launch {
            leaveGroupUseCase(chatId)
                .onSuccess {
                    _uiState.value = _uiState.value.copy(leftGroup = true)
                }
                .onFailure { e -> _uiState.value = _uiState.value.copy(error = e.message) }
        }
    }

    fun clearError() {
        _uiState.value = _uiState.value.copy(error = null)
    }
}
