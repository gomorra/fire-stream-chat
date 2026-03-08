package com.firestream.chat.ui.chatlist

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.firestream.chat.domain.model.Chat
import com.firestream.chat.domain.model.Message
import com.firestream.chat.domain.repository.AuthRepository
import com.firestream.chat.domain.repository.MessageRepository
import com.firestream.chat.domain.usecase.chat.ArchiveChatUseCase
import com.firestream.chat.domain.usecase.chat.DeleteChatUseCase
import com.firestream.chat.domain.usecase.chat.GetChatsUseCase
import com.firestream.chat.domain.usecase.chat.MuteChatUseCase
import com.firestream.chat.domain.usecase.chat.PinChatUseCase
import com.firestream.chat.domain.usecase.message.SearchMessagesUseCase
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.launch
import javax.inject.Inject

data class ChatListUiState(
    val chats: List<Chat> = emptyList(),
    val isLoading: Boolean = true,
    val error: String? = null,
    val currentUserId: String = "",
    val pendingDeleteChatId: String? = null,
    // Phase 2: search
    val searchQuery: String = "",
    val searchResults: List<Message> = emptyList(),
    val isSearchActive: Boolean = false,
    // Phase 2: archived section toggle
    val showArchived: Boolean = false,
    // Phase 2: mute dialog
    val pendingMuteChatId: String? = null
)

@HiltViewModel
class ChatListViewModel @Inject constructor(
    private val getChatsUseCase: GetChatsUseCase,
    private val deleteChatUseCase: DeleteChatUseCase,
    private val pinChatUseCase: PinChatUseCase,
    private val archiveChatUseCase: ArchiveChatUseCase,
    private val muteChatUseCase: MuteChatUseCase,
    private val searchMessagesUseCase: SearchMessagesUseCase,
    private val authRepository: AuthRepository,
    private val messageRepository: MessageRepository
) : ViewModel() {

    private val _uiState = MutableStateFlow(ChatListUiState())
    val uiState: StateFlow<ChatListUiState> = _uiState.asStateFlow()

    private var searchJob: Job? = null

    init {
        _uiState.value = _uiState.value.copy(currentUserId = authRepository.currentUserId ?: "")
        loadChats()
    }

    private val deliveredTimestamps = mutableMapOf<String, Long>()

    private fun loadChats() {
        viewModelScope.launch {
            getChatsUseCase()
                .catch { e ->
                    _uiState.value = _uiState.value.copy(
                        isLoading = false,
                        error = e.message
                    )
                }
                .collect { chats ->
                    _uiState.value = _uiState.value.copy(
                        chats = chats,
                        isLoading = false
                    )
                    // Mark undelivered messages as DELIVERED for all chats
                    markAllChatsAsDelivered(chats)
                }
        }
    }

    private fun markAllChatsAsDelivered(chats: List<Chat>) {
        val currentUserId = _uiState.value.currentUserId
        if (currentUserId.isEmpty()) return
        for (chat in chats) {
            val lastMsg = chat.lastMessage ?: continue
            if (lastMsg.senderId == currentUserId) continue
            // Skip if we already processed this chat for this timestamp
            val lastProcessed = deliveredTimestamps[chat.id]
            if (lastProcessed != null && lastProcessed >= lastMsg.timestamp) continue
            deliveredTimestamps[chat.id] = lastMsg.timestamp
            viewModelScope.launch {
                messageRepository.markChatAsDelivered(chat.id)
            }
        }
    }

    fun requestDeleteChat(chatId: String) {
        _uiState.value = _uiState.value.copy(pendingDeleteChatId = chatId)
    }

    fun cancelDeleteChat() {
        _uiState.value = _uiState.value.copy(pendingDeleteChatId = null)
    }

    fun confirmDeleteChat() {
        val chatId = _uiState.value.pendingDeleteChatId ?: return
        _uiState.value = _uiState.value.copy(pendingDeleteChatId = null)
        viewModelScope.launch {
            deleteChatUseCase(chatId)
        }
    }

    // Phase 2: pin — max 3 pinned chats
    fun togglePin(chatId: String, currentlyPinned: Boolean) {
        val pinnedCount = _uiState.value.chats.count { it.isPinned && !it.isArchived }
        if (!currentlyPinned && pinnedCount >= 3) {
            _uiState.value = _uiState.value.copy(error = "You can pin up to 3 chats")
            return
        }
        viewModelScope.launch {
            pinChatUseCase(chatId, !currentlyPinned)
        }
    }

    // Phase 2: archive
    fun toggleArchive(chatId: String, currentlyArchived: Boolean) {
        viewModelScope.launch {
            archiveChatUseCase(chatId, !currentlyArchived)
        }
    }

    // Phase 2: mute
    fun requestMuteChat(chatId: String) {
        _uiState.value = _uiState.value.copy(pendingMuteChatId = chatId)
    }

    fun cancelMuteChat() {
        _uiState.value = _uiState.value.copy(pendingMuteChatId = null)
    }

    fun confirmMuteChat(muteUntil: Long) {
        val chatId = _uiState.value.pendingMuteChatId ?: return
        _uiState.value = _uiState.value.copy(pendingMuteChatId = null)
        viewModelScope.launch {
            muteChatUseCase(chatId, muteUntil)
        }
    }

    // Phase 2: archived section toggle
    fun toggleShowArchived() {
        _uiState.value = _uiState.value.copy(showArchived = !_uiState.value.showArchived)
    }

    // Phase 2: global search with 300 ms debounce
    fun onSearchQueryChange(query: String) {
        _uiState.value = _uiState.value.copy(searchQuery = query, isSearchActive = query.isNotEmpty())
        searchJob?.cancel()
        if (query.isBlank()) {
            _uiState.value = _uiState.value.copy(searchResults = emptyList())
            return
        }
        searchJob = viewModelScope.launch {
            delay(300)
            val results = searchMessagesUseCase(query)
            _uiState.value = _uiState.value.copy(searchResults = results)
        }
    }

    fun clearSearch() {
        searchJob?.cancel()
        _uiState.value = _uiState.value.copy(
            searchQuery = "",
            searchResults = emptyList(),
            isSearchActive = false
        )
    }

    fun clearError() {
        _uiState.value = _uiState.value.copy(error = null)
    }
}
