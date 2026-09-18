package com.firestream.chat.ui.chat

import androidx.compose.ui.text.TextRange
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Regression coverage for the picker inserting emojis at the end of the composer
 * instead of at the caret: "hello world" with the caret between the words had to
 * become "hello 🙂world", not "hello world🙂".
 */
class ComposerEditTest {

    @Test
    fun `emoji lands at the caret, not at the end of the text`() {
        val edit = insertAtCursor(
            text = "hello world",
            selection = TextRange(6),
            insertion = "🙂",
        )

        assertEquals("hello 🙂world", edit.text)
        assertEquals(TextRange(8), edit.cursor)
    }

    @Test
    fun `caret at the end still appends`() {
        val edit = insertAtCursor(
            text = "hi",
            selection = TextRange(2),
            insertion = "🙂",
        )

        assertEquals("hi🙂", edit.text)
        assertEquals(TextRange(4), edit.cursor)
    }

    @Test
    fun `emoji replaces an open selection`() {
        val edit = insertAtCursor(
            text = "hello world",
            selection = TextRange(6, 11),
            insertion = "🙂",
        )

        assertEquals("hello 🙂", edit.text)
        assertEquals(TextRange(8), edit.cursor)
    }

    @Test
    fun `a stale caret past the text end is clamped`() {
        val edit = insertAtCursor(
            text = "hi",
            selection = TextRange(40),
            insertion = "!",
        )

        assertEquals("hi!", edit.text)
        assertEquals(TextRange(3), edit.cursor)
    }

    @Test
    fun `a reversed selection is handled from its lower bound`() {
        val edit = insertAtCursor(
            text = "hello world",
            selection = TextRange(11, 6),
            insertion = "there",
        )

        assertEquals("hello there", edit.text)
        assertEquals(TextRange(11), edit.cursor)
    }

    @Test
    fun `an oversized emoji records its size at the insertion point`() {
        val edit = insertAtCursor(
            text = "hello world",
            selection = TextRange(6),
            insertion = "🙂",
            insertionSize = 2.0f,
        )

        assertEquals(mapOf(6 to 2.0f), edit.emojiSizes)
    }

    @Test
    fun `a default-sized emoji records nothing`() {
        val edit = insertAtCursor(
            text = "a",
            selection = TextRange(1),
            insertion = "🙂",
            insertionSize = 1.0f,
        )

        assertEquals(emptyMap<Int, Float>(), edit.emojiSizes)
    }

    @Test
    fun `existing emoji sizes shift when text is inserted in front of them`() {
        // "ab🙂" with the 🙂 sized 3× — inserting at index 1 pushes it forward.
        val edit = insertAtCursor(
            text = "ab🙂",
            selection = TextRange(1),
            insertion = "XY",
            emojiSizes = mapOf(2 to 3.0f),
        )

        assertEquals("aXYb🙂", edit.text)
        assertEquals(mapOf(4 to 3.0f), edit.emojiSizes)
    }

    @Test
    fun `an emoji size at the caret shifts with its emoji`() {
        val edit = insertAtCursor(
            text = "🙂",
            selection = TextRange(0),
            insertion = "hi",
            emojiSizes = mapOf(0 to 2.0f),
        )

        assertEquals("hi🙂", edit.text)
        assertEquals(mapOf(2 to 2.0f), edit.emojiSizes)
    }

    @Test
    fun `sizes of emojis inside a replaced selection are dropped`() {
        val edit = insertAtCursor(
            text = "a🙂b",
            selection = TextRange(1, 3),
            insertion = "X",
            emojiSizes = mapOf(1 to 2.0f),
        )

        assertEquals("aXb", edit.text)
        assertEquals(emptyMap<Int, Float>(), edit.emojiSizes)
    }

    @Test
    fun `backspace deletes the grapheme before the caret, not the last one`() {
        // Caret after "hello": the "o" goes, not the trailing "d".
        val edit = deleteBeforeCursor(
            text = "hello world",
            selection = TextRange(5),
        )

        assertEquals("hell world", edit.text)
        assertEquals(TextRange(4), edit.cursor)
    }

    @Test
    fun `backspace removes a whole surrogate pair`() {
        val edit = deleteBeforeCursor(
            text = "hi🙂",
            selection = TextRange(4),
        )

        assertEquals("hi", edit.text)
        assertEquals(TextRange(2), edit.cursor)
    }

    @Test
    fun `backspace removes a whole ZWJ emoji sequence`() {
        // 👨‍👩‍👧 — three code points joined by ZWJ, one visible character.
        val family = "👨‍👩‍👧"
        val edit = deleteBeforeCursor(
            text = "a$family",
            selection = TextRange(1 + family.length),
        )

        assertEquals("a", edit.text)
        assertEquals(TextRange(1), edit.cursor)
    }

    @Test
    fun `backspace with an open selection deletes the selection`() {
        val edit = deleteBeforeCursor(
            text = "hello world",
            selection = TextRange(5, 11),
        )

        assertEquals("hello", edit.text)
        assertEquals(TextRange(5), edit.cursor)
    }

    @Test
    fun `backspace at the start of the text is a no-op`() {
        val sizes = mapOf(0 to 2.0f)
        val edit = deleteBeforeCursor(
            text = "🙂",
            selection = TextRange(0),
            emojiSizes = sizes,
        )

        assertEquals("🙂", edit.text)
        assertEquals(TextRange(0), edit.cursor)
        assertEquals(sizes, edit.emojiSizes)
    }

    @Test
    fun `backspace drops the deleted emoji's size and shifts later ones down`() {
        // "🙂x🙂" — both emojis sized; deleting the first one must drop its entry
        // and pull the trailing one back by two chars.
        val edit = deleteBeforeCursor(
            text = "🙂x🙂",
            selection = TextRange(2),
            emojiSizes = mapOf(0 to 2.0f, 3 to 4.0f),
        )

        assertEquals("x🙂", edit.text)
        assertEquals(mapOf(1 to 4.0f), edit.emojiSizes)
    }

    @Test
    fun `backspace in the middle keeps the sizes of untouched emojis`() {
        val edit = deleteBeforeCursor(
            text = "ab🙂",
            selection = TextRange(1),
            emojiSizes = mapOf(2 to 2.0f),
        )

        assertEquals("b🙂", edit.text)
        assertEquals(mapOf(1 to 2.0f), edit.emojiSizes)
    }
}
