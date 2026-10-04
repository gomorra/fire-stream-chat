package com.firestream.chat.domain.model

/** What an online catalogue offers: animated pictures, or stickers with a transparent background. */
enum class OnlineMediaKind { GIF, STICKER }

/** One file of an online item, at the url the provider returned it under. */
data class OnlineMediaRendition(
    val url: String,
    val width: Int,
    val height: Int,
    val mimeType: String,
)

/**
 * A GIF or sticker from the online catalogue. [preview] is the small file a
 * grid shows, and [send] is the one a message points at. [slug] names the item
 * to the provider when a pick is reported.
 */
data class OnlineMedia(
    val kind: OnlineMediaKind,
    val slug: String,
    val title: String,
    val preview: OnlineMediaRendition,
    val send: OnlineMediaRendition,
) {
    /**
     * What tells this item from the others of a result list. It is the slug,
     * or the url of the file to send for an item the provider gave no slug.
     */
    val key: String get() = slug.ifEmpty { send.url }
}

/** One page of results, in the provider's order. [hasNext] says whether [page] + 1 exists. */
data class OnlineMediaPage(
    val items: List<OnlineMedia>,
    val page: Int,
    val hasNext: Boolean,
)
