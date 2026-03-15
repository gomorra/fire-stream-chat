package com.firestream.chat.ui.chat

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ChatUtilsTest {

    @Test
    fun `isEmojiOnly returns true for single emoji`() {
        assertTrue(isEmojiOnly("😀"))
    }

    @Test
    fun `isEmojiOnly returns true for multiple emoji`() {
        assertTrue(isEmojiOnly("😀🎉🔥"))
    }

    @Test
    fun `isEmojiOnly returns true for emoji with spaces`() {
        assertTrue(isEmojiOnly("😀 🎉"))
    }

    @Test
    fun `isEmojiOnly returns false for mixed text and emoji`() {
        assertFalse(isEmojiOnly("Hello 😀"))
    }

    @Test
    fun `isEmojiOnly returns false for plain text`() {
        assertFalse(isEmojiOnly("Hello world"))
    }

    @Test
    fun `isEmojiOnly returns false for blank string`() {
        assertFalse(isEmojiOnly(""))
        assertFalse(isEmojiOnly("   "))
    }

    @Test
    fun `isEmojiOnly returns true for heart emoji`() {
        // ❤ is U+2764, in the Misc Symbols range
        assertTrue(isEmojiOnly("❤"))
    }

    @Test
    fun `isEmojiOnly returns true for star emoji`() {
        // ⭐ is U+2B50, in the Misc Symbols & Arrows range
        assertTrue(isEmojiOnly("⭐"))
    }

    @Test
    fun `isEmojiOnly returns true for ZWJ sequence`() {
        // Family emoji: 👨‍👩‍👧 (man + ZWJ + woman + ZWJ + girl)
        assertTrue(isEmojiOnly("👨\u200D👩\u200D👧"))
    }

    @Test
    fun `isEmojiOnly returns true for emoji with variation selector`() {
        // ❤️ is ❤ + VS-16
        assertTrue(isEmojiOnly("❤\uFE0F"))
    }

    @Test
    fun `isEmojiOnly returns true for flag emoji`() {
        // 🇺🇸 is two regional indicators
        assertTrue(isEmojiOnly("🇺🇸"))
    }

    @Test
    fun `addEmojiSpans finds emoji in mixed text`() {
        val source = androidx.compose.ui.text.AnnotatedString("Hello 😀 world")
        val result = addEmojiSpans(source, androidx.compose.ui.unit.TextUnit(18f, androidx.compose.ui.unit.TextUnitType.Sp))
        // The result should have span styles applied to the emoji range
        val spans = result.spanStyles
        assertTrue("Expected at least one span for the emoji", spans.isNotEmpty())
    }

    @Test
    fun `addEmojiSpans returns unchanged string when no emoji`() {
        val source = androidx.compose.ui.text.AnnotatedString("Hello world")
        val result = addEmojiSpans(source, androidx.compose.ui.unit.TextUnit(18f, androidx.compose.ui.unit.TextUnitType.Sp))
        val spans = result.spanStyles
        assertEquals("Expected no spans for plain text", 0, spans.size)
    }
}
