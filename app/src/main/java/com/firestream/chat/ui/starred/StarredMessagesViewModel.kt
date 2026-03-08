package com.firestream.chat.ui.starred

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.firestream.chat.domain.model.Message
import com.firestream.chat.domain.usecase.message.GetStarredMessagesUseCase
import com.firestream.chat.domain.usecase.message.StarMessageUseCase
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
    val error: String? = null
)

@HiltViewModel
class StarredMessagesViewModel @Inject constructor(
    private val getStarredMessagesUseCase: GetStarredMessagesUseCase,
    private val starMessageUseCase: StarMessageUseCase
) : ViewModel() {

    private val _uiState = MutableStateFlow(StarredMessagesUiState())
    val uiState: StateFlow<StarredMessagesUiState> = _uiState.asStateFlow()

    init {
        viewModelScope.launch {
            getStarredMessagesUseCase()
                .catch { e -> _uiState.value = _uiState.value.copy(error = e.message, isLoading = false) }
                .collect { messages ->
                    _uiState.value = _uiState.value.copy(messages = messages, isLoading = false)
                }
        }
    }

    fun unstarMessage(messageId: String) {
        viewModelScope.launch {
            starMessageUseCase(messageId, false)
        }
    }
}
