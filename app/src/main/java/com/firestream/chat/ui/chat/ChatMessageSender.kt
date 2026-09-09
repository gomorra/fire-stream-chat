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
            // Deliberately no `isSending = true` here: it gates the send button
            // (ChatScreen), and a text send is local-first — the optimistic
            // bubble is already on screen. Holding the button disabled until the
            // backend acked meant the user could not fire off a second message
            // while the first was still in flight, which is what made sending
            // feel laggy on a slow connection. Media, poll and timer sends still
            // set it: there the flag prevents a genuine double-submit.
            _uiState.update {
                it.copy(
                    composer = it.composer.copy(
                        replyToMessage = null,
                        mentionCandidates = emptyList()
                    ),
                    messages = it.messages.copy(scrollToBottomTrigger = it.messages.scrollToBottomTrigger + 1)
                )
            }
            // Failures surface as the error banner; the bubble itself is already
            // flipped to FAILED in Room by the repository. Neither branch touches
            // isSending — it was never set above, and clearing it here would
            // release the composer out from under a media send running alongside.
            if (state.session.isBroadcast) {
                messageRepository.sendBroadcastMessage(chatId, content, state.session.broadcastRecipientIds)
                    .onFailure { e ->
                        _uiState.update { it.copy(session = it.session.copy(error = AppError.from(e))) }
                    }
            } else {
                val replyToId = state.composer.replyToMessage?.id
                val mentions = if (state.session.isGroupChat) MentionParser.extractMentions(content, state.displayNameToUserId) else emptyList()
                messageRepository.sendMessage(chatId, content, recipientId, replyToId, mentions, emojiSizes)
                    .onFailure { e ->
                        _uiState.update { it.copy(session = it.session.copy(error = AppError.from(e))) }
                    }
            }
        }
    }

    fun sendMediaMessage(uri: Uri, mimeType: String, caption: String = "") =
        sendMediaMessages(listOf(PendingMedia(uri, mimeType, caption)))

    /**
     * Send a picked batch, in the order the user arranged it.
     *
     * Sequential so the images land in the order they were picked; memory is
     * bounded process-wide by MediaProcessingLimiter, not here. One failure does
     * not stop the rest — the first error is reported once the batch is done.
     *
     * Each item carries its own `uri` (the edit cursor's current step) and its
     * own `isHd` — a null there means the repository falls back to the global
     * preference, so an untouched pick sends exactly as it always did.
     */
    fun sendMediaMessages(items: List<PendingMedia>) {
        if (items.isEmpty()) return
        scope.launch {
            _uiState.update { it.copy(composer = it.composer.copy(isSending = true)) }
            var firstError: AppError? = null
            items.forEach { item ->
                messageRepository.sendMediaMessage(
                    chatId,
                    item.uri.toString(),
                    item.mimeType,
                    recipientId,
                    item.caption,
                    item.isHd,
                ).onFailure { e -> if (firstError == null) firstError = AppError.from(e) }
            }
            val error = firstError
            _uiState.update {
                it.copy(
                    composer = it.composer.copy(isSending = false),
                    session = if (error != null) it.session.copy(error = error) else it.session
                )
            }
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
