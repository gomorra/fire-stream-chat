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

    // --- ZWJ / multi-code-point sequences must stay ONE span ---------------
    // Compose breaks text shaping at every span boundary, so a sequence split
    // across several spans renders as its components: ❤️‍🔥 as ❤️ + 🔥.

    @Test
    fun `addEmojiSpans keeps a ZWJ sequence in one span`() {
        // Heart on fire: U+2764 U+FE0F U+200D U+1F525
        val source = "\u2764\uFE0F\u200D\uD83D\uDD25"
        val result = addEmojiSpans(source, SIZE)
        assertEquals("Expected one span for the whole sequence", 1, result.spanStyles.size)
        assertEquals(0, result.spanStyles[0].start)
        assertEquals(source.length, result.spanStyles[0].end)
    }

    @Test
    fun `addEmojiSpans keeps a family ZWJ sequence in one span`() {
        val source = "\uD83D\uDC68\u200D\uD83D\uDC69\u200D\uD83D\uDC67"
        val result = addEmojiSpans(source, SIZE)
        assertEquals(1, result.spanStyles.size)
        assertEquals(source.length, result.spanStyles[0].end)
    }

    @Test
    fun `addEmojiSpans keeps a skin-toned emoji in one span`() {
        val source = "\uD83D\uDC4D\uD83C\uDFFD"  // 👍🏽
        val result = addEmojiSpans(source, SIZE)
        assertEquals(1, result.spanStyles.size)
        assertEquals(source.length, result.spanStyles[0].end)
    }

    @Test
    fun `addEmojiSpans keeps a flag in one span`() {
        val source = "\uD83C\uDDFA\uD83C\uDDF8"  // 🇺🇸
        val result = addEmojiSpans(source, SIZE)
        assertEquals(1, result.spanStyles.size)
        assertEquals(source.length, result.spanStyles[0].end)
    }

    @Test
    fun `addEmojiSpans spans adjacent emoji separately`() {
        val result = addEmojiSpans("\uD83D\uDE00\uD83C\uDF89", SIZE)  // 😀🎉
        assertEquals(2, result.spanStyles.size)
    }

    @Test
    fun `addEmojiSpans applies the size multiplier to the whole sequence`() {
        val source = "\u2764\uFE0F\u200D\uD83D\uDD25"
        val result = addEmojiSpans(source, SIZE, mapOf(0 to 2f))
        assertEquals(1, result.spanStyles.size)
        assertEquals(SIZE * 2f, result.spanStyles[0].item.fontSize)
        assertEquals(source.length, result.spanStyles[0].end)
    }

    @Test
    fun `isEmojiOnly returns true for heart on fire`() {
        assertTrue(isEmojiOnly("\u2764\uFE0F\u200D\uD83D\uDD25"))
    }

    // --- Text-default bases (arrows, ▪ ◻, ©, ™ …) are emoji only with U+FE0F ---
    // Head shaking vertically/horizontally (Emoji 15.1) put an arrow after the
    // joiner. With the arrow outside EMOJI_BASE the sequence split into 🙂 and a
    // separate ↕️ in the bubble, while the unstyled reply header drew it whole.

    @Test
    fun `addEmojiSpans keeps head shaking vertically in one span`() {
        val source = "🙂‍↕️"  // 🙂‍↕️
        val result = addEmojiSpans(source, SIZE)
        assertEquals(1, result.spanStyles.size)
        assertEquals(0, result.spanStyles[0].start)
        assertEquals(source.length, result.spanStyles[0].end)
        assertTrue(isEmojiOnly(source))
    }

    @Test
    fun `addEmojiSpans keeps head shaking horizontally in one span`() {
        val source = "🙂‍↔️"  // 🙂‍↔️
        val result = addEmojiSpans(source, SIZE)
        assertEquals(1, result.spanStyles.size)
        assertEquals(source.length, result.spanStyles[0].end)
        assertTrue(isEmojiOnly(source))
    }

    @Test
    fun `isEmojiOnly returns true for arrow and square emoji`() {
        assertTrue(isEmojiOnly("↗️"))   // ↗️
        assertTrue(isEmojiOnly("⤴️"))   // ⤴️
        assertTrue(isEmojiOnly("▪️"))   // ▪️
        assertTrue(isEmojiOnly("◾◽"))   // ◾◽ (emoji presentation by default)
    }

    @Test
    fun `plain text arrow without variation selector is not an emoji`() {
        assertFalse(isEmojiOnly("→"))  // →
        assertEquals(0, addEmojiSpans("a → b", SIZE).spanStyles.size)
    }

    companion object {
        private val SIZE = androidx.compose.ui.unit.TextUnit(
            18f,
            androidx.compose.ui.unit.TextUnitType.Sp
        )
    }
}
