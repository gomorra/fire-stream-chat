package com.firestream.chat.ui.lists

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.firestream.chat.domain.model.ListData
import com.firestream.chat.domain.model.ListType
import com.firestream.chat.domain.usecase.list.CreateListUseCase
import com.firestream.chat.domain.usecase.list.ObserveMyListsUseCase
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.launch
import javax.inject.Inject

data class ListsUiState(
    val lists: List<ListData> = emptyList(),
    val isLoading: Boolean = true,
    val error: String? = null
)

@HiltViewModel
class ListsViewModel @Inject constructor(
    private val observeMyListsUseCase: ObserveMyListsUseCase,
    private val createListUseCase: CreateListUseCase
) : ViewModel() {

    private val _uiState = MutableStateFlow(ListsUiState())
    val uiState: StateFlow<ListsUiState> = _uiState.asStateFlow()

    init {
        observeLists()
    }

    private fun observeLists() {
        viewModelScope.launch {
            observeMyListsUseCase()
                .catch { e ->
                    _uiState.value = _uiState.value.copy(isLoading = false, error = e.message)
                }
                .collect { lists ->
                    _uiState.value = _uiState.value.copy(lists = lists, isLoading = false)
                }
        }
    }

    fun createList(title: String, type: ListType, onCreated: (String) -> Unit) {
        viewModelScope.launch {
            createListUseCase(title, type)
                .onSuccess { listData -> onCreated(listData.id) }
                .onFailure { e -> _uiState.value = _uiState.value.copy(error = e.message) }
        }
    }

    fun clearError() {
        _uiState.value = _uiState.value.copy(error = null)
    }
}
