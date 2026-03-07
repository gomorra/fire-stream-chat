package com.firestream.chat.ui.chat

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.firestream.chat.domain.model.Message
import com.firestream.chat.domain.usecase.message.GetMessagesUseCase
import com.firestream.chat.domain.usecase.message.SendMessageUseCase
import com.firestream.chat.domain.repository.AuthRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.launch
import javax.inject.Inject

data class ChatUiState(
    val messages: List<Message> = emptyList(),
    val isLoading: Boolean = true,
    val error: String? = null,
    val currentUserId: String = "",
    val isSending: Boolean = false
)

@HiltViewModel
class ChatViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    private val getMessagesUseCase: GetMessagesUseCase,
    private val sendMessageUseCase: SendMessageUseCase,
    private val authRepository: AuthRepository
) : ViewModel() {

    val chatId: String = checkNotNull(savedStateHandle["chatId"])
    val recipientId: String = checkNotNull(savedStateHandle["recipientId"])

    private val _uiState = MutableStateFlow(ChatUiState())
    val uiState: StateFlow<ChatUiState> = _uiState.asStateFlow()

    init {
        _uiState.value = _uiState.value.copy(currentUserId = authRepository.currentUserId ?: "")
        loadMessages()
    }

    private fun loadMessages() {
        viewModelScope.launch {
            getMessagesUseCase(chatId)
                .catch { e ->
                    _uiState.value = _uiState.value.copy(
                        isLoading = false,
                        error = e.message
                    )
                }
                .collect { messages ->
                    _uiState.value = _uiState.value.copy(
                        messages = messages,
                        isLoading = false
                    )
                }
        }
    }

    fun sendMessage(content: String) {
        if (content.isBlank()) return
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(isSending = true)
            sendMessageUseCase(chatId, content, recipientId)
                .onFailure { e ->
                    _uiState.value = _uiState.value.copy(
                        error = e.message,
                        isSending = false
                    )
                }
                .onSuccess {
                    _uiState.value = _uiState.value.copy(isSending = false)
                }
        }
    }

    fun clearError() {
        _uiState.value = _uiState.value.copy(error = null)
    }
}
