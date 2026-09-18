package com.firestream.chat.domain.model

/**
 * A page of search results, plus whether the query hit its cap.
 *
 * [truncated] deliberately cannot be derived from `messages.size`. A query of
 * two characters or more is answered by the substring match itself, but a
 * single-letter one is still narrowed to the whole word it spells, and that
 * narrowing runs *after* SQLite's `LIMIT` — so such a query can return far
 * fewer messages than the cap while still having left genuine matches
 * unfetched: searching "a" in a chat full of "category" fills the 50-row page
 * with substring hits, of which two might be the word — and a UI counting those
 * two survivors would present a truncated page as an exact total. Only the
 * layer that saw the raw row count knows.
 */
data class MessageSearchResults(
    val messages: List<Message> = emptyList(),
    val truncated: Boolean = false,
) {
    companion object {
        val EMPTY = MessageSearchResults()
    }
}
