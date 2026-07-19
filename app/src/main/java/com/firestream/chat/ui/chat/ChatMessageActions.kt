package com.firestream.chat.ui.chat

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import com.firestream.chat.domain.model.AppError
import com.firestream.chat.domain.model.Message
import com.firestream.chat.domain.model.MessageType
import com.firestream.chat.domain.model.Reminder
import com.firestream.chat.domain.model.ReminderScheduleOutcome
import com.firestream.chat.domain.repository.MessageRepository
import com.firestream.chat.domain.repository.ReminderRepository

internal class ChatMessageActions(
    private val chatId: String,
    private val recipientId: String,
    private val messageRepository: MessageRepository,
    private val reminderRepository: ReminderRepository,
    private val _uiState: MutableStateFlow<ChatUiState>,
    private val scope: CoroutineScope,
    // Bridges INEXACT_FALLBACK back to ChatViewModel, which surfaces the in-app
    // banner via ChatCommandsManager.setExactAlarmBannerVisible — mirrors how
    // ChatTimerReactor's onScheduleResult is wired (see docs/PATTERNS.md
    // #chat-manager-slice-ownership: this class must not write the commands
    // slice directly).
    private val onReminderScheduled: (ReminderScheduleOutcome) -> Unit = {},
) {

    fun deleteMessage(messageId: String) {
        scope.launch {
            messageRepository.deleteMessage(chatId, messageId)
                .onFailure { e -> _uiState.update { it.copy(session = it.session.copy(error = AppError.from(e))) } }
        }
    }

    fun startEdit(message: Message) {
        _uiState.update { it.copy(composer = it.composer.copy(editingMessage = message)) }
    }

    fun cancelEdit() {
        _uiState.update { it.copy(composer = it.composer.copy(editingMessage = null)) }
    }

    fun confirmEdit(newContent: String, emojiSizes: Map<Int, Float> = emptyMap()) {
        val msg = _uiState.value.composer.editingMessage ?: return
        if (newContent.isBlank()) return
        _uiState.update { it.copy(composer = it.composer.copy(editingMessage = null)) }
        scope.launch {
            messageRepository.editMessage(chatId, msg.id, newContent, emojiSizes)
                .onFailure { e -> _uiState.update { it.copy(session = it.session.copy(error = AppError.from(e))) } }
        }
    }

    fun setReplyTo(message: Message) {
        _uiState.update { it.copy(composer = it.composer.copy(replyToMessage = message)) }
    }

    fun clearReplyTo() {
        _uiState.update { it.copy(composer = it.composer.copy(replyToMessage = null)) }
    }

    fun toggleReaction(messageId: String, emoji: String) {
        val currentUserId = _uiState.value.session.currentUserId
        val message = _uiState.value.messages.messages.find { it.id == messageId } ?: return
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
                .onFailure { e -> _uiState.update { it.copy(session = it.session.copy(error = AppError.from(e))) } }
        }
    }

    fun toggleStar(message: Message) {
        scope.launch {
            messageRepository.starMessage(message.id, !message.isStarred)
                .onFailure { e -> _uiState.update { it.copy(session = it.session.copy(error = AppError.from(e))) } }
        }
    }

    fun togglePin(messageId: String, pinned: Boolean) {
        scope.launch {
            messageRepository.pinMessage(chatId, messageId, pinned)
                .onFailure { e -> _uiState.update { it.copy(session = it.session.copy(error = AppError.from(e))) } }
        }
    }

    /** Schedules a message-snooze reminder to fire at [fireAtMs]. */
    fun snoozeMessage(message: Message, fireAtMs: Long) {
        val session = _uiState.value.session
        val reminder = Reminder(
            id = message.id,
            messageId = message.id,
            chatId = chatId,
            recipientId = recipientId,
            fireAtMs = fireAtMs,
            messageSnapshot = snapshotContentFor(message),
            senderNameSnapshot = senderNameFor(message, session),
            createdAtMs = System.currentTimeMillis(),
        )
        scope.launch {
            reminderRepository.schedule(reminder)
                .onSuccess { outcome ->
                    if (outcome == ReminderScheduleOutcome.INEXACT_FALLBACK) onReminderScheduled(outcome)
                }
                .onFailure { e -> _uiState.update { it.copy(session = it.session.copy(error = AppError.from(e))) } }
        }
    }

    fun cancelReminder(messageId: String) {
        scope.launch {
            reminderRepository.cancel(messageId)
                .onFailure { e -> _uiState.update { it.copy(session = it.session.copy(error = AppError.from(e))) } }
        }
    }

    // Best-effort sender name for the notification that fires later, after the
    // live message may have been deleted. "You" for the current user's own
    // messages (mirrors the star/starred-overview convention); otherwise the
    // richest name source already populated on session — participantAvatars is
    // filled for both the 1:1 recipient (ChatInfoManager.observeRecipient) and
    // group participants (loadGroupParticipants), so it covers both chat kinds.
    private fun senderNameFor(message: Message, session: SessionState): String =
        if (message.senderId == session.currentUserId) {
            "You"
        } else {
            session.participantAvatars[message.senderId]?.displayName
                ?: session.chatName
                ?: message.senderId
        }

    // Mirrors FCMService's notification-text formatting for non-text types (the
    // fired reminder notification is the direct analogue of a push notification
    // rendering this same message later) — kept local rather than shared since
    // FCMService lives in data/ and ui/ must not depend on it as a symbol source.
    private fun snapshotContentFor(message: Message): String = when (message.type) {
        MessageType.IMAGE -> message.content.takeIf { it.isNotBlank() } ?: "📷 Photo"
        MessageType.VIDEO -> message.content.takeIf { it.isNotBlank() } ?: "🎥 Video"
        MessageType.VOICE -> "🎙️ Voice message"
        MessageType.DOCUMENT -> "📎 Document"
        MessageType.LIST -> message.content.takeIf { it.isNotBlank() } ?: "📋 Shared a list"
        MessageType.POLL -> "📊 Poll"
        MessageType.LOCATION -> message.content.takeIf { it.isNotBlank() && it != LOCATION_DEFAULT_CONTENT }
            ?: "📍 $LOCATION_DEFAULT_CONTENT"
        MessageType.CALL -> message.content.takeIf { it.isNotBlank() } ?: "Call"
        MessageType.TIMER -> message.content.takeIf { it.isNotBlank() } ?: "Timer"
        MessageType.TEXT -> message.content
    }
}
