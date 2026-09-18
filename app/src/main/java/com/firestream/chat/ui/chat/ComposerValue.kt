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
    val iterator = BreakIterator.getCharacterInstance().apply { setText(text) }
    val boundary = iterator.preceding(start).takeIf { it != BreakIterator.DONE } ?: 0
    return replaceComposerRange(text, boundary, start, "", emojiSizes)
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
