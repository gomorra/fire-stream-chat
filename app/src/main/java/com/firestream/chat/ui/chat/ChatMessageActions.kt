package com.firestream.chat.ui.chat

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import com.firestream.chat.domain.model.Message
import com.firestream.chat.domain.usecase.message.AddReactionUseCase
import com.firestream.chat.domain.usecase.message.DeleteMessageUseCase
import com.firestream.chat.domain.usecase.message.EditMessageUseCase
import com.firestream.chat.domain.usecase.message.ForwardMessageUseCase
import com.firestream.chat.domain.usecase.message.PinMessageUseCase
import com.firestream.chat.domain.usecase.message.RemoveReactionUseCase
import com.firestream.chat.domain.usecase.message.StarMessageUseCase

internal class ChatMessageActions(
    private val chatId: String,
    private val deleteMessageUseCase: DeleteMessageUseCase,
    private val editMessageUseCase: EditMessageUseCase,
    private val addReactionUseCase: AddReactionUseCase,
    private val removeReactionUseCase: RemoveReactionUseCase,
    private val starMessageUseCase: StarMessageUseCase,
    private val pinMessageUseCase: PinMessageUseCase,
    private val forwardMessageUseCase: ForwardMessageUseCase,
    private val _uiState: MutableStateFlow<ChatUiState>,
    private val scope: CoroutineScope
) {

    fun deleteMessage(messageId: String) {
        scope.launch {
            deleteMessageUseCase(chatId, messageId)
                .onFailure { e -> _uiState.update { it.copy(error = e.message) } }
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
            editMessageUseCase(chatId, msg.id, newContent)
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
                removeReactionUseCase(chatId, messageId, currentUserId)
            } else {
                addReactionUseCase(chatId, messageId, currentUserId, emoji)
            }
        }
    }

    fun forwardMessage(message: Message, targetChatId: String, targetRecipientId: String) {
        scope.launch {
            forwardMessageUseCase(message, targetChatId, targetRecipientId)
                .onFailure { e -> _uiState.update { it.copy(error = e.message) } }
        }
    }

    fun toggleStar(message: Message) {
        scope.launch {
            starMessageUseCase(message.id, !message.isStarred)
                .onFailure { e -> _uiState.update { it.copy(error = e.message) } }
        }
    }

    fun togglePin(messageId: String, pinned: Boolean) {
        scope.launch {
            pinMessageUseCase(chatId, messageId, pinned)
                .onFailure { e -> _uiState.update { it.copy(error = e.message) } }
        }
    }
}
