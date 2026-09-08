package com.firestream.chat.domain.model

/**
 * The type axis of message search. Single-select: two types would read as AND
 * where the user means OR.
 *
 * Deliberately *not* an alias for [MessageType]: [LINKS] is a property of a
 * message's content, not its type — a link lives in the `content` of a `TEXT`
 * row. The data layer therefore maps this enum onto **two** query parameters
 * (a nullable type name and a "require link" flag); the domain enum stays flat.
 */
enum class MessageFilterType {
    PHOTOS,
    VIDEOS,
    LINKS,
    DOCS,
    VOICE,
}

/**
 * A prefilter over message search, in a single chat or across all of them. The
 * three axes are independent: [type] is single-select, [isStarred] and the
 * [fromMs]–[toMs] date range are toggles.
 *
 * An active filter with a blank query is *browse mode* ("show me the photos"):
 * `SearchMessagesUseCase`'s blank-query guard only returns empty when there is
 * no filter either.
 */
data class MessageSearchFilter(
    val type: MessageFilterType? = null,
    val isStarred: Boolean = false,
    val fromMs: Long? = null,
    val toMs: Long? = null,
) {
    val isActive: Boolean
        get() = type != null || isStarred || fromMs != null || toMs != null

    companion object {
        val NONE = MessageSearchFilter()
    }
}

/**
 * Result caps for message search. Text search keeps the historical 50 in a
 * single chat and 100 across all of them; browse mode (a filter chip with no
 * query typed) gets 200 either way, because there the filter — not the query —
 * is doing the selecting and 50 truncates visibly. 200 is also the largest
 * value that keeps the deferred `(chatId, timestamp)` index defensible; going
 * higher means doing the index too.
 *
 * They live here rather than privately in the repository because the result
 * count the UI shows is cap-truncated, and a UI that cannot tell it is at the
 * cap has to either lie ("200 photos" when there are 2,000) or say nothing.
 */
object MessageSearchLimits {
    const val TEXT = 50
    const val BROWSE = 200

    /** Text search across every chat: a wider net than one chat's 50. */
    const val GLOBAL = 100

    /**
     * Takes the same [chatId] the query itself is given — null meaning every
     * chat — rather than a separate "is global" flag, which would be a second
     * expression of one fact and could disagree with it.
     */
    fun forScope(query: String, chatId: String?): Int = when {
        query.isBlank() -> BROWSE
        chatId == null -> GLOBAL
        else -> TEXT
    }
}
