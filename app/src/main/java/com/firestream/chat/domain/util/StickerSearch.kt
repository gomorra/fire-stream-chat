package com.firestream.chat.domain.util

import com.firestream.chat.domain.model.Sticker
import com.firestream.chat.domain.model.StickerPack
import java.text.Normalizer

/** A sticker a search found, with the pack it was found in. */
data class StickerMatch(val sticker: Sticker, val packId: String)

/** One pack's part of a search over the whole library: the pack, and those of its stickers that were found. */
data class StickerPackMatches(val pack: StickerPack, val stickers: List<Sticker>)

/**
 * Finds stickers by their words and by the emojis they are tagged with.
 *
 * **Text.** A query is cut into words. Every word has to match the start of a
 * word in the sticker's names, in what the naming service found (its two names,
 * its search words, the text on the sticker), or in the name of the pack. Case,
 * accents and umlauts are ignored, and `ß` equals `ss`.
 *
 * **Emojis.** The whole query also becomes emojis, through the emoji keyword
 * table the caller hands in, and a sticker tagged with one of them is found
 * too. The automatic emojis count as tags. Tags are compared without the
 * variation selector U+FE0F. One importer writes a heart with it and another
 * without.
 *
 * **Order.** A sticker found by its own names comes first, then one found by
 * the automatic group, then one found by its pack's name, and an emoji hit last.
 *
 * The object holds no keyword table and no screen text, so it stays pure: the
 * caller passes both in.
 */
object StickerSearch {

    /** The longest composer text that can be one emoji. A family with skin tones is 11 chars per member at most. */
    private const val MAX_SINGLE_EMOJI_CHARS = 32

    private const val RANK_OWN = 0
    private const val RANK_AUTOMATIC = 1
    private const val RANK_PACK = 2
    private const val RANK_EMOJI = 3

    /**
     * Every sticker in [packs] that [query] finds, best match first, and in pack
     * order among equals. A sticker held by several packs is returned once, for
     * the first pack that gives it its best match.
     *
     * [emojisFor] turns the query into emojis. [packName] is the name a pack is
     * shown by, which the two unnamed packs do not carry themselves.
     */
    fun find(
        packs: List<StickerPack>,
        query: String,
        emojisFor: (String) -> List<String>,
        packName: (StickerPack) -> String = { it.name },
    ): List<StickerMatch> {
        val rankOf = ranking(query, emojisFor) ?: return emptyList()
        val best = LinkedHashMap<String, Pair<Int, StickerMatch>>()
        for (pack in packs) {
            val packWords = wordsOf(packName(pack))
            for (sticker in pack.stickers) {
                val rank = rankOf(sticker, packWords) ?: continue
                val known = best[sticker.id]
                if (known == null || rank < known.first) best[sticker.id] = rank to StickerMatch(sticker, pack.id)
            }
        }
        // Stable, so equals keep the order they were found in.
        return best.values.sortedBy { it.first }.map { it.second }
    }

    /**
     * The library as sections, one per pack that has something to show, in the
     * order of [packs]. A blank [query] shows every sticker. Otherwise a section
     * holds what [query] finds in its pack, best match first, so a pack whose
     * name the query matches shows whole.
     *
     * A sticker that two packs hold is in both sections. [find] returns it
     * once, because a pick needs one pack. Here each is its own entry.
     */
    fun sections(
        packs: List<StickerPack>,
        query: String,
        emojisFor: (String) -> List<String>,
        packName: (StickerPack) -> String = { it.name },
    ): List<StickerPackMatches> {
        if (query.isBlank()) return packs.filter { it.stickers.isNotEmpty() }.map { StickerPackMatches(it, it.stickers) }
        val rankOf = ranking(query, emojisFor) ?: return emptyList()
        return packs.mapNotNull { pack ->
            val packWords = wordsOf(packName(pack))
            val found = pack.stickers.mapNotNull { sticker -> rankOf(sticker, packWords)?.let { it to sticker } }
            if (found.isEmpty()) null else StickerPackMatches(pack, found.sortedBy { it.first }.map { it.second })
        }
    }

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
     * nothing to look for. A sticker's own emojis and its automatic ones both count.
     */
    fun taggedWith(emojis: Collection<String>): ((Sticker) -> Boolean)? {
        val wanted = emojis.mapTo(HashSet()) { it.normalised() }
        wanted.remove("")
        if (wanted.isEmpty()) return null
        return { sticker ->
            sticker.emojis.any { it.normalised() in wanted } ||
                sticker.autoName?.emojis?.any { it.normalised() in wanted } == true
        }
    }

    /**
     * The stickers to offer above the composer: those tagged with exactly [text],
     * when [text] is one emoji.
     *
     * A tag comes from an imported file or from the naming service, and is not
     * checked to be an emoji. So a text with a letter or a digit in it never
     * matches, and typing a word that happens to be someone's tag offers nothing.
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

    /**
     * How well [query] finds a sticker in a pack whose name has the given words:
     * a rank, lower is better, or `null` for a sticker it does not find. The
     * function itself is `null` when [query] has nothing to look for.
     */
    private fun ranking(query: String, emojisFor: (String) -> List<String>): ((Sticker, List<String>) -> Int?)? {
        val trimmed = query.trim()
        val words = wordsOf(trimmed)
        val isTagged = taggedWith(emojisFor(trimmed) + trimmed)
        if (words.isEmpty() && isTagged == null) return null
        return { sticker, packWords ->
            textRank(sticker, packWords, words) ?: if (isTagged?.invoke(sticker) == true) RANK_EMOJI else null
        }
    }

    /** The rank of the worst-placed word of [words], or `null` when one of them is found nowhere, or there are none. */
    private fun textRank(sticker: Sticker, packWords: List<String>, words: List<String>): Int? {
        if (words.isEmpty()) return null
        val own = sticker.names.flatMap(::wordsOf)
        val automatic by lazy(LazyThreadSafetyMode.NONE) {
            sticker.autoName?.let { auto -> (auto.words + auto.nameEn + auto.nameDe + auto.text).flatMap(::wordsOf) }.orEmpty()
        }
        var worst = RANK_OWN
        for (word in words) {
            val rank = when {
                own.any { it.startsWith(word) } -> RANK_OWN
                automatic.any { it.startsWith(word) } -> RANK_AUTOMATIC
                packWords.any { it.startsWith(word) } -> RANK_PACK
                else -> return null
            }
            if (rank > worst) worst = rank
        }
        return worst
    }

    /** The words of [text], folded so that case, accents and umlauts do not matter and `ß` is `ss`. */
    private fun wordsOf(text: String): List<String> {
        if (text.isEmpty()) return emptyList()
        return text.folded().split(NOT_A_WORD).filter { it.isNotEmpty() }
    }

    private fun String.folded(): String {
        val lower = lowercase()
        // Most names are plain ASCII, and decomposing is the costly part.
        if (lower.all { it.code < 128 }) return lower
        return Normalizer.normalize(lower.replace("ß", "ss"), Normalizer.Form.NFD).replace(COMBINING_MARK, "")
    }

    private fun String.normalised(): String {
        val trimmed = trim()
        return if (VARIATION_SELECTOR in trimmed) trimmed.replace(VARIATION_SELECTOR.toString(), "") else trimmed
    }

    private const val VARIATION_SELECTOR = '️'
    private val NOT_A_WORD = Regex("[^\\p{L}\\p{N}]+")
    private val COMBINING_MARK = Regex("\\p{M}+")
}
