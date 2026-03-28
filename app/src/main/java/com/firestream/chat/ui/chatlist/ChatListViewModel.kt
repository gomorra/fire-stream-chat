package com.firestream.chat.ui.chatlist

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.firestream.chat.domain.model.Chat
import com.firestream.chat.domain.model.ChatType
import com.firestream.chat.domain.model.Contact
import com.firestream.chat.domain.model.Message
import com.firestream.chat.domain.repository.AuthRepository
import com.firestream.chat.domain.repository.ChatRepository
import com.firestream.chat.domain.repository.ContactRepository
import com.firestream.chat.domain.repository.MessageRepository
import com.firestream.chat.domain.repository.UserRepository
import com.firestream.chat.domain.usecase.message.SearchMessagesUseCase
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch
import javax.inject.Inject

data class ChatListUiState(
    val chats: List<Chat> = emptyList(),
    val isLoading: Boolean = true,
    val error: String? = null,
    val currentUserId: String = "",
    val pendingDeleteChatId: String? = null,
    val searchQuery: String = "",
    val searchResults: List<Message> = emptyList(),
    val isSearchActive: Boolean = false,
    val showArchived: Boolean = false,
    val pendingMuteChatId: String? = null,
    val contacts: Map<String, Contact> = emptyMap(),
    // Presence: set of recipient user IDs currently online
    val onlineUserIds: Set<String> = emptySet(),
    val isSearchBarVisible: Boolean = false
)

@HiltViewModel
class ChatListViewModel @Inject constructor(
    private val searchMessagesUseCase: SearchMessagesUseCase,
    private val authRepository: AuthRepository,
    private val chatRepository: ChatRepository,
    private val messageRepository: MessageRepository,
    private val contactRepository: ContactRepository,
    private val userRepository: UserRepository
) : ViewModel() {

    private val _uiState = MutableStateFlow(ChatListUiState())
    val uiState: StateFlow<ChatListUiState> = _uiState.asStateFlow()

    private var searchJob: Job? = null
    private val recipientObservers = mutableMapOf<String, Job>()
    private var cachedRecipientIds: Set<String> = emptySet()

    init {
        _uiState.value = _uiState.value.copy(currentUserId = authRepository.currentUserId ?: "")
        loadChats()
        syncContacts()
        loadContacts()
    }

    private fun syncContacts() {
        viewModelScope.launch {
            contactRepository.syncContacts()
                .onFailure { /* non-fatal: contacts sync is best-effort */ }
        }
    }

    private fun loadContacts() {
        viewModelScope.launch {
            contactRepository.getContacts()
                .catch { /* non-fatal: contacts are best-effort */ }
                .collect { contacts ->
                    _uiState.value = _uiState.value.copy(
                        contacts = contacts.associateBy { it.uid }
                    )
                }
        }
    }

    private val deliveredTimestamps = mutableMapOf<String, Long>()

    private fun loadChats() {
        viewModelScope.launch {
            chatRepository.getChats()
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
                    observeRecipientAvatars(chats)
                    // Mark undelivered messages as DELIVERED for all chats
                    markAllChatsAsDelivered(chats)
                }
        }
    }

    private fun observeRecipientAvatars(chats: List<Chat>) {
        val currentUserId = _uiState.value.currentUserId
        val recipientIds = chats
            .filter { it.type == ChatType.INDIVIDUAL }
            .mapNotNull { chat -> chat.participants.firstOrNull { it != currentUserId } }
            .toSet()

        if (recipientIds == cachedRecipientIds) return
        cachedRecipientIds = recipientIds

        // Cancel observers for recipients no longer in chat list
        val toRemove = recipientObservers.keys - recipientIds
        toRemove.forEach { id ->
            recipientObservers.remove(id)?.cancel()
            // Remove from online set when we stop observing
            _uiState.value = _uiState.value.copy(
                onlineUserIds = _uiState.value.onlineUserIds - id
            )
        }

        // Start observers for new recipients
        val newIds = recipientIds - recipientObservers.keys
        for (recipientId in newIds) {
            recipientObservers[recipientId] = viewModelScope.launch {
                userRepository.observeUser(recipientId)
                    .distinctUntilChanged { old, new ->
                        old.avatarUrl == new.avatarUrl &&
                            old.displayName == new.displayName &&
                            old.isOnline == new.isOnline
                    }
                    .catch { }
                    .collect { user ->
                        val updatedContact = _uiState.value.contacts[recipientId]
                            ?.copy(avatarUrl = user.avatarUrl, displayName = user.displayName)
                            ?: Contact(
                                uid = recipientId,
                                phoneNumber = user.phoneNumber,
                                displayName = user.displayName,
                                avatarUrl = user.avatarUrl,
                                isRegistered = true
                            )
                        val updatedOnline = if (user.isOnline) {
                            _uiState.value.onlineUserIds + recipientId
                        } else {
                            _uiState.value.onlineUserIds - recipientId
                        }
                        _uiState.value = _uiState.value.copy(
                            contacts = _uiState.value.contacts + (recipientId to updatedContact),
                            onlineUserIds = updatedOnline
                        )
                    }
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
            chatRepository.deleteChat(chatId)
        }
    }

    fun togglePin(chatId: String, currentlyPinned: Boolean) {
        val pinnedCount = _uiState.value.chats.count { it.isPinned && !it.isArchived }
        if (!currentlyPinned && pinnedCount >= 3) {
            _uiState.value = _uiState.value.copy(error = "You can pin up to 3 chats")
            return
        }
        viewModelScope.launch {
            chatRepository.pinChat(chatId, !currentlyPinned)
        }
    }

    fun toggleArchive(chatId: String, currentlyArchived: Boolean) {
        viewModelScope.launch {
            chatRepository.archiveChat(chatId, !currentlyArchived)
        }
    }

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
            chatRepository.muteChat(chatId, muteUntil)
        }
    }

    fun toggleShowArchived() {
        _uiState.value = _uiState.value.copy(showArchived = !_uiState.value.showArchived)
    }

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

    fun toggleSearchBar() {
        val newVisible = !_uiState.value.isSearchBarVisible
        _uiState.value = _uiState.value.copy(isSearchBarVisible = newVisible)
        if (!newVisible) clearSearch()
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
