package com.firestream.chat.ui.lists

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.firestream.chat.domain.model.ListData
import com.firestream.chat.domain.model.ListType
import com.firestream.chat.domain.usecase.list.AddListItemUseCase
import com.firestream.chat.domain.usecase.list.DeleteListUseCase
import com.firestream.chat.domain.usecase.list.ObserveListUseCase
import com.firestream.chat.domain.usecase.list.RemoveListItemUseCase
import com.firestream.chat.domain.usecase.list.ShareListToChatUseCase
import com.firestream.chat.domain.usecase.list.ToggleListItemUseCase
import com.firestream.chat.domain.usecase.list.UpdateListItemUseCase
import com.firestream.chat.domain.usecase.list.UpdateListTitleUseCase
import com.firestream.chat.domain.usecase.list.UpdateListTypeUseCase
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.launch
import javax.inject.Inject

data class ListDetailUiState(
    val listData: ListData? = null,
    val isLoading: Boolean = true,
    val error: String? = null,
    val isDeleted: Boolean = false
)

@HiltViewModel
class ListDetailViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    private val observeListUseCase: ObserveListUseCase,
    private val addListItemUseCase: AddListItemUseCase,
    private val removeListItemUseCase: RemoveListItemUseCase,
    private val toggleListItemUseCase: ToggleListItemUseCase,
    private val updateListItemUseCase: UpdateListItemUseCase,
    private val updateListTitleUseCase: UpdateListTitleUseCase,
    private val updateListTypeUseCase: UpdateListTypeUseCase,
    private val shareListToChatUseCase: ShareListToChatUseCase,
    private val deleteListUseCase: DeleteListUseCase
) : ViewModel() {

    val listId: String = checkNotNull(savedStateHandle["listId"])

    private val _uiState = MutableStateFlow(ListDetailUiState())
    val uiState: StateFlow<ListDetailUiState> = _uiState.asStateFlow()

    init {
        observeList()
    }

    private fun observeList() {
        viewModelScope.launch {
            observeListUseCase(listId)
                .catch { e ->
                    _uiState.value = _uiState.value.copy(isLoading = false, error = e.message)
                }
                .collect { listData ->
                    if (listData == null && !_uiState.value.isLoading) {
                        _uiState.value = _uiState.value.copy(isDeleted = true, isLoading = false)
                    } else {
                        _uiState.value = _uiState.value.copy(
                            listData = listData,
                            isLoading = false
                        )
                    }
                }
        }
    }

    fun addItem(text: String, quantity: String? = null, unit: String? = null) {
        if (text.isBlank()) return
        viewModelScope.launch {
            addListItemUseCase(listId, text, quantity, unit)
                .onFailure { e -> _uiState.value = _uiState.value.copy(error = e.message) }
        }
    }

    fun removeItem(itemId: String) {
        viewModelScope.launch {
            removeListItemUseCase(listId, itemId)
                .onFailure { e -> _uiState.value = _uiState.value.copy(error = e.message) }
        }
    }

    fun toggleItem(itemId: String) {
        viewModelScope.launch {
            toggleListItemUseCase(listId, itemId)
                .onFailure { e -> _uiState.value = _uiState.value.copy(error = e.message) }
        }
    }

    fun updateItem(itemId: String, text: String, quantity: String? = null, unit: String? = null) {
        viewModelScope.launch {
            updateListItemUseCase(listId, itemId, text, quantity, unit)
                .onFailure { e -> _uiState.value = _uiState.value.copy(error = e.message) }
        }
    }

    fun updateTitle(title: String) {
        viewModelScope.launch {
            updateListTitleUseCase(listId, title)
                .onFailure { e -> _uiState.value = _uiState.value.copy(error = e.message) }
        }
    }

    fun updateType(type: ListType) {
        viewModelScope.launch {
            updateListTypeUseCase(listId, type)
                .onFailure { e -> _uiState.value = _uiState.value.copy(error = e.message) }
        }
    }

    fun shareToChat(chatId: String) {
        viewModelScope.launch {
            shareListToChatUseCase(listId, chatId)
                .onFailure { e -> _uiState.value = _uiState.value.copy(error = e.message) }
        }
    }

    fun deleteList() {
        viewModelScope.launch {
            deleteListUseCase(listId)
                .onSuccess { _uiState.value = _uiState.value.copy(isDeleted = true) }
                .onFailure { e -> _uiState.value = _uiState.value.copy(error = e.message) }
        }
    }

    fun clearError() {
        _uiState.value = _uiState.value.copy(error = null)
    }
}
