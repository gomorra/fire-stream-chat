package com.firestream.chat.ui.lists

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.firestream.chat.domain.model.Chat
import com.firestream.chat.domain.model.ListData
import com.firestream.chat.domain.model.ListDiff
import com.firestream.chat.domain.model.ListType
import com.firestream.chat.domain.repository.ChatRepository
import com.firestream.chat.data.remote.firebase.FirebaseAuthSource
import com.firestream.chat.domain.usecase.list.AddListItemUseCase
import com.firestream.chat.domain.usecase.list.DeleteListUseCase
import com.firestream.chat.domain.usecase.list.ObserveListUseCase
import com.firestream.chat.domain.usecase.list.ReorderListItemsUseCase
import com.firestream.chat.domain.usecase.list.RemoveListItemUseCase
import com.firestream.chat.domain.usecase.list.SendListUpdateToChatsUseCase
import com.firestream.chat.domain.usecase.list.ShareListToChatUseCase
import com.firestream.chat.domain.usecase.list.ToggleListItemUseCase
import com.firestream.chat.domain.usecase.list.UpdateListItemUseCase
import com.firestream.chat.domain.usecase.list.UpdateListTitleUseCase
import com.firestream.chat.domain.usecase.list.UpdateListTypeUseCase
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.launch
import javax.inject.Inject

private const val DEBOUNCE_MS = 30_000L

data class ListDetailUiState(
    val listData: ListData? = null,
    val chats: List<Chat> = emptyList(),
    val currentUserId: String = "",
    val isLoading: Boolean = true,
    val error: String? = null,
    val isDeleted: Boolean = false
) {
    val displayItems get() = listData?.items
        ?.let { items -> items.filter { !it.isChecked } + items.filter { it.isChecked } }
        ?: emptyList()
}

@HiltViewModel
class ListDetailViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    private val observeListUseCase: ObserveListUseCase,
    private val addListItemUseCase: AddListItemUseCase,
    private val removeListItemUseCase: RemoveListItemUseCase,
    private val toggleListItemUseCase: ToggleListItemUseCase,
    private val updateListItemUseCase: UpdateListItemUseCase,
    private val reorderListItemsUseCase: ReorderListItemsUseCase,
    private val updateListTitleUseCase: UpdateListTitleUseCase,
    private val updateListTypeUseCase: UpdateListTypeUseCase,
    private val shareListToChatUseCase: ShareListToChatUseCase,
    private val deleteListUseCase: DeleteListUseCase,
    private val sendListUpdateToChatsUseCase: SendListUpdateToChatsUseCase,
    private val chatRepository: ChatRepository,
    private val authSource: FirebaseAuthSource
) : ViewModel() {

    val listId: String = checkNotNull(savedStateHandle["listId"])

    private val _uiState = MutableStateFlow(ListDetailUiState())
    val uiState: StateFlow<ListDetailUiState> = _uiState.asStateFlow()

    private var pendingDiff = ListDiff()
    private var debounceJob: Job? = null

    init {
        _uiState.value = _uiState.value.copy(currentUserId = authSource.currentUserId ?: "")
        observeList()
        loadChats()
    }

    private fun loadChats() {
        viewModelScope.launch {
            chatRepository.getChats()
                .catch { }
                .collect { chats ->
                    _uiState.value = _uiState.value.copy(chats = chats)
                }
        }
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

    private fun accumulateAndDebounce(diff: ListDiff) {
        pendingDiff = ListDiff.accumulate(pendingDiff, diff)
        debounceJob?.cancel()
        debounceJob = viewModelScope.launch {
            delay(DEBOUNCE_MS)
            flushPendingDiff()
        }
    }

    private suspend fun flushPendingDiff() {
        val diff = pendingDiff
        if (diff.isEmpty) return
        pendingDiff = ListDiff()

        val listData = _uiState.value.listData ?: return
        if (listData.sharedChatIds.isEmpty()) return

        sendListUpdateToChatsUseCase(listId, listData.title, listData.sharedChatIds, diff)
    }

    fun addItem(text: String, quantity: String? = null, unit: String? = null) {
        if (text.isBlank()) return
        viewModelScope.launch {
            addListItemUseCase(listId, text, quantity, unit)
                .onSuccess { accumulateAndDebounce(ListDiff(added = listOf(text))) }
                .onFailure { e -> _uiState.value = _uiState.value.copy(error = e.message) }
        }
    }

    fun removeItem(itemId: String) {
        val itemText = _uiState.value.listData?.items?.find { it.id == itemId }?.text ?: itemId
        viewModelScope.launch {
            removeListItemUseCase(listId, itemId)
                .onSuccess { accumulateAndDebounce(ListDiff(removed = listOf(itemText))) }
                .onFailure { e -> _uiState.value = _uiState.value.copy(error = e.message) }
        }
    }

    fun toggleItem(itemId: String) {
        val item = _uiState.value.listData?.items?.find { it.id == itemId }
        viewModelScope.launch {
            toggleListItemUseCase(listId, itemId)
                .onSuccess {
                    if (item != null) {
                        val diff = if (item.isChecked) {
                            ListDiff(unchecked = listOf(item.text))
                        } else {
                            ListDiff(checked = listOf(item.text))
                        }
                        accumulateAndDebounce(diff)
                    }
                }
                .onFailure { e -> _uiState.value = _uiState.value.copy(error = e.message) }
        }
    }

    fun updateItem(itemId: String, text: String, quantity: String? = null, unit: String? = null) {
        viewModelScope.launch {
            updateListItemUseCase(listId, itemId, text, quantity, unit)
                .onFailure { e -> _uiState.value = _uiState.value.copy(error = e.message) }
        }
    }

    fun reorderItems(fromIndex: Int, toIndex: Int) {
        val items = _uiState.value.listData?.items ?: return
        if (fromIndex == toIndex || fromIndex !in items.indices || toIndex !in items.indices) return
        val reordered = items.toMutableList().apply {
            add(toIndex, removeAt(fromIndex))
        }
        viewModelScope.launch {
            reorderListItemsUseCase(listId, reordered)
                .onSuccess { accumulateAndDebounce(ListDiff(reordered = true)) }
                .onFailure { e -> _uiState.value = _uiState.value.copy(error = e.message) }
        }
    }

    fun updateTitle(title: String) {
        viewModelScope.launch {
            updateListTitleUseCase(listId, title)
                .onSuccess { accumulateAndDebounce(ListDiff(titleChanged = title)) }
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

    override fun onCleared() {
        super.onCleared()
        debounceJob?.cancel()
    }
}
