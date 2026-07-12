package com.firestream.chat.ui.chat

import androidx.compose.runtime.Immutable
import com.firestream.chat.data.remote.LinkPreview
import com.firestream.chat.domain.model.ListData
import com.firestream.chat.domain.model.Message

// The currently-shown fullscreen image. Both message images and link-preview
// thumbnails feed into the same FullscreenImageViewer overlay, but they carry
// different data: a tapped message image has localUri + save-to-downloads
// support, while a link-preview thumbnail only has a remote URL. Lives in
// ChatUiState (not screen-local compose state) so the open viewer survives
// activity recreation on rotation via ViewModel retention — no saved-instance
// -state round-trip involved.
@Immutable
internal data class FullscreenImage(
    val imageUrl: String?,
    val localUri: String? = null,
    val canSaveToDownloads: Boolean = false,
)

internal data class OverlaysState(
    val searchQuery: String = "",
    val searchResults: List<Message> = emptyList(),
    val isSearchActive: Boolean = false,
    val linkPreviews: Map<String, LinkPreview> = emptyMap(),
    val listDataCache: Map<String, ListData?> = emptyMap(),
    val recentEmojis: List<String> = emptyList(),
    val fullscreenImage: FullscreenImage? = null,
)
