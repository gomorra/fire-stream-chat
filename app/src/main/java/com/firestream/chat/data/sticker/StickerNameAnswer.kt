package com.firestream.chat.data.sticker

import com.firestream.chat.domain.model.StickerAutoName
import com.firestream.chat.domain.util.StickerWords

/**
 * The check of what the naming service answered, before any of it reaches a row.
 *
 * An answer is untrusted like a pack manifest. A model wrote it after looking
 * at a picture, and the text on a sticker can be written to steer a model. So
 * an answer is taken whole or not at all: one field of the wrong type, one
 * string that is too long, one list that is too long or one emoji that is a
 * word drops all of it. Every string that is kept loses its control characters
 * ([cleanStickerText]).
 *
 * The naming service keeps to the same limits, so an answer it wrote passes.
 * It repeats them in `tools/sticker-namer/answer.py`. Change both together.
 */
internal object StickerNameAnswer {

    /** A name is two to four words. */
    const val MAX_NAME_LENGTH = 80

    /** The search words of both languages together. */
    const val MAX_WORDS = 16

    /** As long as a name a person types, so *Use as mine* cuts no word. */
    const val MAX_WORD_LENGTH = StickerWords.MAX_NAME_LENGTH

    /** The text read off the sticker. */
    const val MAX_TEXT_LENGTH = MAX_STICKER_TEXT

    const val MAX_EMOJIS = 3

    /**
     * What [fields] say the service found, or `null` for an answer that breaks
     * a limit, has a field of the wrong type, or holds nothing. A field that
     * is absent reads as empty.
     */
    fun of(fields: Map<String, Any?>): StickerAutoName? {
        val nameEn = string(fields["nameEn"], MAX_NAME_LENGTH) ?: return null
        val nameDe = string(fields["nameDe"], MAX_NAME_LENGTH) ?: return null
        val text = string(fields["text"], MAX_TEXT_LENGTH) ?: return null
        val words = list(fields["words"], MAX_WORDS, MAX_WORD_LENGTH) ?: return null
        val emojis = list(fields["emojis"], MAX_EMOJIS, StickerWords.MAX_EMOJI_LENGTH) ?: return null
        if (!emojis.all(::isEmoji)) return null
        return StickerAutoName(nameEn, nameDe, words, text, emojis).takeUnless { it.isEmpty }
    }

    /** [raw] cleaned, `""` when it is absent or blank, and `null` when it is no string or longer than [maxLength]. */
    private fun string(raw: Any?, maxLength: Int): String? = when {
        raw == null -> ""
        raw !is String || raw.length > maxLength -> null
        else -> cleanStickerText(raw).orEmpty()
    }

    /**
     * The strings of [raw], cleaned, each once and without the blank ones.
     * `null` when [raw] is no list, holds more than [maxCount] entries, or
     * holds one that is no string or longer than [maxLength].
     */
    private fun list(raw: Any?, maxCount: Int, maxLength: Int): List<String>? {
        if (raw == null) return emptyList()
        if (raw !is List<*> || raw.size > maxCount) return null
        val cleaned = LinkedHashSet<String>()
        for (entry in raw) {
            if (entry !is String || entry.length > maxLength) return null
            cleanStickerText(entry)?.let(cleaned::add)
        }
        return cleaned.toList()
    }

    /**
     * Whether [tag] is symbols and no word. The composer offers a sticker for
     * an emoji it is tagged with, so a tag that is a word would be offered for
     * nothing and found by nothing. An emoji has a character outside ASCII and
     * no letter. The letterlike symbols, such as the information sign, are the
     * one kind of letter an emoji is made of.
     */
    private fun isEmoji(tag: String): Boolean =
        tag.any { it.code > 0x7F } && tag.none { it.isWhitespace() || (it.isLetter() && it !in LETTERLIKE_SYMBOLS) }

    private val LETTERLIKE_SYMBOLS = '℀'..'⅏'
}
