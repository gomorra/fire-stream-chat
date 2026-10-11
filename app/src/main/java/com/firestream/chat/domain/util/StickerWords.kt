package com.firestream.chat.domain.util

import com.firestream.chat.domain.model.StickerAutoName

/**
 * The limits of the words a person gives a sticker, its names and its emojis,
 * and the two edits a screen makes to them. The repository cleans what it is
 * handed by the same rules, and so does a restore.
 */
object StickerWords {

    const val MAX_NAMES = 8
    const val MAX_NAME_LENGTH = 40

    /** As many as an import keeps, so an edit of an imported sticker drops none of its tags. */
    const val MAX_EMOJIS = 8
    const val MAX_EMOJI_LENGTH = 32

    /** [raw] without control characters, trimmed and capped at [MAX_NAME_LENGTH]. `null` when nothing is left. */
    fun cleanName(raw: String): String? =
        raw.filterNot { it.isISOControl() }
            .trim()
            .take(MAX_NAME_LENGTH)
            .trim()
            .takeIf { it.isNotEmpty() }

    /** The names of [raw] that are left after [cleanName], each once whatever its case, [MAX_NAMES] at most. */
    fun cleanNames(raw: List<String>): List<String> =
        raw.mapNotNull(::cleanName).distinctBy { it.lowercase() }.take(MAX_NAMES)

    /** The emojis of [raw], trimmed, each once, [max] at most. One that is blank or too long to be an emoji is left out. */
    fun cleanEmojis(raw: List<String>, max: Int = MAX_EMOJIS): List<String> =
        raw.map { it.trim() }.filter { it.length in 1..MAX_EMOJI_LENGTH }.distinct().take(max)

    /** [names] with [raw] added. Unchanged when [raw] is blank, is one of them already, or there is no room. */
    fun withName(names: List<String>, raw: String): List<String> {
        val name = cleanName(raw) ?: return names
        if (names.size >= MAX_NAMES || names.any { it.equals(name, ignoreCase = true) }) return names
        return names + name
    }

    /**
     * [names] with what was found automatically after them: the German name,
     * the English name and the search words. They are cleaned and capped like
     * typed names, so a sticker that has [MAX_NAMES] names takes none of them.
     */
    fun withAutoName(names: List<String>, found: StickerAutoName): List<String> =
        cleanNames(names + found.nameDe + found.nameEn + found.words)

    /** [emojis] with [emoji] added. Unchanged when it is one of them already, or [max] are there. */
    fun withEmoji(emojis: List<String>, emoji: String, max: Int = MAX_EMOJIS): List<String> {
        val added = cleanEmojis(listOf(emoji)).firstOrNull() ?: return emojis
        if (emojis.size >= max || added in emojis) return emojis
        return emojis + added
    }
}
