package com.firestream.chat.ui.lists

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.firestream.chat.data.local.PreferencesDataStore
import com.firestream.chat.domain.model.Chat
import com.firestream.chat.domain.model.GenericListStyle
import com.firestream.chat.domain.model.ListData
import com.firestream.chat.domain.model.ListHistoryEntry
import com.firestream.chat.domain.model.ListType
import com.firestream.chat.domain.model.User
import com.firestream.chat.domain.model.ListDiff
import com.firestream.chat.domain.repository.AuthRepository
import com.firestream.chat.domain.repository.ChatRepository
import com.firestream.chat.domain.repository.ListRepository
import com.firestream.chat.domain.repository.UserRepository
import com.firestream.chat.domain.usecase.list.SendListUpdateToChatsUseCase
import dagger.hilt.android.lifecycle.HiltViewModel
import com.firestream.chat.ui.chat.resolveChatParticipants
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch
import javax.inject.Inject

enum class ListSortOption(val displayName: String) {
    TYPE("By type"),
    CREATED("Date created"),
    MODIFIED("Last modified"),
    CREATOR("By creator")
}

data class ListsUiState(
    val lists: List<ListData> = emptyList(),
    val chats: List<Chat> = emptyList(),
    val chatParticipants: Map<String, User> = emptyMap(),
    val currentUserId: String = "",
    val selectedListHistory: List<ListHistoryEntry> = emptyList(),
    val participantAvatars: Map<String, List<User>> = emptyMap(),
    val sortOption: ListSortOption = ListSortOption.MODIFIED,
    val pinnedListIds: Set<String> = emptySet(),
    val isLoading: Boolean = true,
    val isRefreshing: Boolean = false,
    val error: String? = null,
    val snackbarMessage: String? = null,
    val searchQuery: String = "",
    val isSearchBarVisible: Boolean = false
) {
    fun isOwner(list: ListData) = list.createdBy == currentUserId
    fun isPinned(list: ListData) = list.id in pinnedListIds
}

@HiltViewModel
class ListsViewModel @Inject constructor(
    private val listRepository: ListRepository,
    private val chatRepository: ChatRepository,
    private val authRepository: AuthRepository,
    private val userRepository: UserRepository,
    private val preferencesDataStore: PreferencesDataStore,
    private val sendListUpdateToChatsUseCase: SendListUpdateToChatsUseCase
) : ViewModel() {

    private val _uiState = MutableStateFlow(ListsUiState())
    private var historyJob: Job? = null
    val uiState: StateFlow<ListsUiState> = _uiState.asStateFlow()

    // Raw unsorted lists — kept separately so sort changes re-apply immediately
    private var rawLists: List<ListData> = emptyList()

    init {
        _uiState.value = _uiState.value.copy(currentUserId = authRepository.currentUserId ?: "")
        observeSortOption()
        observePinnedListIds()
        observeLists()
        observeChats()
    }

    fun refresh() {
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(isRefreshing = true)
            kotlinx.coroutines.delay(500)
            // Re-trigger list observation — the Flow will emit the latest data
            observeLists()
            _uiState.value = _uiState.value.copy(isRefreshing = false)
        }
    }

    private fun observePinnedListIds() {
        preferencesDataStore.pinnedListIdsFlow
            .onEach { pinned ->
                _uiState.value = _uiState.value.copy(
                    pinnedListIds = pinned,
                    lists = filteredAndSorted(rawLists, _uiState.value.sortOption, _uiState.value.searchQuery, pinned)
                )
            }
            .launchIn(viewModelScope)
    }

    fun togglePin(listId: String) {
        viewModelScope.launch {
            val current = _uiState.value.pinnedListIds
            val updated = if (listId in current) current - listId else current + listId
            preferencesDataStore.setPinnedListIds(updated)
        }
    }

    private fun observeSortOption() {
        preferencesDataStore.listSortOptionFlow
            .onEach { raw ->
                val option = runCatching { ListSortOption.valueOf(raw) }.getOrDefault(ListSortOption.MODIFIED)
                if (option == _uiState.value.sortOption) return@onEach
                _uiState.value = _uiState.value.copy(
                    sortOption = option,
                    lists = filteredAndSorted(rawLists, option, _uiState.value.searchQuery)
                )
            }
            .launchIn(viewModelScope)
    }

    private fun sortedLists(lists: List<ListData>, option: ListSortOption, pinned: Set<String>): List<ListData> {
        val sorted = when (option) {
            ListSortOption.TYPE -> lists.sortedWith(compareBy({ it.type.ordinal }, { it.title }))
            ListSortOption.CREATED -> lists.sortedByDescending { it.createdAt }
            ListSortOption.MODIFIED -> lists.sortedByDescending { it.updatedAt }
            ListSortOption.CREATOR -> lists.sortedWith(compareBy({ it.createdBy }, { it.title }))
        }
        return sorted.sortedWith(compareByDescending { it.id in pinned })
    }

    private fun filteredAndSorted(lists: List<ListData>, option: ListSortOption, query: String, pinned: Set<String> = _uiState.value.pinnedListIds): List<ListData> {
        val base = if (query.isBlank()) lists else lists.filter { list ->
            list.title.contains(query, ignoreCase = true) ||
                list.items.any { it.text.contains(query, ignoreCase = true) }
        }
        return sortedLists(base, option, pinned)
    }

    fun setSortOption(option: ListSortOption) {
        viewModelScope.launch {
            preferencesDataStore.setListSortOption(option.name)
        }
    }

    fun toggleSearchBar() {
        val nowVisible = !_uiState.value.isSearchBarVisible
        _uiState.value = _uiState.value.copy(
            isSearchBarVisible = nowVisible,
            searchQuery = if (!nowVisible) "" else _uiState.value.searchQuery,
            lists = if (!nowVisible) filteredAndSorted(rawLists, _uiState.value.sortOption, "") else _uiState.value.lists
        )
    }

    fun onSearchQueryChange(query: String) {
        _uiState.value = _uiState.value.copy(
            searchQuery = query,
            lists = filteredAndSorted(rawLists, _uiState.value.sortOption, query)
        )
    }

    fun clearSearch() {
        _uiState.value = _uiState.value.copy(
            searchQuery = "",
            lists = filteredAndSorted(rawLists, _uiState.value.sortOption, "")
        )
    }

    // Individual document listeners for live unshare detection — direct document
    // listeners fire much faster than the compound observeMyLists query propagates.
    private val observedListIds = mutableSetOf<String>()

    private fun observeLists() {
        viewModelScope.launch {
            listRepository.observeMyLists()
                .catch { e ->
                    _uiState.value = _uiState.value.copy(isLoading = false, error = e.message)
                }
                .collect { lists ->
                    rawLists = lists
                    _uiState.value = _uiState.value.copy(
                        lists = filteredAndSorted(lists, _uiState.value.sortOption, _uiState.value.searchQuery),
                        isLoading = false
                    )
                    resolveParticipants(lists)
                    ensureListDocumentObservers(lists)
                }
        }
    }

    private fun ensureListDocumentObservers(lists: List<ListData>) {
        lists.forEach { list ->
            if (list.id !in observedListIds) {
                observedListIds.add(list.id)
                viewModelScope.launch {
                    // Subscribe to the document — observeList's non-participant
                    // check deletes from Room, which triggers getListsForUser to re-emit.
                    listRepository.observeList(list.id).catch { }.collect { }
                }
            }
        }
    }

    private fun resolveParticipants(lists: List<ListData>) {
        val currentUserId = _uiState.value.currentUserId
        viewModelScope.launch {
            val avatarMap = lists.associate { list ->
                val otherIds = list.participants.filter { it != currentUserId }.take(3)
                val users = otherIds
                    .map { id -> async { userRepository.getUserById(id).getOrNull() } }
                    .awaitAll()
                    .filterNotNull()
                list.id to users
            }
            _uiState.value = _uiState.value.copy(participantAvatars = avatarMap)
        }
    }

    private fun observeChats() {
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

    fun createList(title: String, type: ListType, genericStyle: GenericListStyle = GenericListStyle.BULLET, onCreated: (String) -> Unit) {
        viewModelScope.launch {
            listRepository.createList(title, type, genericStyle = genericStyle)
                .onSuccess { listData -> onCreated(listData.id) }
                .onFailure { e -> _uiState.value = _uiState.value.copy(error = e.message) }
        }
    }

    fun shareListToChat(listId: String, chatId: String) {
        viewModelScope.launch {
            listRepository.shareListToChat(listId, chatId)
                .onFailure { e -> _uiState.value = _uiState.value.copy(error = e.message) }
        }
    }

    fun deleteList(listId: String) {
        val listData = rawLists.find { it.id == listId } ?: return
        viewModelScope.launch {
            listRepository.deleteList(listId)
                .onSuccess {
                    if (listData.sharedChatIds.isNotEmpty()) {
                        sendListUpdateToChatsUseCase(
                            listId, listData.title, listData.sharedChatIds,
                            ListDiff(deleted = true)
                        )
                    }
                    _uiState.value = _uiState.value.copy(
                        snackbarMessage = "\"${listData.title}\" deleted"
                    )
                }
                .onFailure { e -> _uiState.value = _uiState.value.copy(error = e.message) }
        }
    }

    fun showDeletedSnackbar(title: String) {
        _uiState.value = _uiState.value.copy(snackbarMessage = "\"$title\" deleted")
    }

    fun clearSnackbar() {
        _uiState.value = _uiState.value.copy(snackbarMessage = null)
    }

    fun renameList(listId: String, title: String) {
        viewModelScope.launch {
            listRepository.updateListTitle(listId, title)
                .onFailure { e -> _uiState.value = _uiState.value.copy(error = e.message) }
        }
    }

    fun loadHistory(listId: String) {
        historyJob?.cancel()
        historyJob = viewModelScope.launch {
            listRepository.observeHistory(listId)
                .catch { }
                .collect { history ->
                    _uiState.value = _uiState.value.copy(selectedListHistory = history)
                }
        }
    }

    fun clearSelectedList() {
        historyJob?.cancel()
        historyJob = null
        _uiState.value = _uiState.value.copy(selectedListHistory = emptyList())
    }

    fun clearError() {
        _uiState.value = _uiState.value.copy(error = null)
    }
}
