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
import com.firestream.chat.domain.model.OnlineMedia
import com.firestream.chat.domain.repository.ChatRepository
import com.firestream.chat.domain.repository.MessageRepository
import com.firestream.chat.domain.repository.StickerRepository
import com.firestream.chat.domain.util.MentionParser

/**
 * How often a continuous typist re-announces itself. It must stay well under
 * the reader's ten-second expiry (`TYPING_TTL_MS` in `FirestoreChatSource`),
 * because the reader compares the writer's clock with its own.
 */
internal const val TYPING_REFRESH_MS = 3_000L

/** How long after the last keystroke the typing indicator is withdrawn. */
internal const val TYPING_IDLE_MS = 4_000L

internal class ChatMessageSender(
    private val chatId: String,
    private val chatRepository: ChatRepository,
    private val messageRepository: MessageRepository,
    private val stickerRepository: StickerRepository,
    private val _uiState: MutableStateFlow<ChatUiState>,
    private val scope: CoroutineScope
) {

    private var typingIdleJob: Job? = null

    /** Active while the last typing-on write is still fresh. Keystrokes inside it write nothing. */
    private var typingFreshJob: Job? = null

    /** True between a typing-on write and the typing-off write that ends it. */
    private var typingAnnounced = false

    /**
     * Tells the other members that this user is typing.
     *
     * A typing write changes the chat document, and every member's chat and
     * chat-list listener is billed a read for it. A burst of typing therefore
     * costs one typing-on write per [TYPING_REFRESH_MS] and one typing-off
     * write when it ends, not one write per keystroke.
     */
    fun onTyping(text: String) {
        if (text.isBlank()) {
            stopTyping()
            return
        }
        if (typingFreshJob?.isActive != true) {
            typingAnnounced = true
            scope.launch { chatRepository.setTyping(chatId, true) }
            typingFreshJob = scope.launch { delay(TYPING_REFRESH_MS) }
        }
        typingIdleJob?.cancel()
        typingIdleJob = scope.launch {
            delay(TYPING_IDLE_MS)
            typingFreshJob?.cancel()
            typingAnnounced = false
            chatRepository.setTyping(chatId, false)
        }
    }

    /**
     * Ends the typing indicator. Writes nothing when no typing-on write is
     * outstanding. The write runs in [writeScope] and is never awaited by the caller.
     */
    private fun stopTyping(writeScope: CoroutineScope = scope) {
        typingIdleJob?.cancel()
        typingFreshJob?.cancel()
        if (!typingAnnounced) return
        typingAnnounced = false
        writeScope.launch { chatRepository.setTyping(chatId, false) }
    }

    fun sendMessage(content: String, emojiSizes: Map<Int, Float> = emptyMap()) {
        if (content.isBlank()) return
        val state = _uiState.value
        // Typing-off is a backend write that is awaited until the server acks;
        // with no (or a bad) connection it does not return for as long as the
        // connection is missing. It must run *beside* the send, never in front
        // of it: awaiting it here held the message back from the repository, so
        // the bubble never appeared while offline and leaving the screen
        // cancelled the coroutine before the message was ever written to Room.
        stopTyping()
        scope.launch {
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
                messageRepository.sendMessage(chatId, content, replyToId, mentions, emojiSizes)
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
                    item.sendMimeType,
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

    /**
     * Sends a sticker from the library and moves it to the front of Recents.
     *
     * [packId] is the pack the user picked it from, or null for a pick from
     * Recents or from a suggestion. The repository decides whether the
     * recipient sees it. No `isSending`: like a text send this is local-first,
     * and a second sticker must be sendable while the first is in flight.
     */
    fun sendSticker(stickerId: String, packId: String?) {
        scope.launch {
            scrollToBottom()
            sendStickerNow(stickerId, packId).onFailure(::showError)
        }
    }

    private suspend fun sendStickerNow(stickerId: String, packId: String?): Result<Message> =
        messageRepository.sendStickerMessage(chatId, stickerId, packId)
            .onSuccess { stickerRepository.markUsed(stickerId) }

    /**
     * Sends a picture the keyboard inserted. A GIF goes out as it is. Any other
     * picture is put into the `SAVED` pack and sent as a sticker, so it is in
     * the Stickers tab the next time.
     *
     * [onHandled] runs once the bytes are copied or the send is given up, also
     * when the scope is cancelled. The keyboard's grant for [uri] is held until then.
     */
    fun sendKeyboardContent(uri: Uri, mimeType: String, onHandled: () -> Unit = {}) {
        val route = keyboardContentRoute(mimeType) ?: return onHandled()
        scope.launch {
            try {
                scrollToBottom()
                when (route) {
                    KeyboardContentRoute.GIF ->
                        messageRepository.sendGifMessage(chatId, uri.toString(), mimeType).onFailure(::showError)
                    KeyboardContentRoute.STICKER -> stickerRepository.saveSticker(uri.toString())
                        .onSuccess { stickerId -> sendStickerNow(stickerId, packId = null).onFailure(::showError) }
                        .onFailure(::showError)
                }
            } finally {
                onHandled()
            }
        }
    }

    /**
     * Sends a GIF or a sticker picked from the online catalogue. The message
     * points at the provider's url, so nothing is uploaded and the library is
     * not touched: an online sticker has no id there, and never joins Recents.
     *
     * [onSent] runs once the message is queued. It does not run for a refused pick.
     */
    fun sendOnlineMedia(media: OnlineMedia, onSent: () -> Unit = {}) {
        scope.launch {
            scrollToBottom()
            messageRepository.sendOnlineMedia(chatId, media)
                .onSuccess { onSent() }
                .onFailure(::showError)
        }
    }

    private fun scrollToBottom() = _uiState.update {
        it.copy(messages = it.messages.copy(scrollToBottomTrigger = it.messages.scrollToBottomTrigger + 1))
    }

    private fun showError(e: Throwable) = _uiState.update { it.copy(session = it.session.copy(error = AppError.from(e))) }

    fun sendVoiceMessage(uri: Uri, durationSeconds: Int) {
        scope.launch {
            _uiState.update { it.copy(composer = it.composer.copy(isSending = true)) }
            messageRepository.sendVoiceMessage(chatId, uri.toString(), durationSeconds)
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
            messageRepository.sendLocationMessage(chatId, latitude, longitude, comment)
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
            messageRepository.retryFailedMessage(message.id)
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
        stopTyping(CoroutineScope(SupervisorJob() + Dispatchers.IO))
    }
}
