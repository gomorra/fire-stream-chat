package com.firestream.chat.ui.chat

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import com.firestream.chat.domain.usecase.message.SearchMessagesUseCase

internal class ChatSearchManager(
    private val chatId: String,
    private val searchMessagesUseCase: SearchMessagesUseCase,
    private val _uiState: MutableStateFlow<ChatUiState>,
    private val scope: CoroutineScope
) {

    private var searchJob: Job? = null

    fun onSearchQueryChange(query: String) {
        _uiState.update { it.copy(searchQuery = query) }
        searchJob?.cancel()
        if (query.isBlank()) {
            _uiState.update { it.copy(searchResults = emptyList()) }
            return
        }
        searchJob = scope.launch {
            delay(300)
            try {
                val results = searchMessagesUseCase(query, chatId)
                _uiState.update { it.copy(searchResults = results) }
            } catch (_: Exception) {
                _uiState.update { it.copy(searchResults = emptyList()) }
            }
        }
    }

    fun toggleSearch() {
        _uiState.update {
            val newActive = !it.isSearchActive
            it.copy(
                isSearchActive = newActive,
                searchQuery = if (newActive) it.searchQuery else "",
                searchResults = if (newActive) it.searchResults else emptyList()
            )
        }
        if (!_uiState.value.isSearchActive) searchJob?.cancel()
    }

    fun clearSearch() {
        searchJob?.cancel()
        _uiState.update {
            it.copy(
                isSearchActive = false,
                searchQuery = "",
                searchResults = emptyList()
            )
        }
    }
}
