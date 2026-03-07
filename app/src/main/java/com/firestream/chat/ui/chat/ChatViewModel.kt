package com.firestream.chat.ui.chat

import android.net.Uri
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.firestream.chat.data.remote.LinkPreview
import com.firestream.chat.data.remote.LinkPreviewSource
import com.firestream.chat.domain.model.Message
import com.firestream.chat.domain.repository.AuthRepository
import com.firestream.chat.domain.repository.ChatRepository
import com.firestream.chat.domain.usecase.message.AddReactionUseCase
import com.firestream.chat.domain.usecase.message.DeleteMessageUseCase
import com.firestream.chat.domain.usecase.message.EditMessageUseCase
import com.firestream.chat.domain.usecase.message.ForwardMessageUseCase
import com.firestream.chat.domain.usecase.message.GetMessagesUseCase
import com.firestream.chat.domain.usecase.message.RemoveReactionUseCase
import com.firestream.chat.domain.usecase.message.SendMediaMessageUseCase
import com.firestream.chat.domain.usecase.message.SendMessageUseCase
import com.firestream.chat.domain.usecase.message.SendVoiceMessageUseCase
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
    val editingMessage: Message? = null,
    // Phase 1 state
    val replyToMessage: Message? = null,
    val linkPreviews: Map<String, LinkPreview> = emptyMap()
)

@HiltViewModel
class ChatViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    private val getMessagesUseCase: GetMessagesUseCase,
    private val sendMessageUseCase: SendMessageUseCase,
    private val deleteMessageUseCase: DeleteMessageUseCase,
    private val editMessageUseCase: EditMessageUseCase,
    private val sendMediaMessageUseCase: SendMediaMessageUseCase,
    private val addReactionUseCase: AddReactionUseCase,
    private val removeReactionUseCase: RemoveReactionUseCase,
    private val forwardMessageUseCase: ForwardMessageUseCase,
    private val sendVoiceMessageUseCase: SendVoiceMessageUseCase,
    private val linkPreviewSource: LinkPreviewSource,
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
                    // Fetch link previews for text messages with URLs
                    fetchLinkPreviewsFor(messages)
                }
        }
    }

    private fun fetchLinkPreviewsFor(messages: List<Message>) {
        messages.forEach { msg ->
            if (msg.type.name == "TEXT") {
                val url = linkPreviewSource.extractUrl(msg.content) ?: return@forEach
                if (_uiState.value.linkPreviews.containsKey(url)) return@forEach
                viewModelScope.launch {
                    val preview = linkPreviewSource.fetchPreview(url) ?: return@launch
                    _uiState.value = _uiState.value.copy(
                        linkPreviews = _uiState.value.linkPreviews + (url to preview)
                    )
                }
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
        val replyToId = _uiState.value.replyToMessage?.id
        viewModelScope.launch {
            chatRepository.setTyping(chatId, false)
            _uiState.value = _uiState.value.copy(isSending = true, replyToMessage = null)
            sendMessageUseCase(chatId, content, recipientId, replyToId)
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

    // Phase 1: reply-to
    fun setReplyTo(message: Message) {
        _uiState.value = _uiState.value.copy(replyToMessage = message)
    }

    fun clearReplyTo() {
        _uiState.value = _uiState.value.copy(replyToMessage = null)
    }

    // Phase 1: reactions
    fun toggleReaction(messageId: String, emoji: String) {
        val currentUserId = _uiState.value.currentUserId
        val message = _uiState.value.messages.find { it.id == messageId } ?: return
        viewModelScope.launch {
            if (message.reactions[currentUserId] == emoji) {
                removeReactionUseCase(chatId, messageId, currentUserId)
            } else {
                addReactionUseCase(chatId, messageId, currentUserId, emoji)
            }
        }
    }

    // Phase 1: forwarding
    fun forwardMessage(message: Message, targetChatId: String, targetRecipientId: String) {
        viewModelScope.launch {
            forwardMessageUseCase(message, targetChatId, targetRecipientId)
                .onFailure { e -> _uiState.value = _uiState.value.copy(error = e.message) }
        }
    }

    // Phase 1: voice messages
    fun sendVoiceMessage(uri: Uri, durationSeconds: Int) {
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(isSending = true)
            sendVoiceMessageUseCase(chatId, uri, recipientId, durationSeconds)
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
