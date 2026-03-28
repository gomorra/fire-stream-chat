package com.firestream.chat.ui.chat

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import com.firestream.chat.domain.usecase.message.SendPollUseCase
import com.firestream.chat.domain.usecase.message.VotePollUseCase
import com.firestream.chat.domain.usecase.message.ClosePollUseCase

internal class ChatPollManager(
    private val chatId: String,
    private val sendPollUseCase: SendPollUseCase,
    private val votePollUseCase: VotePollUseCase,
    private val closePollUseCase: ClosePollUseCase,
    private val _uiState: MutableStateFlow<ChatUiState>,
    private val scope: CoroutineScope
) {

    fun sendPoll(question: String, options: List<String>, isMultipleChoice: Boolean, isAnonymous: Boolean) {
        scope.launch {
            _uiState.update { it.copy(isSending = true) }
            sendPollUseCase(chatId, question, options, isMultipleChoice, isAnonymous)
                .onFailure { e -> _uiState.update { it.copy(error = e.message, isSending = false) } }
                .onSuccess { _uiState.update { it.copy(isSending = false) } }
        }
    }

    fun votePoll(messageId: String, optionIds: List<String>) {
        scope.launch {
            votePollUseCase(chatId, messageId, optionIds)
                .onFailure { e -> _uiState.update { it.copy(error = e.message) } }
        }
    }

    fun closePoll(messageId: String) {
        scope.launch {
            closePollUseCase(chatId, messageId)
                .onFailure { e -> _uiState.update { it.copy(error = e.message) } }
        }
    }
}
