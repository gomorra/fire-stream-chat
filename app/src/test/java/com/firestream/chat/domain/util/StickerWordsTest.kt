package com.firestream.chat.domain.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Test

class StickerWordsTest {

    @Test
    fun `a name is trimmed, loses control characters and is capped at forty characters`() {
        assertEquals("Grumpy cat", StickerWords.cleanName("  Grumpy\u0000 cat\n "))
        assertEquals(StickerWords.MAX_NAME_LENGTH, StickerWords.cleanName("x".repeat(100))!!.length)
        // The cut must not leave a space at the end.
        assertEquals("a".repeat(39), StickerWords.cleanName("a".repeat(39) + " b"))
        assertNull(StickerWords.cleanName(" \t "))
    }

    @Test
    fun `names are kept once whatever their case, eight at most, in the order given`() {
        assertEquals(listOf("Cat", "Dog"), StickerWords.cleanNames(listOf("Cat", " ", "cat ", "Dog")))
        assertEquals((1..8).map { "n$it" }, StickerWords.cleanNames((1..20).map { "n$it" }))
    }

    @Test
    fun `emojis are trimmed and kept once, and one that is too long to be an emoji is left out`() {
        assertEquals(listOf("😺", "🐶"), StickerWords.cleanEmojis(listOf(" 😺", "", "😺", "x".repeat(33), "🐶")))
        assertEquals(listOf("1", "2", "3"), StickerWords.cleanEmojis(listOf("1", "2", "3", "4"), max = 3))
    }

    @Test
    fun `a name is added at the end, unless it is blank, a repeat or one too many`() {
        val names = listOf("Cat")

        assertEquals(listOf("Cat", "Dog"), StickerWords.withName(names, " Dog "))
        assertSame(names, StickerWords.withName(names, "  "))
        assertSame(names, StickerWords.withName(names, "CAT"))
        val full = (1..StickerWords.MAX_NAMES).map { "n$it" }
        assertSame(full, StickerWords.withName(full, "one more"))
    }

    @Test
    fun `an emoji is added at the end, unless it is a repeat or one too many`() {
        val emojis = listOf("😺")

        assertEquals(listOf("😺", "🐶"), StickerWords.withEmoji(emojis, "🐶"))
        assertSame(emojis, StickerWords.withEmoji(emojis, "😺"))
        assertSame(emojis, StickerWords.withEmoji(emojis, "🐶", max = 1))
    }
}
