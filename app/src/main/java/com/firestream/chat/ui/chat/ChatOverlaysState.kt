package com.firestream.chat.ui.chat

import androidx.compose.runtime.Immutable
import com.firestream.chat.data.remote.LinkPreview
import com.firestream.chat.domain.model.ListData
import com.firestream.chat.domain.model.Message
import com.firestream.chat.domain.model.MessageSearchFilter

// The currently-shown fullscreen image. Both message images and link-preview
// thumbnails feed into the same fullscreen overlay, but they carry different
// data: a tapped message image has [messageId] + localUri + save-to-downloads
// support, while a link-preview thumbnail only has a remote URL. Lives in
// ChatUiState (not screen-local compose state) so the open viewer survives
// activity recreation on rotation via ViewModel retention — no saved-instance
// -state round-trip involved.
//
// [messageId] also selects the overlay: a message image opens the swipeable
// FullscreenImagePager over every image in the chat (starting at that message),
// while a null id — a link-preview thumbnail, which is not part of the chat's
// media — opens the single-image FullscreenImageViewer.
@Immutable
internal data class FullscreenImage(
    val imageUrl: String?,
    val localUri: String? = null,
    val canSaveToDownloads: Boolean = false,
    val messageId: String? = null,
)

// The currently-shown fullscreen video. Mirrors FullscreenImage: lives in
// ChatUiState (not screen-local compose state) so the open player survives
// activity recreation on rotation via ViewModel retention. `source` is
// local-first (localUri ?: mediaUrl) — resolved by the caller before this is
// constructed, same as FullscreenImageViewer's model resolution.
@Immutable
internal data class FullscreenVideo(
    val source: String,
)

internal data class OverlaysState(
    val searchQuery: String = "",
    // The prefilter chips under the search box. An active filter with a blank
    // query is browse mode ("show me the photos"), so this is a second,
    // independent input to the same search — not a post-filter over results.
    val searchFilter: MessageSearchFilter = MessageSearchFilter.NONE,
    val searchResults: List<Message> = emptyList(),
    // Whether the query hit its cap, so the results header can say "200+"
    // rather than presenting a truncated page as an exact total. Not derivable
    // from searchResults.size — see MessageSearchResults.
    val searchResultsTruncated: Boolean = false,
    val isSearchActive: Boolean = false,
    /** Message id → its resolved link preview. Keyed by message so the UI never
     *  re-derives the URL; ChatMessageLoader owns the pairing for the loaded
     *  window, ChatSearchManager adds the older messages a Links search reaches.
     *  One map for both, so a link previewed in the conversation is not fetched
     *  a second time to render its search row. */
    val linkPreviews: Map<String, LinkPreview> = emptyMap(),
    val listDataCache: Map<String, ListData?> = emptyMap(),
    val recentEmojis: List<String> = emptyList(),
    val fullscreenImage: FullscreenImage? = null,
    val fullscreenVideo: FullscreenVideo? = null,
)
