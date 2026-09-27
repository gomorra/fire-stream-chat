package com.firestream.chat.domain.util

/**
 * What a file bubble can show of a document's contents. Built off the main
 * thread from the local copy (`FilePreviewLoader`) and cached per message.
 */
sealed interface FilePreview {
    /** Nothing to show beyond the card — an unsupported kind, a binary file, no local copy yet. */
    data object None : FilePreview

    /**
     * The first [text] of the file, at most [TextPreview.MAX_BYTES] of it.
     * [truncated]: the file goes on past what the preview holds.
     */
    data class Text(val text: String, val truncated: Boolean) : FilePreview

    /**
     * A PDF: its first page rendered to [thumbnailPath] (`null` when it would not
     * render), its [pageCount], and the text of its first pages when it has any
     * (a scan has none).
     */
    data class Pdf(
        val thumbnailPath: String?,
        val thumbnailAspect: Float,
        val pageCount: Int,
        val text: Text?,
    ) : FilePreview
}
