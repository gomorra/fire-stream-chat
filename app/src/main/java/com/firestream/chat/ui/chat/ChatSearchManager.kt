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
        _uiState.update { it.copy(overlays = it.overlays.copy(searchQuery = query)) }
        searchJob?.cancel()
        if (query.isBlank()) {
            _uiState.update { it.copy(overlays = it.overlays.copy(searchResults = emptyList())) }
            return
        }
        searchJob = scope.launch {
            delay(300)
            try {
                val results = searchMessagesUseCase(query, chatId)
                _uiState.update { it.copy(overlays = it.overlays.copy(searchResults = results)) }
            } catch (_: Exception) {
                _uiState.update { it.copy(overlays = it.overlays.copy(searchResults = emptyList())) }
            }
        }
    }

    fun toggleSearch() {
        _uiState.update {
            val newActive = !it.overlays.isSearchActive
            it.copy(
                overlays = it.overlays.copy(
                    isSearchActive = newActive,
                    searchQuery = if (newActive) it.overlays.searchQuery else "",
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
                    searchResults = emptyList()
                )
            )
        }
    }
}
