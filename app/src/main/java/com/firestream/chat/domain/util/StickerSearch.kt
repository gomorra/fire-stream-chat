package com.firestream.chat.domain.util

import com.firestream.chat.domain.model.Sticker
import com.firestream.chat.domain.model.StickerPack

/** A sticker a search found, with the pack it was found in. */
data class StickerMatch(val sticker: Sticker, val packId: String)

/**
 * Finds stickers by the emojis they are tagged with.
 *
 * A sticker has no name, only emoji tags. A text query therefore becomes emojis
 * first, through the emoji keyword table, and the caller hands those in. This
 * object compares tags only, so it needs no keyword table and stays pure.
 *
 * Tags are compared without the variation selector U+FE0F. One importer writes
 * a heart with it and another without.
 */
object StickerSearch {

    /** The longest composer text that can be one emoji. A family with skin tones is 11 chars per member at most. */
    private const val MAX_SINGLE_EMOJI_CHARS = 32

    /**
     * Every sticker in [packs] tagged with one of [emojis], in pack order.
     * A sticker held by several packs is returned once, for the first of them.
     */
    fun byEmojis(packs: List<StickerPack>, emojis: Collection<String>): List<StickerMatch> {
        val isTagged = taggedWith(emojis) ?: return emptyList()
        val seen = HashSet<String>()
        return buildList {
            for (pack in packs) {
                for (sticker in pack.stickers) {
                    if (isTagged(sticker) && seen.add(sticker.id)) add(StickerMatch(sticker, pack.id))
                }
            }
        }
    }

    /**
     * A test for "tagged with one of [emojis]", or `null` when [emojis] names
     * nothing to look for. For a caller that filters each pack by itself.
     */
    fun taggedWith(emojis: Collection<String>): ((Sticker) -> Boolean)? {
        val wanted = emojis.mapTo(HashSet()) { it.normalised() }
        wanted.remove("")
        if (wanted.isEmpty()) return null
        return { sticker -> sticker.emojis.any { it.normalised() in wanted } }
    }

    /**
     * The stickers to offer above the composer: those tagged with exactly [text],
     * when [text] is one emoji.
     *
     * A tag comes from an imported file and is not checked to be an emoji. So a
     * text with a letter or a digit in it never matches, and typing a word that
     * happens to be someone's tag offers nothing.
     */
    fun suggestionsFor(text: String, packs: List<StickerPack>): List<StickerMatch> =
        suggestionKey(text)?.let { byEmojis(packs, listOf(it)) }.orEmpty()

    /**
     * The text [suggestionsFor] would look up, or null when [text] cannot be one
     * emoji. Cheap, so a caller can run it on every keystroke and search the
     * library only when the answer changes.
     */
    fun suggestionKey(text: String): String? {
        val emoji = text.trim()
        if (emoji.isEmpty() || emoji.length > MAX_SINGLE_EMOJI_CHARS) return null
        if (emoji.any { it.isLetterOrDigit() || it.isWhitespace() }) return null
        return emoji
    }

    private fun String.normalised(): String {
        val trimmed = trim()
        return if (VARIATION_SELECTOR in trimmed) trimmed.replace(VARIATION_SELECTOR.toString(), "") else trimmed
    }

    private const val VARIATION_SELECTOR = '️'
}
