package com.firestream.chat.domain.util

import com.firestream.chat.domain.model.Sticker
import com.firestream.chat.domain.model.StickerPackKind
import com.firestream.chat.test.fakes.testSticker
import com.firestream.chat.test.fakes.testStickerPack
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class StickerSearchTest {

    private fun pack(id: String, vararg stickers: Sticker) = testStickerPack(id, StickerPackKind.USER, *stickers)

    private val cat = testSticker("cat", "😺", "❤️")
    private val dog = testSticker("dog", "🐶")
    private val heart = testSticker("heart", "❤")
    private val untagged = testSticker("untagged")
    private val packs = listOf(pack("animals", cat, dog), pack("love", heart, cat, untagged))

    @Test
    fun `byEmojis returns the stickers tagged with any of the emojis, in pack order`() {
        val matches = StickerSearch.byEmojis(packs, listOf("🐶", "😺"))

        assertEquals(listOf("cat", "dog"), matches.map { it.sticker.id })
    }

    @Test
    fun `a sticker held by two packs is returned once, for the first pack`() {
        val matches = StickerSearch.byEmojis(packs, listOf("😺"))

        assertEquals(listOf(StickerMatch(cat, "animals")), matches)
    }

    @Test
    fun `the variation selector does not split a tag from its query`() {
        val withSelector = StickerSearch.byEmojis(packs, listOf("❤️")).map { it.sticker.id }
        val without = StickerSearch.byEmojis(packs, listOf("❤")).map { it.sticker.id }

        assertEquals(listOf("cat", "heart"), withSelector)
        assertEquals(withSelector, without)
    }

    @Test
    fun `no emojis find nothing, and an untagged sticker is never found`() {
        assertTrue(StickerSearch.byEmojis(packs, emptyList()).isEmpty())
        assertTrue(StickerSearch.byEmojis(packs, listOf("")).isEmpty())
        assertTrue(StickerSearch.byEmojis(packs, listOf("🦊")).isEmpty())
    }

    @Test
    fun `one emoji in the composer suggests its stickers`() {
        assertEquals(listOf("dog"), StickerSearch.suggestionsFor(" 🐶 ", packs).map { it.sticker.id })
    }

    @Test
    fun `text, two emojis and an empty composer suggest nothing`() {
        val tagged = listOf(pack("words", testSticker("hi", "hi"), dog))

        assertTrue(StickerSearch.suggestionsFor("", packs).isEmpty())
        assertTrue(StickerSearch.suggestionsFor("🐶 😺", packs).isEmpty())
        assertTrue(StickerSearch.suggestionsFor("🐶😺", packs).isEmpty())
        assertTrue(StickerSearch.suggestionsFor("good 🐶", packs).isEmpty())
        // A tag read from a file can be a word. Typing that word must not open the strip.
        assertTrue(StickerSearch.suggestionsFor("hi", tagged).isEmpty())
    }
}
