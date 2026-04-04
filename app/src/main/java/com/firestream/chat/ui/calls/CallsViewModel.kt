package com.firestream.chat.ui.calls

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.firestream.chat.domain.model.CallDirection
import com.firestream.chat.domain.model.CallLogEntry
import com.firestream.chat.domain.model.Contact
import com.firestream.chat.domain.model.Message
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
import kotlinx.coroutines.launch
import javax.inject.Inject

data class CallsUiState(
    val entries: List<CallLogEntry> = emptyList(),
    val isLoading: Boolean = true,
    val isRefreshing: Boolean = false,
    val error: String? = null,
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

    init {
        loadContacts()
        loadCallLog()
    }

    private fun loadContacts() {
        viewModelScope.launch {
            contactRepository.getContacts()
                .catch { }
                .collect { contacts ->
                    _uiState.value = _uiState.value.copy(
                        contacts = contacts.associateBy { it.uid }
                    )
                }
        }
    }

    private fun loadCallLog() {
        viewModelScope.launch {
            combine(messageRepository.getCallLog(), chatRepository.getChats()) { messages, chats ->
                val chatMap = chats.associateBy { it.id }
                messages.mapNotNull { message ->
                    val chat = chatMap[message.chatId] ?: return@mapNotNull null
                    val otherPartyId = chat.participants.firstOrNull { it != currentUserId }
                        ?: return@mapNotNull null
                    val contact = _uiState.value.contacts[otherPartyId]
                    val displayName = contact?.displayName?.takeIf { it.isNotBlank() }
                        ?: "Unknown"
                    CallLogEntry(
                        messageId = message.id,
                        chatId = message.chatId,
                        otherPartyId = otherPartyId,
                        displayName = displayName,
                        avatarUrl = contact?.avatarUrl,
                        direction = deriveDirection(message.senderId, message.content),
                        durationSeconds = message.duration,
                        timestamp = message.timestamp
                    )
                }
            }
                .catch { e ->
                    _uiState.value = _uiState.value.copy(isLoading = false, error = e.message)
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
                        val updated = _uiState.value.contacts[userId]
                            ?.copy(avatarUrl = user.avatarUrl, displayName = user.displayName)
                            ?: Contact(
                                uid = userId,
                                phoneNumber = user.phoneNumber,
                                displayName = user.displayName,
                                avatarUrl = user.avatarUrl,
                                isRegistered = true
                            )
                        _uiState.value = _uiState.value.copy(
                            contacts = _uiState.value.contacts + (userId to updated)
                        )
                    }
            }
        }
    }

    fun refresh() {
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(isRefreshing = true)
            try {
                val contacts = contactRepository.getContacts().first()
                _uiState.value = _uiState.value.copy(
                    contacts = contacts.associateBy { it.uid }
                )
                val messages = messageRepository.getCallLog().first()
                val chats = chatRepository.getChats().first()
                val chatMap = chats.associateBy { it.id }
                val entries = messages.mapNotNull { message ->
                    val chat = chatMap[message.chatId] ?: return@mapNotNull null
                    val otherPartyId = chat.participants.firstOrNull { it != currentUserId }
                        ?: return@mapNotNull null
                    val contact = _uiState.value.contacts[otherPartyId]
                    val displayName = contact?.displayName?.takeIf { it.isNotBlank() } ?: "Unknown"
                    CallLogEntry(
                        messageId = message.id,
                        chatId = message.chatId,
                        otherPartyId = otherPartyId,
                        displayName = displayName,
                        avatarUrl = contact?.avatarUrl,
                        direction = deriveDirection(message.senderId, message.content),
                        durationSeconds = message.duration,
                        timestamp = message.timestamp
                    )
                }
                _uiState.value = _uiState.value.copy(entries = entries)
            } catch (e: Exception) {
                _uiState.value = _uiState.value.copy(error = e.message)
            }
            _uiState.value = _uiState.value.copy(isRefreshing = false)
        }
    }

    fun clearError() {
        _uiState.value = _uiState.value.copy(error = null)
    }

    companion object {
        fun deriveDirection(senderId: String, content: String, currentUserId: String): CallDirection {
            if (senderId == currentUserId) return CallDirection.OUTGOING
            return when (content) {
                "hangup", "remote_hangup" -> CallDirection.INCOMING
                else -> CallDirection.MISSED
            }
        }
    }

    private fun deriveDirection(senderId: String, content: String): CallDirection =
        deriveDirection(senderId, content, currentUserId)
}
