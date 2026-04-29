package com.firestream.chat.ui.lists

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.firestream.chat.domain.model.AppError
import com.firestream.chat.domain.model.Chat
import com.firestream.chat.domain.model.GenericListStyle
import com.firestream.chat.domain.model.ListData
import com.firestream.chat.domain.model.ListItem
import com.firestream.chat.domain.model.User
import com.firestream.chat.domain.repository.AuthRepository
import com.firestream.chat.domain.repository.UserRepository
import com.firestream.chat.ui.chat.resolveChatParticipants
import com.firestream.chat.domain.model.ListDiff
import com.firestream.chat.domain.model.ListType
import com.firestream.chat.domain.repository.ChatRepository
import com.firestream.chat.domain.repository.ListRepository
import com.firestream.chat.data.local.PreferencesDataStore
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

private const val COOLDOWN_MS = 10_000L
private const val UNDO_WINDOW_MS = 4_000L

data class PendingRemoval(val item: ListItem, val previousList: ListData)

data class ListDetailUiState(
    val listData: ListData? = null,
    val chats: List<Chat> = emptyList(),
    val chatParticipants: Map<String, User> = emptyMap(),
    val currentUserId: String = "",
    val isLoading: Boolean = true,
    val error: AppError? = null,
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
    private val authRepository: AuthRepository,
    private val userRepository: UserRepository,
    private val preferencesDataStore: PreferencesDataStore,
    @ApplicationScope private val applicationScope: CoroutineScope,
) : ViewModel() {

    val listId: String = checkNotNull(savedStateHandle["listId"])

    private val _uiState = MutableStateFlow(ListDetailUiState())
    val uiState: StateFlow<ListDetailUiState> = _uiState.asStateFlow()

    private var cooldownJob: Job? = null
    // Prevents the Firestore observer from triggering navigation while we send the delete notification
    private var isDeletingList = false

    // Pending swipe-to-delete: optimistic UI hide is applied immediately, but
    // the Firestore commit is deferred so the user can Undo. The timer + commit
    // run on applicationScope so they survive screen navigation — otherwise the
    // delete is silently lost when the user leaves before the snackbar expires.
    private val _pendingRemoval = MutableStateFlow<PendingRemoval?>(null)
    val pendingRemoval: StateFlow<PendingRemoval?> = _pendingRemoval.asStateFlow()
    private var pendingRemovalJob: Job? = null

    init {
        _uiState.value = _uiState.value.copy(currentUserId = authRepository.currentUserId ?: "")
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
            var hasSeenData = false
            listRepository.observeList(listId)
                .catch { e ->
                    _uiState.value = _uiState.value.copy(isLoading = false, error = AppError.from(e))
                }
                .collect { listData ->
                    val state = _uiState.value
                    when {
                        listData == null && hasSeenData && !isDeletingList -> {
                            // Transitioned loaded → null: list deleted or access revoked.
                            val wasOwner = state.listData?.createdBy == state.currentUserId
                            _uiState.value = if (wasOwner) {
                                state.copy(isDeleted = true, isLoading = false)
                            } else {
                                state.copy(isAccessDenied = true, isLoading = false)
                            }
                        }
                        listData == null -> {
                            // Freshly shared list: Room is empty on first open and the
                            // Firestore listener's initial miss would otherwise look like
                            // a deletion. Stay on the spinner until real data arrives.
                        }
                        listData.participants.isNotEmpty()
                            && state.currentUserId !in listData.participants
                            && state.currentUserId != listData.createdBy -> {
                            _uiState.value = state.copy(isAccessDenied = true, isLoading = false)
                        }
                        else -> {
                            hasSeenData = true
                            _uiState.value = state.copy(listData = listData, isLoading = false)
                        }
                    }
                }
        }
    }

    private fun sendListUpdateThrottled(diff: ListDiff) {
        val listData = _uiState.value.listData ?: return
        val chatIds = listData.sharedChatIds
        if (chatIds.isEmpty()) return

        val inCooldown = cooldownJob?.isActive == true

        // Reset the cooldown on every update — the next bubble only fires
        // after the user has been quiet for a full window.
        cooldownJob?.cancel()
        cooldownJob = viewModelScope.launch { delay(COOLDOWN_MS) }

        if (inCooldown) return

        // Leading edge: send immediately on the application scope so the
        // write survives if the user navigates away before it completes.
        applicationScope.launch {
            sendListUpdateToChatsUseCase(listId, listData.title, chatIds, diff)
        }
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
            order = (currentList.items.maxOfOrNull { it.order } ?: -1) + 1,
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
                .onSuccess { sendListUpdateThrottled(ListDiff(added = listOf(text))) }
                .onFailure { e ->
                    _uiState.value = _uiState.value.copy(listData = currentList, error = AppError.from(e))
                }
        }
    }

    fun requestRemoveItem(itemId: String) {
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
        _pendingRemoval.value = PendingRemoval(item, currentList)
        pendingRemovalJob?.cancel()
        pendingRemovalJob = applicationScope.launch {
            delay(UNDO_WINDOW_MS)
            commitRemoval(item, currentList)
        }
    }

    fun undoRemoveItem() {
        pendingRemovalJob?.cancel()
        val pending = _pendingRemoval.value ?: return
        _uiState.value = _uiState.value.copy(listData = pending.previousList)
        _pendingRemoval.value = null
    }

    private suspend fun commitRemoval(item: ListItem, previousList: ListData) {
        listRepository.removeItem(listId, item.id)
            .onSuccess { sendListUpdateThrottled(ListDiff(removed = listOf(item.text))) }
            .onFailure { e ->
                _uiState.value = _uiState.value.copy(listData = previousList, error = AppError.from(e))
            }
        _pendingRemoval.value = null
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
                        sendListUpdateThrottled(ListDiff(removed = texts))
                    }
                }
                .onFailure { e ->
                    // Revert on failure
                    _uiState.value = _uiState.value.copy(
                        listData = currentList,
                        error = AppError.from(e)
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
                    sendListUpdateThrottled(diff)
                }
                .onFailure { e ->
                    _uiState.value = _uiState.value.copy(
                        listData = currentList,
                        error = AppError.from(e)
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
                .onSuccess { sendListUpdateThrottled(ListDiff(edited = listOf(text))) }
                .onFailure { e ->
                    _uiState.value = _uiState.value.copy(listData = currentList, error = AppError.from(e))
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
                    _uiState.value = _uiState.value.copy(listData = currentList, error = AppError.from(e))
                }
        }
    }

    fun updateTitle(title: String) {
        viewModelScope.launch {
            listRepository.updateListTitle(listId, title)
                .onSuccess { sendListUpdateThrottled(ListDiff(titleChanged = title)) }
                .onFailure { e -> _uiState.value = _uiState.value.copy(error = AppError.from(e)) }
        }
    }

    fun updateType(type: ListType) {
        viewModelScope.launch {
            listRepository.updateListType(listId, type)
                .onFailure { e -> _uiState.value = _uiState.value.copy(error = AppError.from(e)) }
        }
    }

    fun updateGenericStyle(style: GenericListStyle) {
        viewModelScope.launch {
            listRepository.updateGenericStyle(listId, style)
                .onFailure { e -> _uiState.value = _uiState.value.copy(error = AppError.from(e)) }
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
                    _uiState.value = _uiState.value.copy(error = AppError.from(e))
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
                    _uiState.value = _uiState.value.copy(error = AppError.from(e))
                }
        }
    }

    fun deleteList() {
        val listData = _uiState.value.listData ?: return
        isDeletingList = true
        viewModelScope.launch {
            cooldownJob?.cancel()

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
                    _uiState.value = _uiState.value.copy(error = AppError.from(e))
                }
        }
    }

    fun clearError() {
        _uiState.value = _uiState.value.copy(error = null)
    }

    // Persist on @ApplicationScope so the write lands even if the user navigates
    // away before viewModelScope would complete.
    fun persistOpen() {
        applicationScope.launch { preferencesDataStore.setLastOpenListId(listId) }
    }

    fun clearOpen() {
        applicationScope.launch { preferencesDataStore.clearLastOpenListId() }
    }

}
