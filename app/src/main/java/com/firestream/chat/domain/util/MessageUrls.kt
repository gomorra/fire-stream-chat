package com.firestream.chat.domain.util

/**
 * Finds the link inside a message's text.
 *
 * Pure text logic with no dependencies, so every layer can call it directly:
 * the data layer fetches a preview for what it returns, and the UI uses the same
 * answer to label a search row. Both used to carry their own copy of the regex,
 * which had already drifted — the chat trimmed trailing punctuation and the
 * search row did not, so one message rendered two different links.
 */
object MessageUrls {

    private val URL_REGEX = Regex("""https?://[^\s]+""")

    // Sentence punctuation that follows a link far more often than it belongs to
    // one: "see https://example.com/page." must not resolve the period as path.
    private const val TRAILING_PUNCTUATION = ".,;:!?'\"]}"

    /** The first URL in [text], or null if it has none. */
    fun extractUrl(text: String): String? {
        val raw = URL_REGEX.find(text)?.value ?: return null
        // Computed once over the whole match: a closing paren only counts as
        // punctuation when it is unmatched, because Wikipedia-style paths
        // legitimately end in ')'.
        val unmatchedCloseParen = raw.count { it == ')' } > raw.count { it == '(' }
        val trimmed = raw.trimEnd { c ->
            c in TRAILING_PUNCTUATION || (c == ')' && unmatchedCloseParen)
        }
        return trimmed.takeIf { it.substringAfter("://", "").isNotEmpty() }
    }
}
