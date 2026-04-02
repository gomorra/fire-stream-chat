package com.firestream.chat.ui.chat

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
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
                .onFailure { e -> _uiState.update { it.copy(error = e.message) } }
        }
    }

    private var savedReplyToMessage: Message? = null

    fun startEdit(message: Message) {
        savedReplyToMessage = _uiState.value.replyToMessage
        _uiState.update { it.copy(editingMessage = message, replyToMessage = null) }
    }

    fun cancelEdit() {
        _uiState.update { it.copy(editingMessage = null, replyToMessage = savedReplyToMessage) }
        savedReplyToMessage = null
    }

    fun confirmEdit(newContent: String) {
        val msg = _uiState.value.editingMessage ?: return
        if (newContent.isBlank()) return
        _uiState.update { it.copy(editingMessage = null, replyToMessage = savedReplyToMessage) }
        savedReplyToMessage = null
        scope.launch {
            messageRepository.editMessage(chatId, msg.id, newContent)
                .onFailure { e -> _uiState.update { it.copy(error = e.message) } }
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
                .onFailure { e -> _uiState.update { it.copy(error = e.message) } }
        }
    }

    fun toggleStar(message: Message) {
        scope.launch {
            messageRepository.starMessage(message.id, !message.isStarred)
                .onFailure { e -> _uiState.update { it.copy(error = e.message) } }
        }
    }

    fun togglePin(messageId: String, pinned: Boolean) {
        scope.launch {
            messageRepository.pinMessage(chatId, messageId, pinned)
                .onFailure { e -> _uiState.update { it.copy(error = e.message) } }
        }
    }
}
