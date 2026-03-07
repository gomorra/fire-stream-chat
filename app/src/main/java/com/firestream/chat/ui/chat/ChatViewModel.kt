package com.firestream.chat.ui.chat

import android.net.Uri
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.firestream.chat.domain.model.Message
import com.firestream.chat.domain.repository.AuthRepository
import com.firestream.chat.domain.repository.ChatRepository
import com.firestream.chat.domain.usecase.message.DeleteMessageUseCase
import com.firestream.chat.domain.usecase.message.EditMessageUseCase
import com.firestream.chat.domain.usecase.message.GetMessagesUseCase
import com.firestream.chat.domain.usecase.message.SendMediaMessageUseCase
import com.firestream.chat.domain.usecase.message.SendMessageUseCase
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
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
    val isSending: Boolean = false,
    val typingUserIds: List<String> = emptyList(),
    val editingMessage: Message? = null
)

@HiltViewModel
class ChatViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    private val getMessagesUseCase: GetMessagesUseCase,
    private val sendMessageUseCase: SendMessageUseCase,
    private val deleteMessageUseCase: DeleteMessageUseCase,
    private val editMessageUseCase: EditMessageUseCase,
    private val sendMediaMessageUseCase: SendMediaMessageUseCase,
    private val authRepository: AuthRepository,
    private val chatRepository: ChatRepository
) : ViewModel() {

    val chatId: String = checkNotNull(savedStateHandle["chatId"])
    val recipientId: String = checkNotNull(savedStateHandle["recipientId"])

    private val _uiState = MutableStateFlow(ChatUiState())
    val uiState: StateFlow<ChatUiState> = _uiState.asStateFlow()

    private var typingDebounceJob: Job? = null

    init {
        _uiState.value = _uiState.value.copy(currentUserId = authRepository.currentUserId ?: "")
        loadMessages()
        observeTyping()
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

    private fun observeTyping() {
        viewModelScope.launch {
            chatRepository.observeTyping(chatId)
                .catch { /* ignore typing errors */ }
                .collect { typingIds ->
                    val othersTyping = typingIds.filter { it != _uiState.value.currentUserId }
                    _uiState.value = _uiState.value.copy(typingUserIds = othersTyping)
                }
        }
    }

    fun onTyping(text: String) {
        if (text.isNotBlank()) {
            viewModelScope.launch { chatRepository.setTyping(chatId, true) }
            typingDebounceJob?.cancel()
            typingDebounceJob = viewModelScope.launch {
                delay(4_000)
                chatRepository.setTyping(chatId, false)
            }
        } else {
            typingDebounceJob?.cancel()
            viewModelScope.launch { chatRepository.setTyping(chatId, false) }
        }
    }

    fun sendMessage(content: String) {
        if (content.isBlank()) return
        typingDebounceJob?.cancel()
        viewModelScope.launch {
            chatRepository.setTyping(chatId, false)
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

    fun deleteMessage(messageId: String) {
        viewModelScope.launch {
            deleteMessageUseCase(chatId, messageId)
                .onFailure { e -> _uiState.value = _uiState.value.copy(error = e.message) }
        }
    }

    fun startEdit(message: Message) {
        _uiState.value = _uiState.value.copy(editingMessage = message)
    }

    fun cancelEdit() {
        _uiState.value = _uiState.value.copy(editingMessage = null)
    }

    fun confirmEdit(newContent: String) {
        val msg = _uiState.value.editingMessage ?: return
        if (newContent.isBlank()) return
        _uiState.value = _uiState.value.copy(editingMessage = null)
        viewModelScope.launch {
            editMessageUseCase(chatId, msg.id, newContent)
                .onFailure { e -> _uiState.value = _uiState.value.copy(error = e.message) }
        }
    }

    fun sendMediaMessage(uri: Uri, mimeType: String) {
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(isSending = true)
            sendMediaMessageUseCase(chatId, uri, mimeType, recipientId)
                .onFailure { e -> _uiState.value = _uiState.value.copy(error = e.message, isSending = false) }
                .onSuccess { _uiState.value = _uiState.value.copy(isSending = false) }
        }
    }

    fun clearError() {
        _uiState.value = _uiState.value.copy(error = null)
    }

    override fun onCleared() {
        super.onCleared()
        typingDebounceJob?.cancel()
        viewModelScope.launch { chatRepository.setTyping(chatId, false) }
    }
}
