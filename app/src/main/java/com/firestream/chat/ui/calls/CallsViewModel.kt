package com.firestream.chat.ui.calls

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.firestream.chat.domain.model.AppError
import com.firestream.chat.domain.model.CallLogEntry
import com.firestream.chat.domain.model.CallLogType
import com.firestream.chat.domain.model.Chat
import com.firestream.chat.domain.model.Contact
import com.firestream.chat.domain.model.Message
import com.firestream.chat.domain.model.User
import com.firestream.chat.domain.repository.AuthRepository
import com.firestream.chat.domain.repository.ChatRepository
import com.firestream.chat.domain.repository.ContactRepository
import com.firestream.chat.domain.repository.MessageRepository
import com.firestream.chat.domain.repository.UserRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import javax.inject.Inject

data class CallsUiState(
    val entries: List<CallLogEntry> = emptyList(),
    val isLoading: Boolean = true,
    val isRefreshing: Boolean = false,
    val error: AppError? = null,
    val contacts: Map<String, Contact> = emptyMap()
)

@HiltViewModel
class CallsViewModel @Inject constructor(
    private val messageRepository: MessageRepository,
    private val chatRepository: ChatRepository,
    private val authRepository: AuthRepository,
    private val contactRepository: ContactRepository,
    private val userRepository: UserRepository
) : ViewModel() {

    private val _uiState = MutableStateFlow(CallsUiState())
    val uiState: StateFlow<CallsUiState> = _uiState.asStateFlow()

    private val currentUserId = authRepository.currentUserId ?: ""
    private val userObservers = mutableMapOf<String, kotlinx.coroutines.Job>()
    private var cachedObservedIds: Set<String> = emptySet()

    /** The contacts as [ContactRepository] last emitted them. */
    private var savedContacts: Map<String, Contact> = emptyMap()

    /** The profile of each other party in the log, from [observeOtherPartyUsers]. */
    private val profiles = mutableMapOf<String, User>()

    init {
        loadContacts()
        loadCallLog()
    }

    private fun loadContacts() {
        viewModelScope.launch {
            contactRepository.getContacts()
                .catch { }
                .collect { contacts ->
                    savedContacts = contacts.associateBy { it.uid }
                    publishContacts()
                }
        }
    }

    /**
     * Publish the saved contacts with each profile's name and avatar laid over them. A caller who
     * is not a contact is known only from their profile, so a contacts reload must not replace the
     * published map.
     */
    private fun publishContacts() {
        val merged = savedContacts + profiles.mapValues { (userId, user) ->
            savedContacts[userId]?.copy(avatarUrl = user.avatarUrl, displayName = user.displayName)
                ?: Contact(
                    uid = userId,
                    phoneNumber = user.phoneNumber,
                    displayName = user.displayName,
                    avatarUrl = user.avatarUrl,
                    isRegistered = true
                )
        }
        _uiState.value = _uiState.value.copy(contacts = merged)
    }

    private fun buildEntries(
        messages: List<Message>,
        chats: List<Chat>,
        contacts: Map<String, Contact>
    ): List<CallLogEntry> {
        val chatMap = chats.associateBy { it.id }
        return messages.mapNotNull { message ->
            val chat = chatMap[message.chatId] ?: return@mapNotNull null
            val otherPartyId = chat.participants.firstOrNull { it != currentUserId }
                ?: return@mapNotNull null
            val contact = contacts[otherPartyId]
            val displayName = contact?.displayName?.takeIf { it.isNotBlank() } ?: "Unknown"
            CallLogEntry(
                messageId = message.id,
                chatId = message.chatId,
                otherPartyId = otherPartyId,
                displayName = displayName,
                avatarUrl = contact?.avatarUrl,
                type = CallLogType.of(message.senderId == currentUserId, message.content, message.duration),
                durationSeconds = message.duration,
                timestamp = message.timestamp,
                video = message.isVideoCall
            )
        }
    }

    private fun loadCallLog() {
        viewModelScope.launch {
            combine(
                messageRepository.getCallLog(),
                chatRepository.getChats(),
                // Names arrive after the log does: contacts load on their own, and
                // observeOtherPartyUsers fills in callers who are not contacts.
                _uiState.map { it.contacts }.distinctUntilChanged()
            ) { messages, chats, contacts ->
                buildEntries(messages, chats, contacts)
            }
                .catch { e ->
                    _uiState.value = _uiState.value.copy(isLoading = false, error = AppError.from(e))
                }
                .collect { entries ->
                    _uiState.value = _uiState.value.copy(entries = entries, isLoading = false)
                    observeOtherPartyUsers(entries.map { it.otherPartyId }.toSet())
                }
        }
    }

    private fun observeOtherPartyUsers(ids: Set<String>) {
        if (ids == cachedObservedIds) return
        cachedObservedIds = ids

        val toRemove = userObservers.keys - ids
        toRemove.forEach { userObservers.remove(it)?.cancel() }

        val newIds = ids - userObservers.keys
        for (userId in newIds) {
            userObservers[userId] = viewModelScope.launch {
                userRepository.observeUser(userId)
                    .distinctUntilChanged { old, new ->
                        old.avatarUrl == new.avatarUrl && old.displayName == new.displayName
                    }
                    .catch { }
                    .collect { user ->
                        profiles[userId] = user
                        publishContacts()
                    }
            }
        }
    }

    fun refresh() {
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(isRefreshing = true)
            try {
                savedContacts = contactRepository.getContacts().first().associateBy { it.uid }
                publishContacts()
                val messages = messageRepository.getCallLog().first()
                val chats = chatRepository.getChats().first()
                val entries = buildEntries(messages, chats, _uiState.value.contacts)
                _uiState.value = _uiState.value.copy(entries = entries)
            } catch (e: Exception) {
                _uiState.value = _uiState.value.copy(error = AppError.from(e))
            }
            _uiState.value = _uiState.value.copy(isRefreshing = false)
        }
    }

    fun clearError() {
        _uiState.value = _uiState.value.copy(error = null)
    }
}
