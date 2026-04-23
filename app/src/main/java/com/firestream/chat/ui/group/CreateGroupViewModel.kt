package com.firestream.chat.ui.group

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.firestream.chat.domain.model.AppError
import com.firestream.chat.domain.model.Contact
import com.firestream.chat.domain.repository.ChatRepository
import com.firestream.chat.domain.repository.ContactRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

data class CreateGroupUiState(
    val contacts: List<Contact> = emptyList(),
    val filteredContacts: List<Contact> = emptyList(),
    val selectedIds: Set<String> = emptySet(),
    val name: String = "",
    val searchQuery: String = "",
    val isLoading: Boolean = true,
    val isCreating: Boolean = false,
    val error: AppError? = null
)

@HiltViewModel
class CreateGroupViewModel @Inject constructor(
    private val contactRepository: ContactRepository,
    private val chatRepository: ChatRepository
) : ViewModel() {

    private val _uiState = MutableStateFlow(CreateGroupUiState())
    val uiState: StateFlow<CreateGroupUiState> = _uiState.asStateFlow()

    init {
        loadContacts()
    }

    private fun loadContacts() {
        viewModelScope.launch {
            contactRepository.syncContacts()
                .onSuccess { contacts ->
                    _uiState.value = _uiState.value.copy(
                        contacts = contacts,
                        filteredContacts = contacts,
                        isLoading = false
                    )
                }
                .onFailure { e ->
                    _uiState.value = _uiState.value.copy(isLoading = false, error = AppError.from(e))
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
        _uiState.value = _uiState.value.copy(searchQuery = query, filteredContacts = filtered)
    }

    fun toggleSelection(contactId: String) {
        val current = _uiState.value.selectedIds
        _uiState.value = _uiState.value.copy(
            selectedIds = if (contactId in current) current - contactId else current + contactId
        )
    }

    fun onNameChange(name: String) {
        _uiState.value = _uiState.value.copy(name = name)
    }

    fun createGroup(onCreated: (chatId: String) -> Unit) {
        val state = _uiState.value
        if (state.selectedIds.isEmpty()) {
            _uiState.value = state.copy(error = AppError.Validation("Select at least one member"))
            return
        }
        val name = state.name.ifBlank { "New Group" }
        _uiState.value = state.copy(isCreating = true)
        viewModelScope.launch {
            chatRepository.createGroup(name, state.selectedIds.toList())
                .onSuccess { chat ->
                    _uiState.value = _uiState.value.copy(isCreating = false)
                    onCreated(chat.id)
                }
                .onFailure { e ->
                    _uiState.value = _uiState.value.copy(isCreating = false, error = AppError.from(e))
                }
        }
    }

    fun clearError() {
        _uiState.value = _uiState.value.copy(error = null)
    }
}
