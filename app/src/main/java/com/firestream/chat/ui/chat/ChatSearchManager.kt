// region: AGENT-NOTE
// Responsibility: Debounced full-text search across the current chat, plus the
//   prefilter chips (type / starred / date range) that also drive it.
// Owns: ChatUiState.overlays.{searchQuery, searchFilter, searchResults, isSearchActive}.
// Collaborators: ChatViewModel (composition root), SearchMessagesUseCase.
// Don't put here: global search across chats (lives in ChatListViewModel),
//   any state outside the overlays slice. Pattern:
//   docs/PATTERNS.md#chat-manager-slice-ownership.
// endregion

package com.firestream.chat.ui.chat

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import com.firestream.chat.domain.model.MessageSearchFilter
import com.firestream.chat.domain.usecase.message.SearchMessagesUseCase

// Only typing is debounced. A chip tap is a discrete action with a settled
// intent behind it, so it re-queries at once (see [onFilterChange]).
private const val TYPING_DEBOUNCE_MS = 300L

internal class ChatSearchManager(
    private val chatId: String,
    private val searchMessagesUseCase: SearchMessagesUseCase,
    private val _uiState: MutableStateFlow<ChatUiState>,
    private val scope: CoroutineScope
) {

    private var searchJob: Job? = null

    fun onSearchQueryChange(query: String) {
        _uiState.update { it.copy(overlays = it.overlays.copy(searchQuery = query)) }
        runSearch(debounce = true)
    }

    /**
     * A chip tap. Re-queries immediately: unlike a keystroke it is a complete
     * intent, and 300 ms of nothing after a tap reads as a dropped tap.
     */
    fun onFilterChange(filter: MessageSearchFilter) {
        _uiState.update { it.copy(overlays = it.overlays.copy(searchFilter = filter)) }
        runSearch(debounce = false)
    }

    /**
     * Opens search with [filter] already applied — the entry point behind
     * "Shared media", which lands the user in a Photos browse. Idempotent when
     * search is already open; the filter is applied either way.
     */
    fun openSearchWithFilter(filter: MessageSearchFilter) {
        _uiState.update {
            it.copy(
                overlays = it.overlays.copy(
                    isSearchActive = true,
                    searchQuery = "",
                    searchFilter = filter,
                )
            )
        }
        runSearch(debounce = false)
    }

    fun toggleSearch() {
        _uiState.update {
            val newActive = !it.overlays.isSearchActive
            it.copy(
                overlays = it.overlays.copy(
                    isSearchActive = newActive,
                    searchQuery = if (newActive) it.overlays.searchQuery else "",
                    searchFilter = if (newActive) it.overlays.searchFilter else MessageSearchFilter.NONE,
                    searchResults = if (newActive) it.overlays.searchResults else emptyList()
                )
            )
        }
        if (!_uiState.value.overlays.isSearchActive) searchJob?.cancel()
    }

    fun clearSearch() {
        searchJob?.cancel()
        _uiState.update {
            it.copy(
                overlays = it.overlays.copy(
                    isSearchActive = false,
                    searchQuery = "",
                    searchFilter = MessageSearchFilter.NONE,
                    searchResults = emptyList()
                )
            )
        }
    }

    /**
     * Re-issues the search from whatever query and filter are currently in
     * state — the single path both inputs funnel through, so neither can go
     * stale against the other.
     */
    private fun runSearch(debounce: Boolean) {
        searchJob?.cancel()
        val overlays = _uiState.value.overlays
        val query = overlays.searchQuery
        val filter = overlays.searchFilter
        // Nothing selected on either axis: the use case would return empty
        // anyway, but short-circuiting keeps the results list from flickering
        // through a round trip on the way back to empty.
        if (query.isBlank() && !filter.isActive) {
            _uiState.update { it.copy(overlays = it.overlays.copy(searchResults = emptyList())) }
            return
        }
        searchJob = scope.launch {
            if (debounce) delay(TYPING_DEBOUNCE_MS)
            try {
                val results = searchMessagesUseCase(query, chatId, filter)
                _uiState.update { it.copy(overlays = it.overlays.copy(searchResults = results)) }
            } catch (e: CancellationException) {
                // A superseded job must not clear the results the job that
                // replaced it is about to write.
                throw e
            } catch (_: Exception) {
                _uiState.update { it.copy(overlays = it.overlays.copy(searchResults = emptyList())) }
            }
        }
    }
}
