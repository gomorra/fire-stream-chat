package com.firestream.chat.ui.chat

import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue

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
