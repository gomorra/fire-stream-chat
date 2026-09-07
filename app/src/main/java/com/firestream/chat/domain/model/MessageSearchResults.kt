package com.firestream.chat.domain.model

/**
 * A page of search results, plus whether the query hit its cap.
 *
 * [truncated] deliberately cannot be derived from `messages.size`. The data
 * layer applies a whole-word filter *after* SQLite's `LIMIT`, so a capped query
 * can return far fewer messages than the cap while still having left genuine
 * matches unfetched: searching "cat" in a chat full of "category" fills the
 * 50-row page with substring hits, of which two might survive the filter — and
 * a UI counting those two survivors would present a truncated page as an exact
 * total. Only the layer that saw the raw row count knows.
 */
data class MessageSearchResults(
    val messages: List<Message> = emptyList(),
    val truncated: Boolean = false,
) {
    companion object {
        val EMPTY = MessageSearchResults()
    }
}
