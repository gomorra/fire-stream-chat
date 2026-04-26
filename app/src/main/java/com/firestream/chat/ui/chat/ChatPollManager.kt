// region: AGENT-NOTE
// Responsibility: Send / vote / close polls from the chat composer.
// Owns: ChatUiState.composer.isSending — and currently writes session.error
//   via AppError.from() (Phase 2 will route this through composer.error per
//   the manager contract — see docs/PATTERNS.md#chat-manager-slice-ownership).
// Collaborators: ChatViewModel (composition root), PollRepository.
// Don't put here: poll rendering (PollBubble) or poll-create UI (CreatePollSheet).
//   New send/vote intents are fine; reading non-poll state is not.
// endregion

package com.firestream.chat.ui.chat

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import com.firestream.chat.domain.model.AppError
import com.firestream.chat.domain.repository.PollRepository

internal class ChatPollManager(
    private val chatId: String,
    private val pollRepository: PollRepository,
    private val _uiState: MutableStateFlow<ChatUiState>,
    private val scope: CoroutineScope
) {

    fun sendPoll(question: String, options: List<String>, isMultipleChoice: Boolean, isAnonymous: Boolean) {
        scope.launch {
            _uiState.update { it.copy(composer = it.composer.copy(isSending = true)) }
            pollRepository.sendPoll(chatId, question, options, isMultipleChoice, isAnonymous)
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

    fun votePoll(messageId: String, optionIds: List<String>) {
        scope.launch {
            pollRepository.votePoll(chatId, messageId, optionIds)
                .onFailure { e -> _uiState.update { it.copy(session = it.session.copy(error = AppError.from(e))) } }
        }
    }

    fun closePoll(messageId: String) {
        scope.launch {
            pollRepository.closePoll(chatId, messageId)
                .onFailure { e -> _uiState.update { it.copy(session = it.session.copy(error = AppError.from(e))) } }
        }
    }
}
