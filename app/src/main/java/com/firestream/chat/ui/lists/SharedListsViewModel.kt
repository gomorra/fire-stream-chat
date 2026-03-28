package com.firestream.chat.ui.lists

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.firestream.chat.domain.model.ListData
import com.firestream.chat.domain.repository.ListRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.launch
import javax.inject.Inject

data class SharedListsUiState(
    val lists: List<ListData> = emptyList(),
    val isLoading: Boolean = true
)

@HiltViewModel
class SharedListsViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    private val listRepository: ListRepository
) : ViewModel() {

    private val chatId: String = checkNotNull(savedStateHandle["chatId"])

    private val _uiState = MutableStateFlow(SharedListsUiState())
    val uiState: StateFlow<SharedListsUiState> = _uiState.asStateFlow()

    init {
        viewModelScope.launch {
            listRepository.getSharedListsForChat(chatId)
                .catch { _uiState.value = _uiState.value.copy(isLoading = false) }
                .collect { lists ->
                    _uiState.value = SharedListsUiState(lists = lists, isLoading = false)
                }
        }
    }
}
