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
import com.firestream.chat.domain.model.AppError
import com.firestream.chat.domain.model.Message
import com.firestream.chat.domain.repository.ChatRepository
import com.firestream.chat.domain.repository.MessageRepository
import com.firestream.chat.domain.util.MentionParser

internal class ChatMessageSender(
    private val chatId: String,
    private val recipientId: String,
    private val chatRepository: ChatRepository,
    private val messageRepository: MessageRepository,
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
            _uiState.update {
                it.copy(
                    composer = it.composer.copy(
                        isSending = true,
                        replyToMessage = null,
                        mentionCandidates = emptyList()
                    ),
                    messages = it.messages.copy(scrollToBottomTrigger = it.messages.scrollToBottomTrigger + 1)
                )
            }
            if (state.session.isBroadcast) {
                messageRepository.sendBroadcastMessage(chatId, content, state.session.broadcastRecipientIds)
                    .onFailure { e ->
                        _uiState.update {
                            it.copy(
                                composer = it.composer.copy(isSending = false),
                                session = it.session.copy(error = AppError.from(e))
                            )
                        }
                    }
                    .onSuccess { _uiState.update { it.copy(composer = it.composer.copy(isSending = false)) } }
            } else {
                val replyToId = state.composer.replyToMessage?.id
                val mentions = if (state.session.isGroupChat) MentionParser.extractMentions(content, state.displayNameToUserId) else emptyList()
                messageRepository.sendMessage(chatId, content, recipientId, replyToId, mentions, emojiSizes)
                    .onFailure { e ->
                        _uiState.update {
                            it.copy(
                                composer = it.composer.copy(isSending = false),
                                session = it.session.copy(error = AppError.from(e))
                            )
                        }
                    }
                    .onSuccess { _uiState.update { it.copy(composer = it.composer.copy(isSending = false)) } }
            }
        }
    }

    fun sendMediaMessage(uri: Uri, mimeType: String, caption: String = "") {
        scope.launch {
            _uiState.update { it.copy(composer = it.composer.copy(isSending = true)) }
            messageRepository.sendMediaMessage(chatId, uri.toString(), mimeType, recipientId, caption)
                .onFailure { e ->
                    _uiState.update {
                        it.copy(
                            composer = it.composer.copy(isSending = false),
                            session = it.session.copy(error = AppError.from(e))
                        )
                    }
                }
                .onSuccess { _uiState.update { it.copy(composer = it.composer.copy(isSending = false)) } }
        }
    }

    fun sendVoiceMessage(uri: Uri, durationSeconds: Int) {
        scope.launch {
            _uiState.update { it.copy(composer = it.composer.copy(isSending = true)) }
            messageRepository.sendVoiceMessage(chatId, uri.toString(), recipientId, durationSeconds)
                .onFailure { e ->
                    _uiState.update {
                        it.copy(
                            composer = it.composer.copy(isSending = false),
                            session = it.session.copy(error = AppError.from(e))
                        )
                    }
                }
                .onSuccess { _uiState.update { it.copy(composer = it.composer.copy(isSending = false)) } }
        }
    }

    fun sendLocationMessage(latitude: Double, longitude: Double, comment: String = "") {
        scope.launch {
            _uiState.update {
                it.copy(
                    composer = it.composer.copy(isSending = true),
                    messages = it.messages.copy(scrollToBottomTrigger = it.messages.scrollToBottomTrigger + 1)
                )
            }
            messageRepository.sendLocationMessage(chatId, latitude, longitude, recipientId, comment)
                .onFailure { e ->
                    _uiState.update {
                        it.copy(
                            composer = it.composer.copy(isSending = false),
                            session = it.session.copy(error = AppError.from(e))
                        )
                    }
                }
                .onSuccess { _uiState.update { it.copy(composer = it.composer.copy(isSending = false)) } }
        }
    }

    fun retrySend(message: Message) {
        scope.launch {
            _uiState.update { it.copy(composer = it.composer.copy(isSending = true)) }
            messageRepository.retryFailedMessage(message.id, recipientId)
                .onFailure { e ->
                    _uiState.update {
                        it.copy(
                            composer = it.composer.copy(isSending = false),
                            session = it.session.copy(error = AppError.from(e))
                        )
                    }
                }
                .onSuccess { _uiState.update { it.copy(composer = it.composer.copy(isSending = false)) } }
        }
    }

    fun onCleared() {
        typingDebounceJob?.cancel()
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            chatRepository.setTyping(chatId, false)
        }
    }
}
