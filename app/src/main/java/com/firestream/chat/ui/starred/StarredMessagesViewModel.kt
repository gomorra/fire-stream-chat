package com.firestream.chat.ui.starred

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.firestream.chat.domain.model.AppError
import com.firestream.chat.domain.model.Message
import com.firestream.chat.domain.repository.MessageRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.launch
import javax.inject.Inject

data class StarredMessagesUiState(
    val messages: List<Message> = emptyList(),
    val isLoading: Boolean = true,
    val error: AppError? = null
)

@HiltViewModel
class StarredMessagesViewModel @Inject constructor(
    private val messageRepository: MessageRepository
) : ViewModel() {

    private val _uiState = MutableStateFlow(StarredMessagesUiState())
    val uiState: StateFlow<StarredMessagesUiState> = _uiState.asStateFlow()

    init {
        viewModelScope.launch {
            messageRepository.getStarredMessages()
                .catch { e -> _uiState.value = _uiState.value.copy(error = AppError.from(e), isLoading = false) }
                .collect { messages ->
                    _uiState.value = _uiState.value.copy(messages = messages, isLoading = false)
                }
        }
    }

    fun unstarMessage(messageId: String) {
        viewModelScope.launch {
            messageRepository.starMessage(messageId, false)
        }
    }
}
