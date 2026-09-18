package com.firestream.chat.ui.chat

import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import java.text.BreakIterator

/**
 * Builds the composer's [TextFieldValue] from the separately-held text/cursor
 * state while preserving the IME's composing region.
 *
 * Preserving `composition` is not cosmetic: Compose's text-input layer treats
 * "composition changed while selection didn't" as an external edit and calls
 * `InputMethodManager.restartInput()`. Echoing `composition = null` after every
 * IME edit therefore restarts the input session once per predictive keystroke —
 * and with a keyboard that re-establishes its composing region on every attach
 * (Gboard after its voice-typing module wedges), it becomes a frame-rate
 * restartInput loop that leaves the field unable to accept input at all.
 * See docs/GOTCHAS.md.
 *
 * The composition is clamped to the text bounds and dropped when it collapses
 * (a zero-length composing region is meaningless), so programmatic rewrites
 * that shrink the text can never hand the IME an out-of-range region.
 */
internal fun buildComposerValue(
    annotated: AnnotatedString,
    cursor: TextRange,
    composition: TextRange?,
): TextFieldValue {
    val len = annotated.text.length
    val selection = TextRange(cursor.start.coerceIn(0, len), cursor.end.coerceIn(0, len))
    val safeComposition = composition
        ?.let { TextRange(it.start.coerceIn(0, len), it.end.coerceIn(0, len)) }
        ?.takeIf { !it.collapsed }
    return TextFieldValue(annotated, selection, safeComposition)
}

/**
 * The outcome of a programmatic composer edit: the new text, where the caret
 * lands, and the emoji-size map ([addEmojiSpans]'s charIndex → multiplier)
 * remapped onto the new indices.
 */
internal data class ComposerEdit(
    val text: String,
    val cursor: TextRange,
    val emojiSizes: Map<Int, Float>,
)

/**
 * Inserts [insertion] at the caret — replacing the selection when one is open —
 * instead of appending at the end, and leaves the caret directly after it.
 *
 * Emoji-size entries move with their emoji: those before the edit point stay,
 * those inside a replaced selection are dropped, and those after it shift by the
 * length change. [insertionSize] records a size for the inserted run itself
 * (1.0f = default, nothing recorded).
 */
internal fun insertAtCursor(
    text: String,
    selection: TextRange,
    insertion: String,
    emojiSizes: Map<Int, Float> = emptyMap(),
    insertionSize: Float = 1.0f,
): ComposerEdit {
    val start = selection.min.coerceIn(0, text.length)
    val end = selection.max.coerceIn(start, text.length)
    val edit = replaceComposerRange(text, start, end, insertion, emojiSizes)
    return if (insertionSize == 1.0f) edit
    else edit.copy(emojiSizes = edit.emojiSizes + (start to insertionSize))
}

/**
 * Deletes the selection, or — when the caret is collapsed — the whole grapheme
 * cluster before it, so backspace removes one visible character (a flag, a
 * skin-toned or ZWJ emoji) rather than a stray code unit. A caret at index 0
 * with no selection is a no-op.
 */
internal fun deleteBeforeCursor(
    text: String,
    selection: TextRange,
    emojiSizes: Map<Int, Float> = emptyMap(),
): ComposerEdit {
    val start = selection.min.coerceIn(0, text.length)
    val end = selection.max.coerceIn(start, text.length)
    if (start != end) return replaceComposerRange(text, start, end, "", emojiSizes)
    if (start == 0) return ComposerEdit(text, TextRange(0), emojiSizes)
    return replaceComposerRange(text, graphemeStartBefore(text, start), start, "", emojiSizes)
}

private const val ZERO_WIDTH_JOINER = '\u200D'

private fun isRegionalIndicator(codePoint: Int) = codePoint in 0x1F1E6..0x1F1FF

private fun isEmojiModifier(codePoint: Int) = codePoint in 0x1F3FB..0x1F3FF

/**
 * The start of the grapheme cluster that ends at [index] — what one backspace
 * has to remove.
 *
 * [BreakIterator] supplies the boundaries, but its grapheme data is the host's
 * JDK, and the hosts disagree about exactly the characters a chat composer is
 * full of. JDK 17, which CI runs, breaks 👨‍👩‍👧 into three faces and two joiners,
 * a flag into its two regional indicators and 👍🏽 into hand plus tone; JDK 21 and
 * Android's ICU keep each as one cluster. Backspace must eat one *visible*
 * character on all of them, so the three emoji joins are re-applied here rather
 * than trusted to the platform. On a host that already joins them the loop finds
 * nothing to do and leaves the boundary as it was. See docs/GOTCHAS.md.
 */
private fun graphemeStartBefore(text: String, index: Int): Int {
    val iterator = BreakIterator.getCharacterInstance().apply { setText(text) }
    fun boundaryBefore(at: Int) = iterator.preceding(at).takeIf { it != BreakIterator.DONE } ?: 0

    var start = boundaryBefore(index)
    while (start > 0) {
        // A joiner belongs to the sequence: take it and the component before it.
        if (text[start - 1] == ZERO_WIDTH_JOINER) {
            val joined = boundaryBefore(start - 1)
            if (joined >= start) break
            start = joined
            continue
        }
        val previous = boundaryBefore(start)
        if (previous >= start) break
        val codePoint = text.codePointAt(start)
        val joinsBackwards = isEmojiModifier(codePoint) ||
            // Regional indicators pair off from the start of their run (UAX #29
            // GB12/GB13), so this one closes a flag only if an odd number of them
            // precede it — otherwise it opens its own.
            (isRegionalIndicator(codePoint) &&
                isRegionalIndicator(text.codePointAt(previous)) &&
                regionalIndicatorsBefore(text, start) % 2 == 1)
        if (!joinsBackwards) break
        start = previous
    }
    return start
}

/** How many regional indicators run backwards from [index] without interruption. */
private fun regionalIndicatorsBefore(text: String, index: Int): Int {
    var count = 0
    var at = index
    while (at >= 2 && isRegionalIndicator(text.codePointAt(at - 2))) {
        count++
        at -= 2
    }
    return count
}

/** Splices [replacement] into `[start, end)` and shifts the emoji-size indices to match. */
private fun replaceComposerRange(
    text: String,
    start: Int,
    end: Int,
    replacement: String,
    emojiSizes: Map<Int, Float>,
): ComposerEdit {
    val newText = text.substring(0, start) + replacement + text.substring(end)
    val delta = replacement.length - (end - start)
    val shifted = when {
        emojiSizes.isEmpty() -> emojiSizes
        else -> emojiSizes.entries
            .filter { (index, _) -> index < start || index >= end }
            .associate { (index, size) -> (if (index >= end) index + delta else index) to size }
    }
    return ComposerEdit(newText, TextRange(start + replacement.length), shifted)
}
