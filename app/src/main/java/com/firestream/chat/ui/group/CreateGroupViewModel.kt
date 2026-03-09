package com.firestream.chat.ui.group

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.firestream.chat.domain.model.Contact
import com.firestream.chat.domain.usecase.chat.CreateGroupUseCase
import com.firestream.chat.domain.usecase.contact.SyncContactsUseCase
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
    val error: String? = null
)

@HiltViewModel
class CreateGroupViewModel @Inject constructor(
    private val syncContactsUseCase: SyncContactsUseCase,
    private val createGroupUseCase: CreateGroupUseCase
) : ViewModel() {

    private val _uiState = MutableStateFlow(CreateGroupUiState())
    val uiState: StateFlow<CreateGroupUiState> = _uiState.asStateFlow()

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
                    _uiState.value = _uiState.value.copy(isLoading = false, error = e.message)
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
            _uiState.value = state.copy(error = "Select at least one member")
            return
        }
        val name = state.name.ifBlank { "New Group" }
        _uiState.value = state.copy(isCreating = true)
        viewModelScope.launch {
            createGroupUseCase(name, state.selectedIds.toList())
                .onSuccess { chat ->
                    _uiState.value = _uiState.value.copy(isCreating = false)
                    onCreated(chat.id)
                }
                .onFailure { e ->
                    _uiState.value = _uiState.value.copy(isCreating = false, error = e.message)
                }
        }
    }

    fun clearError() {
        _uiState.value = _uiState.value.copy(error = null)
    }
}
