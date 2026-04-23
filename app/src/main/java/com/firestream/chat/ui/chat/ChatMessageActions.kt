package com.firestream.chat.ui.chat

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import com.firestream.chat.domain.model.AppError
import com.firestream.chat.domain.model.Message
import com.firestream.chat.domain.repository.MessageRepository

internal class ChatMessageActions(
    private val chatId: String,
    private val messageRepository: MessageRepository,
    private val _uiState: MutableStateFlow<ChatUiState>,
    private val scope: CoroutineScope
) {

    fun deleteMessage(messageId: String) {
        scope.launch {
            messageRepository.deleteMessage(chatId, messageId)
                .onFailure { e -> _uiState.update { it.copy(error = AppError.from(e)) } }
        }
    }

    fun startEdit(message: Message) {
        _uiState.update { it.copy(editingMessage = message) }
    }

    fun cancelEdit() {
        _uiState.update { it.copy(editingMessage = null) }
    }

    fun confirmEdit(newContent: String) {
        val msg = _uiState.value.editingMessage ?: return
        if (newContent.isBlank()) return
        _uiState.update { it.copy(editingMessage = null) }
        scope.launch {
            messageRepository.editMessage(chatId, msg.id, newContent)
                .onFailure { e -> _uiState.update { it.copy(error = AppError.from(e)) } }
        }
    }

    fun setReplyTo(message: Message) {
        _uiState.update { it.copy(replyToMessage = message) }
    }

    fun clearReplyTo() {
        _uiState.update { it.copy(replyToMessage = null) }
    }

    fun toggleReaction(messageId: String, emoji: String) {
        val currentUserId = _uiState.value.currentUserId
        val message = _uiState.value.messages.find { it.id == messageId } ?: return
        scope.launch {
            if (message.reactions[currentUserId] == emoji) {
                messageRepository.removeReaction(chatId, messageId, currentUserId)
            } else {
                messageRepository.addReaction(chatId, messageId, currentUserId, emoji)
            }
        }
    }

    fun forwardMessage(message: Message, targetChatId: String, targetRecipientId: String) {
        scope.launch {
            messageRepository.forwardMessage(message, targetChatId, targetRecipientId)
                .onFailure { e -> _uiState.update { it.copy(error = AppError.from(e)) } }
        }
    }

    fun toggleStar(message: Message) {
        scope.launch {
            messageRepository.starMessage(message.id, !message.isStarred)
                .onFailure { e -> _uiState.update { it.copy(error = AppError.from(e)) } }
        }
    }

    fun togglePin(messageId: String, pinned: Boolean) {
        scope.launch {
            messageRepository.pinMessage(chatId, messageId, pinned)
                .onFailure { e -> _uiState.update { it.copy(error = AppError.from(e)) } }
        }
    }
}
