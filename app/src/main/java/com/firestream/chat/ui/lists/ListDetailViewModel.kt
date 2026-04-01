package com.firestream.chat.ui.lists

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.firestream.chat.domain.model.Chat
import com.firestream.chat.domain.model.GenericListStyle
import com.firestream.chat.domain.model.ListData
import com.firestream.chat.domain.model.ListItem
import com.firestream.chat.domain.model.User
import com.firestream.chat.domain.repository.UserRepository
import com.firestream.chat.ui.chat.resolveChatParticipants
import com.firestream.chat.domain.model.ListDiff
import com.firestream.chat.domain.model.ListType
import com.firestream.chat.domain.repository.ChatRepository
import com.firestream.chat.domain.repository.ListRepository
import com.firestream.chat.data.remote.firebase.FirebaseAuthSource
import com.firestream.chat.domain.usecase.list.SendListUpdateToChatsUseCase
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.launch
import javax.inject.Inject

private const val DEBOUNCE_MS = 15_000L

data class ListDetailUiState(
    val listData: ListData? = null,
    val chats: List<Chat> = emptyList(),
    val chatParticipants: Map<String, User> = emptyMap(),
    val currentUserId: String = "",
    val isLoading: Boolean = true,
    val error: String? = null,
    val isDeleted: Boolean = false
) {
    val isOwner get() = listData?.createdBy == currentUserId
    val displayItems get() = listData?.items
        ?.let { items -> items.filter { !it.isChecked } + items.filter { it.isChecked } }
        ?: emptyList()
}

@HiltViewModel
class ListDetailViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    private val sendListUpdateToChatsUseCase: SendListUpdateToChatsUseCase,
    private val chatRepository: ChatRepository,
    private val listRepository: ListRepository,
    private val authSource: FirebaseAuthSource,
    private val userRepository: UserRepository
) : ViewModel() {

    val listId: String = checkNotNull(savedStateHandle["listId"])

    private val _uiState = MutableStateFlow(ListDetailUiState())
    val uiState: StateFlow<ListDetailUiState> = _uiState.asStateFlow()

    private var pendingDiff = ListDiff()
    private var pendingSharedChatIds: List<String> = emptyList()
    private var debounceJob: Job? = null
    // Prevents the Firestore observer from triggering navigation while we send the delete notification
    private var isDeletingList = false

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
                    val uid = _uiState.value.currentUserId
                    val newIds = chats.mapNotNull { it.participants.firstOrNull { id -> id != uid } }.toSet()
                    val participants = if (newIds == _uiState.value.chatParticipants.keys) {
                        _uiState.value.chatParticipants
                    } else {
                        chats.resolveChatParticipants(uid, userRepository)
                    }
                    _uiState.value = _uiState.value.copy(chats = chats, chatParticipants = participants)
                }
        }
    }

    private fun observeList() {
        viewModelScope.launch {
            listRepository.observeList(listId)
                .catch { e ->
                    _uiState.value = _uiState.value.copy(isLoading = false, error = e.message)
                }
                .collect { listData ->
                    if (listData == null && !_uiState.value.isLoading && !isDeletingList) {
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
        // Capture sharedChatIds now — don't rely on UI state at flush time (Firestore race)
        val currentIds = _uiState.value.listData?.sharedChatIds ?: emptyList()
        if (currentIds.isNotEmpty()) pendingSharedChatIds = currentIds
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
        val chatIds = pendingSharedChatIds.ifEmpty { listData.sharedChatIds }
        pendingSharedChatIds = emptyList()
        if (chatIds.isEmpty()) return

        sendListUpdateToChatsUseCase(listId, listData.title, chatIds, diff)
    }

    fun addItem(text: String, quantity: String? = null, unit: String? = null) {
        if (text.isBlank()) return
        viewModelScope.launch {
            listRepository.addItem(listId, text, quantity, unit)
                .onSuccess { accumulateAndDebounce(ListDiff(added = listOf(text))) }
                .onFailure { e -> _uiState.value = _uiState.value.copy(error = e.message) }
        }
    }

    fun removeItem(itemId: String) {
        val itemText = _uiState.value.listData?.items?.find { it.id == itemId }?.text ?: itemId
        viewModelScope.launch {
            listRepository.removeItem(listId, itemId)
                .onSuccess { accumulateAndDebounce(ListDiff(removed = listOf(itemText))) }
                .onFailure { e -> _uiState.value = _uiState.value.copy(error = e.message) }
        }
    }

    fun clearCheckedItems() {
        if (_uiState.value.listData?.items?.none { it.isChecked } == true) return
        viewModelScope.launch {
            listRepository.clearCheckedItems(listId)
                .onSuccess { clearedTexts ->
                    if (clearedTexts.isNotEmpty()) {
                        accumulateAndDebounce(ListDiff(removed = clearedTexts))
                    }
                }
                .onFailure { e -> _uiState.value = _uiState.value.copy(error = e.message) }
        }
    }

    fun toggleItem(itemId: String) {
        val currentList = _uiState.value.listData ?: return
        val item = currentList.items.find { it.id == itemId } ?: return

        // Optimistic update: toggle locally before Firestore round-trip
        val updatedItems = currentList.items.map {
            if (it.id == itemId) it.copy(isChecked = !it.isChecked) else it
        }
        _uiState.value = _uiState.value.copy(
            listData = currentList.copy(items = updatedItems)
        )

        viewModelScope.launch {
            listRepository.toggleItemChecked(listId, itemId)
                .onSuccess {
                    val diff = if (item.isChecked) {
                        ListDiff(unchecked = listOf(item.text))
                    } else {
                        ListDiff(checked = listOf(item.text))
                    }
                    accumulateAndDebounce(diff)
                }
                .onFailure { e ->
                    // Revert on failure
                    _uiState.value = _uiState.value.copy(
                        listData = currentList,
                        error = e.message
                    )
                }
        }
    }

    fun updateItem(itemId: String, text: String, quantity: String? = null, unit: String? = null) {
        viewModelScope.launch {
            listRepository.updateItem(listId, itemId, text, quantity, unit)
                .onSuccess { accumulateAndDebounce(ListDiff(edited = listOf(text))) }
                .onFailure { e -> _uiState.value = _uiState.value.copy(error = e.message) }
        }
    }

    fun reorderItems(reorderedItems: List<ListItem>) {
        viewModelScope.launch {
            listRepository.reorderItems(listId, reorderedItems)
                .onFailure { e -> _uiState.value = _uiState.value.copy(error = e.message) }
        }
    }

    fun updateTitle(title: String) {
        viewModelScope.launch {
            listRepository.updateListTitle(listId, title)
                .onSuccess { accumulateAndDebounce(ListDiff(titleChanged = title)) }
                .onFailure { e -> _uiState.value = _uiState.value.copy(error = e.message) }
        }
    }

    fun updateType(type: ListType) {
        viewModelScope.launch {
            listRepository.updateListType(listId, type)
                .onFailure { e -> _uiState.value = _uiState.value.copy(error = e.message) }
        }
    }

    fun updateGenericStyle(style: GenericListStyle) {
        viewModelScope.launch {
            listRepository.updateGenericStyle(listId, style)
                .onFailure { e -> _uiState.value = _uiState.value.copy(error = e.message) }
        }
    }

    fun shareToChat(chatId: String) {
        val currentList = _uiState.value.listData ?: return
        val optimisticIds = (currentList.sharedChatIds + chatId).distinct()
        _uiState.value = _uiState.value.copy(
            listData = currentList.copy(sharedChatIds = optimisticIds)
        )
        viewModelScope.launch {
            listRepository.shareListToChat(listId, chatId)
                .onFailure { e ->
                    // Only revert if observer hasn't already corrected the state
                    if (_uiState.value.listData?.sharedChatIds == optimisticIds) {
                        _uiState.value = _uiState.value.copy(listData = currentList)
                    }
                    _uiState.value = _uiState.value.copy(error = e.message)
                }
        }
    }

    fun unshareFromChat(chatId: String) {
        val currentList = _uiState.value.listData ?: return
        val optimisticIds = currentList.sharedChatIds.filter { it != chatId }
        _uiState.value = _uiState.value.copy(
            listData = currentList.copy(sharedChatIds = optimisticIds)
        )
        viewModelScope.launch {
            listRepository.unshareListFromChat(listId, chatId)
                .onFailure { e ->
                    // Only revert if observer hasn't already corrected the state
                    if (_uiState.value.listData?.sharedChatIds == optimisticIds) {
                        _uiState.value = _uiState.value.copy(listData = currentList)
                    }
                    _uiState.value = _uiState.value.copy(error = e.message)
                }
        }
    }

    fun deleteList() {
        val listData = _uiState.value.listData ?: return
        isDeletingList = true
        viewModelScope.launch {
            debounceJob?.cancel()
            flushPendingDiff()

            listRepository.deleteList(listId)
                .onSuccess {
                    // Send notification before setting isDeleted — viewModelScope must stay alive
                    if (listData.sharedChatIds.isNotEmpty()) {
                        sendListUpdateToChatsUseCase(
                            listId, listData.title, listData.sharedChatIds,
                            ListDiff(deleted = true)
                        )
                    }
                    isDeletingList = false
                    _uiState.value = _uiState.value.copy(isDeleted = true)
                }
                .onFailure { e ->
                    isDeletingList = false
                    _uiState.value = _uiState.value.copy(error = e.message)
                }
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
