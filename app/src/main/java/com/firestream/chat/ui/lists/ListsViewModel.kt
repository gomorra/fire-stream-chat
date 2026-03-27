package com.firestream.chat.ui.lists

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.firestream.chat.data.local.PreferencesDataStore
import com.firestream.chat.data.remote.firebase.FirebaseAuthSource
import com.firestream.chat.domain.model.Chat
import com.firestream.chat.domain.model.GenericListStyle
import com.firestream.chat.domain.model.ListData
import com.firestream.chat.domain.model.ListHistoryEntry
import com.firestream.chat.domain.model.ListType
import com.firestream.chat.domain.model.User
import com.firestream.chat.domain.repository.ChatRepository
import com.firestream.chat.domain.repository.UserRepository
import com.firestream.chat.domain.usecase.list.CreateListUseCase
import com.firestream.chat.domain.usecase.list.DeleteListUseCase
import com.firestream.chat.domain.usecase.list.ObserveListHistoryUseCase
import com.firestream.chat.domain.usecase.list.ObserveMyListsUseCase
import com.firestream.chat.domain.usecase.list.ShareListToChatUseCase
import com.firestream.chat.domain.usecase.list.UpdateListTitleUseCase
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
    val isLoading: Boolean = true,
    val error: String? = null
)

@HiltViewModel
class ListsViewModel @Inject constructor(
    private val observeMyListsUseCase: ObserveMyListsUseCase,
    private val createListUseCase: CreateListUseCase,
    private val shareListToChatUseCase: ShareListToChatUseCase,
    private val deleteListUseCase: DeleteListUseCase,
    private val updateListTitleUseCase: UpdateListTitleUseCase,
    private val observeListHistoryUseCase: ObserveListHistoryUseCase,
    private val chatRepository: ChatRepository,
    private val authSource: FirebaseAuthSource,
    private val userRepository: UserRepository,
    private val preferencesDataStore: PreferencesDataStore
) : ViewModel() {

    private val _uiState = MutableStateFlow(ListsUiState())
    private var historyJob: Job? = null
    val uiState: StateFlow<ListsUiState> = _uiState.asStateFlow()

    // Raw unsorted lists — kept separately so sort changes re-apply immediately
    private var rawLists: List<ListData> = emptyList()

    init {
        _uiState.value = _uiState.value.copy(currentUserId = authSource.currentUserId ?: "")
        observeSortOption()
        observeLists()
        observeChats()
    }

    private fun observeSortOption() {
        preferencesDataStore.listSortOptionFlow
            .onEach { raw ->
                val option = runCatching { ListSortOption.valueOf(raw) }.getOrDefault(ListSortOption.MODIFIED)
                if (option == _uiState.value.sortOption) return@onEach
                _uiState.value = _uiState.value.copy(
                    sortOption = option,
                    lists = sortedLists(rawLists, option)
                )
            }
            .launchIn(viewModelScope)
    }

    private fun sortedLists(lists: List<ListData>, option: ListSortOption): List<ListData> = when (option) {
        ListSortOption.TYPE -> lists.sortedWith(compareBy({ it.type.ordinal }, { it.title }))
        ListSortOption.CREATED -> lists.sortedByDescending { it.createdAt }
        ListSortOption.MODIFIED -> lists.sortedByDescending { it.updatedAt }
        ListSortOption.CREATOR -> lists.sortedWith(compareBy({ it.createdBy }, { it.title }))
    }

    fun setSortOption(option: ListSortOption) {
        viewModelScope.launch {
            preferencesDataStore.setListSortOption(option.name)
        }
    }

    private fun observeLists() {
        viewModelScope.launch {
            observeMyListsUseCase()
                .catch { e ->
                    _uiState.value = _uiState.value.copy(isLoading = false, error = e.message)
                }
                .collect { lists ->
                    rawLists = lists
                    _uiState.value = _uiState.value.copy(
                        lists = sortedLists(lists, _uiState.value.sortOption),
                        isLoading = false
                    )
                    resolveParticipants(lists)
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
            createListUseCase(title, type, genericStyle = genericStyle)
                .onSuccess { listData -> onCreated(listData.id) }
                .onFailure { e -> _uiState.value = _uiState.value.copy(error = e.message) }
        }
    }

    fun shareListToChat(listId: String, chatId: String) {
        viewModelScope.launch {
            shareListToChatUseCase(listId, chatId)
                .onFailure { e -> _uiState.value = _uiState.value.copy(error = e.message) }
        }
    }

    fun deleteList(listId: String) {
        viewModelScope.launch {
            deleteListUseCase(listId)
                .onFailure { e -> _uiState.value = _uiState.value.copy(error = e.message) }
        }
    }

    fun renameList(listId: String, title: String) {
        viewModelScope.launch {
            updateListTitleUseCase(listId, title)
                .onFailure { e -> _uiState.value = _uiState.value.copy(error = e.message) }
        }
    }

    fun loadHistory(listId: String) {
        historyJob?.cancel()
        historyJob = viewModelScope.launch {
            observeListHistoryUseCase(listId)
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
