package com.firestream.chat.ui.chat

import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextRange
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ComposerValueTest {

    @Test
    fun `IME composition is preserved through a value rebuild`() {
        // The bug scenario: the composer rebuilt its TextFieldValue with
        // composition = null after every IME edit. Compose treats a dropped
        // composition (selection unchanged) as an external edit and calls
        // restartInput() — once per predictive keystroke on a healthy keyboard,
        // and in an endless frame-rate loop against a voice-wedged Gboard,
        // which left the field unable to accept input until a reboot.
        val value = buildComposerValue(
            annotated = AnnotatedString("hallo"),
            cursor = TextRange(5),
            composition = TextRange(0, 5),
        )

        assertEquals(TextRange(0, 5), value.composition)
        assertEquals(TextRange(5), value.selection)
        assertEquals("hallo", value.text)
    }

    @Test
    fun `null composition stays null`() {
        val value = buildComposerValue(
            annotated = AnnotatedString("abc"),
            cursor = TextRange(3),
            composition = null,
        )

        assertNull(value.composition)
    }

    @Test
    fun `composition beyond the text end is clamped`() {
        // A programmatic rewrite shrank the text while the IME still reported a
        // wider composing region — the region must never exceed the new bounds.
        val value = buildComposerValue(
            annotated = AnnotatedString("abcde"),
            cursor = TextRange(5),
            composition = TextRange(3, 9),
        )

        assertEquals(TextRange(3, 5), value.composition)
    }

    @Test
    fun `composition that collapses after clamping is dropped`() {
        val value = buildComposerValue(
            annotated = AnnotatedString("ab"),
            cursor = TextRange(2),
            composition = TextRange(4, 7),
        )

        assertNull(value.composition)
    }

    @Test
    fun `zero-length composition is dropped`() {
        val value = buildComposerValue(
            annotated = AnnotatedString("abc"),
            cursor = TextRange(3),
            composition = TextRange(2, 2),
        )

        assertNull(value.composition)
    }

    @Test
    fun `selection is clamped to text bounds like before`() {
        val value = buildComposerValue(
            annotated = AnnotatedString("ab"),
            cursor = TextRange(1, 9),
            composition = null,
        )

        assertEquals(TextRange(1, 2), value.selection)
    }
}
