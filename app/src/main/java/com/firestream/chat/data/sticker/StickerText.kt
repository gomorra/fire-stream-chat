package com.firestream.chat.data.sticker

/** The longest pack name, publisher or pack id the library keeps. */
internal const val MAX_STICKER_TEXT = 128

/**
 * A pack name, publisher or pack id as the library stores it: without control
 * characters, trimmed and capped at [MAX_STICKER_TEXT]. `null` when nothing is
 * left. Every text that reaches a pack row goes through here, whether a file,
 * an archive or the user supplied it.
 */
internal fun cleanStickerText(raw: String): String? =
    raw.filterNot { it.isISOControl() }
        .trim()
        .take(MAX_STICKER_TEXT)
        .takeIf { it.isNotEmpty() }
