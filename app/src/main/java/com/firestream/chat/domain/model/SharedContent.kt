package com.firestream.chat.domain.model

sealed class SharedContent {
    data class Text(val text: String) : SharedContent()
    data class Media(val items: List<MediaItem>) : SharedContent() {
        data class MediaItem(val cachedUri: String, val mimeType: String, val fileName: String)
    }
}
