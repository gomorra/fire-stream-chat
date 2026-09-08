// region: AGENT-NOTE
// Responsibility: Debounced search across *every* chat, plus the prefilter
//   chips (type / starred / date range) that also drive it, and the chat /
//   contact maps a cross-chat result row needs to say where it came from.
// Owns: GlobalSearchUiState in its entirety.
// Collaborators: SearchMessagesUseCase (chatId = null), ChatRepository,
//   ContactRepository, AuthRepository.
// Don't put here: in-chat search (ChatSearchManager owns the overlays slice),
//   chat-list concerns, or the debounce/cancellation machinery (SearchRunner,
//   shared with in-chat search). Derived reads belong on GlobalSearchUiState,
//   not here — see ChatSessionState.senderDisplayName for the precedent.
// endregion

package com.firestream.chat.ui.search

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.firestream.chat.domain.model.Chat
import com.firestream.chat.domain.model.Contact
import com.firestream.chat.domain.model.Message
import com.firestream.chat.domain.model.MessageSearchFilter
import com.firestream.chat.domain.repository.AuthRepository
import com.firestream.chat.domain.repository.ChatRepository
import com.firestream.chat.domain.repository.ContactRepository
import com.firestream.chat.domain.usecase.message.SearchMessagesUseCase
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

data class GlobalSearchUiState(
    val query: String = "",
    val filter: MessageSearchFilter = MessageSearchFilter.NONE,
    val results: List<Message> = emptyList(),
    val truncated: Boolean = false,
    val chats: Map<String, Chat> = emptyMap(),
    val contacts: Map<String, Contact> = emptyMap(),
    val currentUserId: String = "",
) {
    /** Whether the user has selected anything on either axis — see [isSearchSelecting]. */
    val isSelecting: Boolean
        get() = isSearchSelecting(query, filter)
}

/**
 * The label above a result row: `Alice · Weekend Trip`.
 *
 * An extension on the state rather than a view-model method, so the composable
 * reads it from the snapshot it already subscribes to — the same shape as
 * `SessionState.senderDisplayName`, which fills this slot for in-chat search.
 */
internal fun GlobalSearchUiState.resultLabel(message: Message): String =
    globalResultLabel(message, chats[message.chatId], contacts, currentUserId)

/**
 * The other participant of [chatId], for building the chat route a result tap
 * navigates to. Empty for groups and broadcasts, which is what `Routes.chat`
 * expects.
 */
internal fun GlobalSearchUiState.recipientIdFor(chatId: String): String =
    chats[chatId].otherParticipant(currentUserId)

@HiltViewModel
class GlobalSearchViewModel @Inject constructor(
    searchMessagesUseCase: SearchMessagesUseCase,
    authRepository: AuthRepository,
    chatRepository: ChatRepository,
    contactRepository: ContactRepository,
) : ViewModel() {

    private val _uiState = MutableStateFlow(GlobalSearchUiState())
    val uiState: StateFlow<GlobalSearchUiState> = _uiState.asStateFlow()

    // Debounce timing and the cancel-supersede contract are shared with in-chat
    // search; only where the results land is this view model's business.
    private val runner = SearchRunner(viewModelScope, searchMessagesUseCase, chatId = null) { messages, truncated ->
        _uiState.update { it.copy(results = messages, truncated = truncated) }
    }

    init {
        _uiState.update { it.copy(currentUserId = authRepository.currentUserId ?: "") }
        viewModelScope.launch {
            chatRepository.getChats()
                .catch { /* non-fatal: results still render, just without a chat label */ }
                .collect { chats -> _uiState.update { it.copy(chats = chats.associateBy { c -> c.id }) } }
        }
        viewModelScope.launch {
            contactRepository.getContacts()
                .catch { /* non-fatal: labels fall back to the chat title */ }
                .collect { contacts -> _uiState.update { it.copy(contacts = contacts.associateBy { c -> c.uid }) } }
        }
    }

    fun onQueryChange(query: String) {
        _uiState.update { it.copy(query = query) }
        runSearch(debounce = true)
    }

    /** A chip tap: a complete intent, so it re-queries without the typing delay. */
    fun onFilterChange(filter: MessageSearchFilter) {
        _uiState.update { it.copy(filter = filter) }
        runSearch(debounce = false)
    }

    fun clearQuery() {
        _uiState.update { it.copy(query = "") }
        runSearch(debounce = false)
    }

    private fun runSearch(debounce: Boolean) {
        val state = _uiState.value
        runner.run(state.query, state.filter, debounce)
    }
}
