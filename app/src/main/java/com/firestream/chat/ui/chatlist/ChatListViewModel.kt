package com.firestream.chat.ui.chatlist

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.firestream.chat.domain.model.Chat
import com.firestream.chat.domain.repository.AuthRepository
import com.firestream.chat.domain.usecase.chat.DeleteChatUseCase
import com.firestream.chat.domain.usecase.chat.GetChatsUseCase
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.launch
import javax.inject.Inject

data class ChatListUiState(
    val chats: List<Chat> = emptyList(),
    val isLoading: Boolean = true,
    val error: String? = null,
    val currentUserId: String = "",
    val pendingDeleteChatId: String? = null
)

@HiltViewModel
class ChatListViewModel @Inject constructor(
    private val getChatsUseCase: GetChatsUseCase,
    private val deleteChatUseCase: DeleteChatUseCase,
    private val authRepository: AuthRepository
) : ViewModel() {

    private val _uiState = MutableStateFlow(ChatListUiState())
    val uiState: StateFlow<ChatListUiState> = _uiState.asStateFlow()

    init {
        _uiState.value = _uiState.value.copy(currentUserId = authRepository.currentUserId ?: "")
        loadChats()
    }

    private fun loadChats() {
        viewModelScope.launch {
            getChatsUseCase()
                .catch { e ->
                    _uiState.value = _uiState.value.copy(
                        isLoading = false,
                        error = e.message
                    )
                }
                .collect { chats ->
                    _uiState.value = _uiState.value.copy(
                        chats = chats,
                        isLoading = false
                    )
                }
        }
    }

    fun requestDeleteChat(chatId: String) {
        _uiState.value = _uiState.value.copy(pendingDeleteChatId = chatId)
    }

    fun cancelDeleteChat() {
        _uiState.value = _uiState.value.copy(pendingDeleteChatId = null)
    }

    fun confirmDeleteChat() {
        val chatId = _uiState.value.pendingDeleteChatId ?: return
        _uiState.value = _uiState.value.copy(pendingDeleteChatId = null)
        viewModelScope.launch {
            deleteChatUseCase(chatId)
        }
    }
}
