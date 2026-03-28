package com.firestream.chat.ui.chat

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import com.firestream.chat.domain.repository.PollRepository

internal class ChatPollManager(
    private val chatId: String,
    private val pollRepository: PollRepository,
    private val _uiState: MutableStateFlow<ChatUiState>,
    private val scope: CoroutineScope
) {

    fun sendPoll(question: String, options: List<String>, isMultipleChoice: Boolean, isAnonymous: Boolean) {
        scope.launch {
            _uiState.update { it.copy(isSending = true) }
            pollRepository.sendPoll(chatId, question, options, isMultipleChoice, isAnonymous)
                .onFailure { e -> _uiState.update { it.copy(error = e.message, isSending = false) } }
                .onSuccess { _uiState.update { it.copy(isSending = false) } }
        }
    }

    fun votePoll(messageId: String, optionIds: List<String>) {
        scope.launch {
            pollRepository.votePoll(chatId, messageId, optionIds)
                .onFailure { e -> _uiState.update { it.copy(error = e.message) } }
        }
    }

    fun closePoll(messageId: String) {
        scope.launch {
            pollRepository.closePoll(chatId, messageId)
                .onFailure { e -> _uiState.update { it.copy(error = e.message) } }
        }
    }
}
