package com.firestream.chat.domain.util

import java.nio.ByteBuffer
import java.nio.charset.Charset
import java.nio.charset.CodingErrorAction

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

/**
 * Decodes the head of a text file for the bubble's preview. Pure, so the rule
 * is testable without a device: at most [MAX_BYTES] are ever read, a file that
 * looks binary gives no preview, and a character cut in half by the limit is
 * dropped rather than shown as a replacement mark.
 */
object TextPreview {

    /**
     * How much of a file the preview holds, collapsed or expanded. Enough for a
     * few hundred lines — the expanded bubble stays scrollable with the chat —
     * and a bound on what one bubble can cost to lay out.
     */
    const val MAX_BYTES = 32 * 1024

    /** Lines shown before *Show more*. */
    const val COLLAPSED_LINES = 10

    /** Bytes inspected for NUL — a text file has none, nearly every binary format has some early. */
    private const val SNIFF_BYTES = 8 * 1024

    /**
     * The preview of a file whose first bytes are [head] (at most [MAX_BYTES]
     * of them) and whose full size is [fileLength]; `null` when it is binary or
     * empty.
     */
    fun decode(head: ByteArray, fileLength: Long): FilePreview.Text? {
        if (head.isEmpty()) return null
        val sniff = minOf(head.size, SNIFF_BYTES)
        val (charset, bomLength) = charsetOf(head)
        // UTF-16 text is full of NULs by nature; only the 8-bit encodings are sniffed.
        if (charset == Charsets.UTF_8 && (0 until sniff).any { head[it] == 0.toByte() }) return null

        val decoder = charset.newDecoder()
            .onMalformedInput(CodingErrorAction.REPLACE)
            .onUnmappableCharacter(CodingErrorAction.REPLACE)
        val text = decoder.decode(ByteBuffer.wrap(head, bomLength, head.size - bomLength)).toString()
        val truncated = fileLength > head.size
        val cleaned = text
            .let { if (truncated) it.trimEnd('\uFFFD') else it }
            .replace("\r\n", "\n")
            .replace('\r', '\n')
            .trimEnd()
        if (cleaned.isEmpty()) return null
        // Mostly replacement marks: it was not text in this encoding after all.
        if (cleaned.count { it == '\uFFFD' } > cleaned.length / 10) return null
        return FilePreview.Text(cleaned, truncated)
    }

    /**
     * The preview of text already decoded elsewhere — a PDF's text layer.
     * [moreFollows]: the source goes on past what [text] covers. Held to the
     * same [MAX_BYTES] budget, counted in characters.
     */
    fun fromText(text: String, moreFollows: Boolean): FilePreview.Text? {
        val cleaned = text.replace("\r\n", "\n").replace('\r', '\n')
            // PDF text layers carry runs of blank lines between blocks.
            .replace(Regex("\n{3,}"), "\n\n")
            .trim()
        if (cleaned.isEmpty()) return null
        val cut = cleaned.length > MAX_BYTES
        return FilePreview.Text(if (cut) cleaned.take(MAX_BYTES).trimEnd() else cleaned, truncated = cut || moreFollows)
    }

    private fun charsetOf(head: ByteArray): Pair<Charset, Int> = when {
        head.size >= 3 && head[0] == 0xEF.toByte() && head[1] == 0xBB.toByte() && head[2] == 0xBF.toByte() ->
            Charsets.UTF_8 to 3
        head.size >= 2 && head[0] == 0xFF.toByte() && head[1] == 0xFE.toByte() -> Charsets.UTF_16LE to 2
        head.size >= 2 && head[0] == 0xFE.toByte() && head[1] == 0xFF.toByte() -> Charsets.UTF_16BE to 2
        else -> Charsets.UTF_8 to 0
    }
}
