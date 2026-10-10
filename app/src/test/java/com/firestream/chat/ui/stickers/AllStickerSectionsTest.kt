package com.firestream.chat.ui.stickers

import com.firestream.chat.domain.model.StickerPackKind
import com.firestream.chat.test.fakes.testSticker
import com.firestream.chat.test.fakes.testStickerPack
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AllStickerSectionsTest {

    private val cat = testSticker("cat", "😺")
    private val dog = testSticker("dog", "🐶")
    private val heart = testSticker("heart", "❤️")
    private val untagged = testSticker("untagged")

    private val favourites = testStickerPack("f", StickerPackKind.FAVOURITES, cat)
    private val animals = testStickerPack("Animals", StickerPackKind.USER, cat, dog)
    private val love = testStickerPack("Love", StickerPackKind.USER, heart, untagged)
    private val empty = testStickerPack("Empty")
    private val packs = listOf(favourites, animals, empty, love)

    /** A stand-in for the emoji keyword table. */
    private val emojisFor: (String) -> List<String> = { word -> if (word == "cat") listOf("😺") else emptyList() }

    private fun sections(query: String) = allStickerSections(packs, query, emojisFor).map { section ->
        section.pack.id to section.stickers.map { it.id }
    }

    @Test
    fun `a blank query shows every sticker under its pack and leaves out an empty pack`() {
        val all = listOf("f" to listOf("cat"), "Animals" to listOf("cat", "dog"), "Love" to listOf("heart", "untagged"))

        assertEquals(all, sections(""))
        assertEquals(all, sections("  "))
    }

    @Test
    fun `a word finds the stickers tagged with its emojis, in every pack that holds one`() {
        // The cat is in two packs, and each is its own entry.
        assertEquals(listOf("f" to listOf("cat"), "Animals" to listOf("cat")), sections(" cat "))
    }

    @Test
    fun `an emoji finds its stickers with or without the variation selector`() {
        assertEquals(listOf("Love" to listOf("heart")), sections("❤"))
        assertEquals(listOf("Love" to listOf("heart")), sections("❤️"))
    }

    @Test
    fun `a pack's name shows the whole pack, whatever the case`() {
        assertEquals(listOf("Love" to listOf("heart", "untagged")), sections("lov"))
        // An unnamed pack is found by the name the screen shows.
        assertEquals(listOf("f" to listOf("cat")), sections("favour"))
    }

    @Test
    fun `a query that names nothing shows nothing`() {
        assertTrue(sections("fox").isEmpty())
        // An empty pack has nothing to show, also when its name matches.
        assertTrue(sections("Empty").isEmpty())
    }
}
