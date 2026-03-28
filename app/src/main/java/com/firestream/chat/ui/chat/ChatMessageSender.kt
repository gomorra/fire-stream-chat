package com.firestream.chat.ui.chat

import android.net.Uri
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import com.firestream.chat.domain.repository.ChatRepository
import com.firestream.chat.domain.usecase.message.ParseMentionsUseCase
import com.firestream.chat.domain.usecase.message.SendBroadcastMessageUseCase
import com.firestream.chat.domain.usecase.message.SendMediaMessageUseCase
import com.firestream.chat.domain.usecase.message.SendMessageUseCase
import com.firestream.chat.domain.usecase.message.SendVoiceMessageUseCase

internal class ChatMessageSender(
    private val chatId: String,
    private val recipientId: String,
    private val sendMessageUseCase: SendMessageUseCase,
    private val sendMediaMessageUseCase: SendMediaMessageUseCase,
    private val sendVoiceMessageUseCase: SendVoiceMessageUseCase,
    private val sendBroadcastMessageUseCase: SendBroadcastMessageUseCase,
    private val parseMentionsUseCase: ParseMentionsUseCase,
    private val chatRepository: ChatRepository,
    private val _uiState: MutableStateFlow<ChatUiState>,
    private val scope: CoroutineScope
) {

    private var typingDebounceJob: Job? = null

    fun onTyping(text: String) {
        if (text.isNotBlank()) {
            scope.launch { chatRepository.setTyping(chatId, true) }
            typingDebounceJob?.cancel()
            typingDebounceJob = scope.launch {
                delay(4_000)
                chatRepository.setTyping(chatId, false)
            }
        } else {
            typingDebounceJob?.cancel()
            scope.launch { chatRepository.setTyping(chatId, false) }
        }
    }

    fun sendMessage(content: String, emojiSizes: Map<Int, Float> = emptyMap()) {
        if (content.isBlank()) return
        typingDebounceJob?.cancel()
        val state = _uiState.value
        scope.launch {
            chatRepository.setTyping(chatId, false)
            _uiState.update { it.copy(isSending = true, replyToMessage = null, mentionCandidates = emptyList()) }
            if (state.isBroadcast) {
                sendBroadcastMessageUseCase(chatId, content, state.broadcastRecipientIds)
                    .onFailure { e -> _uiState.update { it.copy(error = e.message, isSending = false) } }
                    .onSuccess { _uiState.update { it.copy(isSending = false) } }
            } else {
                val replyToId = state.replyToMessage?.id
                val mentions = if (state.isGroupChat) parseMentionsUseCase(content, state.displayNameToUserId) else emptyList()
                sendMessageUseCase(chatId, content, recipientId, replyToId, mentions, emojiSizes)
                    .onFailure { e -> _uiState.update { it.copy(error = e.message, isSending = false) } }
                    .onSuccess { _uiState.update { it.copy(isSending = false) } }
            }
        }
    }

    fun sendMediaMessage(uri: Uri, mimeType: String) {
        scope.launch {
            _uiState.update { it.copy(isSending = true) }
            sendMediaMessageUseCase(chatId, uri, mimeType, recipientId)
                .onFailure { e -> _uiState.update { it.copy(error = e.message, isSending = false) } }
                .onSuccess { _uiState.update { it.copy(isSending = false) } }
        }
    }

    fun sendVoiceMessage(uri: Uri, durationSeconds: Int) {
        scope.launch {
            _uiState.update { it.copy(isSending = true) }
            sendVoiceMessageUseCase(chatId, uri, recipientId, durationSeconds)
                .onFailure { e -> _uiState.update { it.copy(error = e.message, isSending = false) } }
                .onSuccess { _uiState.update { it.copy(isSending = false) } }
        }
    }

    fun onCleared() {
        typingDebounceJob?.cancel()
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            chatRepository.setTyping(chatId, false)
        }
    }
}
