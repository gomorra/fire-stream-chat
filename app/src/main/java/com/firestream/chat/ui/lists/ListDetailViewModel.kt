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
import com.firestream.chat.di.ApplicationScope
import com.firestream.chat.domain.usecase.list.SendListUpdateToChatsUseCase
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.launch
import java.util.UUID
import javax.inject.Inject

private const val DEBOUNCE_MS = 10_000L

data class ListDetailUiState(
    val listData: ListData? = null,
    val chats: List<Chat> = emptyList(),
    val chatParticipants: Map<String, User> = emptyMap(),
    val currentUserId: String = "",
    val isLoading: Boolean = true,
    val error: String? = null,
    val isDeleted: Boolean = false,
    val deletedListTitle: String? = null,
    val isAccessDenied: Boolean = false
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
    private val userRepository: UserRepository,
    @ApplicationScope private val applicationScope: CoroutineScope,
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
                    val state = _uiState.value
                    if (listData == null && !state.isLoading && !isDeletingList) {
                        // If the current user is not the owner of the last-known list,
                        // a null emission means they lost access (removed from participants),
                        // not that the list was deleted. Show the appropriate message.
                        val wasOwner = state.listData?.createdBy == state.currentUserId
                            || state.listData == null  // no prior data → default to deleted path
                        if (wasOwner) {
                            _uiState.value = state.copy(isDeleted = true, isLoading = false)
                        } else {
                            _uiState.value = state.copy(isAccessDenied = true, isLoading = false)
                        }
                    } else if (listData != null
                        && listData.participants.isNotEmpty()
                        && state.currentUserId !in listData.participants
                        && state.currentUserId != listData.createdBy
                    ) {
                        // Firestore delivered the updated doc before Room removed it:
                        // participant check fires while the document is still visible.
                        _uiState.value = state.copy(isAccessDenied = true, isLoading = false)
                    } else {
                        _uiState.value = state.copy(
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
        val currentList = _uiState.value.listData ?: return
        val itemId = UUID.randomUUID().toString()
        val newItem = ListItem(
            id = itemId,
            text = text,
            quantity = quantity,
            unit = unit,
            order = currentList.items.size,
            addedBy = _uiState.value.currentUserId
        )
        // Optimistic insert: append locally so the user sees it immediately. The id matches
        // the one passed to the repo, so the Firestore listener's echo reconciles in place.
        _uiState.value = _uiState.value.copy(
            listData = currentList.copy(
                items = currentList.items + newItem,
                itemCount = currentList.itemCount + 1
            )
        )
        viewModelScope.launch {
            listRepository.addItem(listId, itemId, text, quantity, unit)
                .onSuccess { accumulateAndDebounce(ListDiff(added = listOf(text))) }
                .onFailure { e ->
                    _uiState.value = _uiState.value.copy(listData = currentList, error = e.message)
                }
        }
    }

    fun removeItem(itemId: String) {
        val currentList = _uiState.value.listData ?: return
        val item = currentList.items.find { it.id == itemId } ?: return
        val remaining = currentList.items.filter { it.id != itemId }
        _uiState.value = _uiState.value.copy(
            listData = currentList.copy(
                items = remaining,
                itemCount = (currentList.itemCount - 1).coerceAtLeast(0),
                checkedCount = (currentList.checkedCount - if (item.isChecked) 1 else 0).coerceAtLeast(0)
            )
        )
        viewModelScope.launch {
            listRepository.removeItem(listId, itemId)
                .onSuccess { accumulateAndDebounce(ListDiff(removed = listOf(item.text))) }
                .onFailure { e ->
                    _uiState.value = _uiState.value.copy(listData = currentList, error = e.message)
                }
        }
    }

    fun clearCheckedItems() {
        val currentList = _uiState.value.listData ?: return
        val checkedItems = currentList.items.filter { it.isChecked }
        if (checkedItems.isEmpty()) return

        // Optimistic update: drop checked items locally before Firestore round-trip
        // so the UI reflects the change immediately.
        val remaining = currentList.items.filter { !it.isChecked }
        _uiState.value = _uiState.value.copy(
            listData = currentList.copy(
                items = remaining,
                itemCount = (currentList.itemCount - checkedItems.size).coerceAtLeast(0),
                checkedCount = 0
            )
        )

        viewModelScope.launch {
            listRepository.clearCheckedItems(listId)
                .onSuccess { clearedTexts ->
                    val texts = clearedTexts.ifEmpty { checkedItems.map { it.text } }
                    if (texts.isNotEmpty()) {
                        accumulateAndDebounce(ListDiff(removed = texts))
                    }
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

    fun toggleItem(itemId: String) {
        val currentList = _uiState.value.listData ?: return
        val item = currentList.items.find { it.id == itemId } ?: return
        val target = !item.isChecked

        val updatedItems = currentList.items.map {
            if (it.id == itemId) it.copy(isChecked = target) else it
        }
        _uiState.value = _uiState.value.copy(
            listData = currentList.copy(
                items = updatedItems,
                checkedCount = (currentList.checkedCount + if (target) 1 else -1)
                    .coerceIn(0, currentList.itemCount)
            )
        )

        viewModelScope.launch {
            listRepository.toggleItemChecked(listId, itemId, target)
                .onSuccess {
                    val diff = if (target) {
                        ListDiff(checked = listOf(item.text))
                    } else {
                        ListDiff(unchecked = listOf(item.text))
                    }
                    accumulateAndDebounce(diff)
                }
                .onFailure { e ->
                    _uiState.value = _uiState.value.copy(
                        listData = currentList,
                        error = e.message
                    )
                }
        }
    }

    fun updateItem(itemId: String, text: String, quantity: String? = null, unit: String? = null) {
        val currentList = _uiState.value.listData ?: return
        val updatedItems = currentList.items.map {
            if (it.id == itemId) it.copy(text = text, quantity = quantity, unit = unit) else it
        }
        _uiState.value = _uiState.value.copy(
            listData = currentList.copy(items = updatedItems)
        )
        viewModelScope.launch {
            listRepository.updateItem(listId, itemId, text, quantity, unit)
                .onSuccess { accumulateAndDebounce(ListDiff(edited = listOf(text))) }
                .onFailure { e ->
                    _uiState.value = _uiState.value.copy(listData = currentList, error = e.message)
                }
        }
    }

    fun reorderItems(reorderedItems: List<ListItem>) {
        val currentList = _uiState.value.listData ?: return
        _uiState.value = _uiState.value.copy(
            listData = currentList.copy(items = reorderedItems)
        )
        viewModelScope.launch {
            listRepository.reorderItems(listId, reorderedItems)
                .onFailure { e ->
                    _uiState.value = _uiState.value.copy(listData = currentList, error = e.message)
                }
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
                    _uiState.value = _uiState.value.copy(
                        isDeleted = true,
                        deletedListTitle = listData.title
                    )
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
        // Cancel the in-flight debounce — its viewModelScope dies with us anyway —
        // and re-dispatch the pending flush on the application scope so a user
        // who navigates back within the debounce window still gets their chat
        // bubble update delivered instead of silently dropped.
        debounceJob?.cancel()
        if (pendingDiff.isEmpty) return
        applicationScope.launch { flushPendingDiff() }
    }
}
