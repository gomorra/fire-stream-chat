package com.firestream.chat.ui.contacts

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.firestream.chat.domain.model.Contact
import com.firestream.chat.domain.repository.ChatRepository
import com.firestream.chat.domain.usecase.contact.SyncContactsUseCase
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

data class ContactsUiState(
    val contacts: List<Contact> = emptyList(),
    val filteredContacts: List<Contact> = emptyList(),
    val searchQuery: String = "",
    val isLoading: Boolean = true,
    val error: String? = null
)

@HiltViewModel
class ContactsViewModel @Inject constructor(
    private val syncContactsUseCase: SyncContactsUseCase,
    private val chatRepository: ChatRepository
) : ViewModel() {

    private val _uiState = MutableStateFlow(ContactsUiState())
    val uiState: StateFlow<ContactsUiState> = _uiState.asStateFlow()

    init {
        loadContacts()
    }

    private fun loadContacts() {
        viewModelScope.launch {
            syncContactsUseCase()
                .onSuccess { contacts ->
                    _uiState.value = _uiState.value.copy(
                        contacts = contacts,
                        filteredContacts = contacts,
                        isLoading = false
                    )
                }
                .onFailure { e ->
                    _uiState.value = _uiState.value.copy(
                        isLoading = false,
                        error = e.message
                    )
                }
        }
    }

    fun onSearchQueryChange(query: String) {
        val filtered = if (query.isBlank()) {
            _uiState.value.contacts
        } else {
            _uiState.value.contacts.filter { contact ->
                contact.displayName.contains(query, ignoreCase = true) ||
                    contact.phoneNumber.contains(query)
            }
        }
        _uiState.value = _uiState.value.copy(
            searchQuery = query,
            filteredContacts = filtered
        )
    }

    fun startChat(contact: Contact, onChatReady: (chatId: String, recipientId: String) -> Unit) {
        viewModelScope.launch {
            chatRepository.getOrCreateChat(contact.uid)
                .onSuccess { chat ->
                    onChatReady(chat.id, contact.uid)
                }
                .onFailure { e ->
                    _uiState.value = _uiState.value.copy(error = e.message)
                }
        }
    }

    fun clearError() {
        _uiState.value = _uiState.value.copy(error = null)
    }
}
