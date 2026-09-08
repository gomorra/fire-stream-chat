// region: AGENT-NOTE
// Responsibility: Debounced full-text search across the current chat, plus the
//   prefilter chips (type / starred / date range) that also drive it.
// Owns: ChatUiState.overlays.{searchQuery, searchFilter, searchResults, isSearchActive}.
// Collaborators: ChatViewModel (composition root), SearchMessagesUseCase.
// Don't put here: global search across chats (lives in ui/search/
//   GlobalSearchViewModel), the debounce/cancellation machinery itself (shared
//   with global search as ui/search/SearchRunner), or any state outside the
//   overlays slice. Pattern: docs/PATTERNS.md#chat-manager-slice-ownership.
// endregion

package com.firestream.chat.ui.chat

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import com.firestream.chat.domain.model.MessageSearchFilter
import com.firestream.chat.domain.usecase.message.SearchMessagesUseCase
import com.firestream.chat.ui.search.SearchRunner

internal class ChatSearchManager(
    chatId: String,
    searchMessagesUseCase: SearchMessagesUseCase,
    private val _uiState: MutableStateFlow<ChatUiState>,
    scope: CoroutineScope
) {

    // Debounce timing and the cancel-supersede contract are shared with global
    // search; only where the results land is this manager's business.
    private val runner = SearchRunner(scope, searchMessagesUseCase, chatId) { messages, truncated ->
        _uiState.update {
            it.copy(
                overlays = it.overlays.copy(
                    searchResults = messages,
                    searchResultsTruncated = truncated,
                )
            )
        }
    }

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
                    searchResults = if (newActive) it.overlays.searchResults else emptyList(),
                    searchResultsTruncated = if (newActive) it.overlays.searchResultsTruncated else false
                )
            )
        }
        if (!_uiState.value.overlays.isSearchActive) runner.cancel()
    }

    fun clearSearch() {
        runner.cancel()
        _uiState.update {
            it.copy(
                overlays = it.overlays.copy(
                    isSearchActive = false,
                    searchQuery = "",
                    searchFilter = MessageSearchFilter.NONE,
                    searchResults = emptyList(),
                    searchResultsTruncated = false,
                )
            )
        }
    }

    /** Re-issues the search from whatever query and filter are currently in state. */
    private fun runSearch(debounce: Boolean) {
        val overlays = _uiState.value.overlays
        runner.run(overlays.searchQuery, overlays.searchFilter, debounce)
    }
}
