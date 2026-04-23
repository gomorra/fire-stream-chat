package com.firestream.chat.ui.chat

import com.firestream.chat.data.remote.LinkPreview
import com.firestream.chat.domain.model.ListData
import com.firestream.chat.domain.model.Message

internal data class OverlaysState(
    val searchQuery: String = "",
    val searchResults: List<Message> = emptyList(),
    val isSearchActive: Boolean = false,
    val linkPreviews: Map<String, LinkPreview> = emptyMap(),
    val listDataCache: Map<String, ListData?> = emptyMap(),
    val recentEmojis: List<String> = emptyList(),
)
