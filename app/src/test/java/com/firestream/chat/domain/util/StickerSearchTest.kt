package com.firestream.chat.domain.util

import com.firestream.chat.domain.model.Sticker
import com.firestream.chat.domain.model.StickerAutoName
import com.firestream.chat.domain.model.StickerPack
import com.firestream.chat.domain.model.StickerPackKind
import com.firestream.chat.test.fakes.testSticker
import com.firestream.chat.test.fakes.testStickerPack
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
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
    fun `taggedWith tests one sticker, and is null when there is nothing to look for`() {
        val isHeart = StickerSearch.taggedWith(listOf("❤"))!!

        assertTrue(isHeart(cat))
        assertTrue(isHeart(heart))
        assertFalse(isHeart(dog))
        assertFalse(isHeart(untagged))
        assertNull(StickerSearch.taggedWith(listOf("", " ")))
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

    @Test
    fun `an automatic emoji counts as a tag, for a search and for the composer`() {
        val named = untagged.copy(id = "sloth", autoName = StickerAutoName(emojis = listOf("🦥")))
        val library = listOf(pack("zoo", dog, named))

        assertEquals(listOf("sloth"), StickerSearch.byEmojis(library, listOf("🦥")).map { it.sticker.id })
        assertEquals(listOf("sloth"), StickerSearch.suggestionsFor("🦥", library).map { it.sticker.id })
    }

    // --- The text search ---

    private val grumpy = testSticker("grumpy", "😾").copy(names = listOf("Grumpy cat", "Montag"))
    private val street = testSticker("street").copy(names = listOf("Große Straße"))
    private val bear = testSticker("bear").copy(
        autoName = StickerAutoName(
            nameEn = "Sleepy bear",
            nameDe = "Müder Bär",
            words = listOf("tired", "Schlaf", "cat nap"),
            text = "Gute Nacht",
        ),
    )
    private val plain = testSticker("plain")
    private val tagged = testSticker("tagged", "😺")
    private val library = listOf(
        pack("Büro", plain, tagged),
        pack("Cats", bear, grumpy),
        pack("Roads", street, grumpy),
    )

    /** A stand-in for the emoji keyword table. */
    private val emojisFor: (String) -> List<String> = { word -> if (word == "cat") listOf("😺") else emptyList() }

    private fun find(query: String) = StickerSearch.find(library, query, emojisFor).map { it.sticker.id }

    @Test
    fun `a query finds stickers by the start of a word, whatever the case, accents and umlauts`() {
        val cases = listOf(
            // The start of a word in a name, not its middle.
            "grum" to listOf("grumpy"),
            "GRUMPY" to listOf("grumpy"),
            "umpy" to emptyList(),
            // A second name, and a word after the first in a name.
            "mont" to listOf("grumpy"),
            // An umlaut in the name, typed without and with it.
            "mude" to listOf("bear"),
            "MÜDER" to listOf("bear"),
            "bar" to listOf("bear"),
            // ß is ss, both ways.
            "strasse" to listOf("street"),
            "Straße" to listOf("street"),
            "grosse" to listOf("street"),
            // The automatic search words and the text on the sticker.
            "schlaf" to listOf("bear"),
            "nacht" to listOf("bear"),
            // Nothing.
            "fox" to emptyList(),
            "  " to emptyList(),
        )

        cases.forEach { (query, expected) -> assertEquals("query \"$query\"", expected, find(query)) }
    }

    @Test
    fun `every word of a query has to be found, in any order and in any of the fields`() {
        assertEquals(listOf("grumpy"), find("cat grumpy"))
        assertEquals(listOf("bear"), find("sleepy tired"))
        // One word in the sticker's name, the other in its pack's.
        assertEquals(listOf("street"), find("road gross"))
        assertTrue(find("grumpy fox").isEmpty())
    }

    @Test
    fun `own names come first, then the automatic group, then the pack's name, and emoji hits last`() {
        // "cat": grumpy's own name, the bear's search word, the pack Cats, and the emoji the word stands for.
        // The bear is found by its own word before its pack's name is looked at.
        assertEquals(listOf("grumpy", "bear", "tagged"), find("cat"))
        // "cats" is only the pack's name: its stickers, in the pack's order.
        assertEquals(listOf("bear", "grumpy"), find("cats"))
    }

    @Test
    fun `a pack's name finds its stickers, umlauts ignored`() {
        assertEquals(listOf("plain", "tagged"), find("buro"))
    }

    @Test
    fun `a sticker in two packs is found once, for the pack that gives it its best place`() {
        // By its name, the first pack that holds it.
        assertEquals(listOf(StickerMatch(grumpy, "Cats")), StickerSearch.find(library, "grumpy", emojisFor))
        // By the name of the second pack only.
        assertEquals(listOf(StickerMatch(street, "Roads"), StickerMatch(grumpy, "Roads")), StickerSearch.find(library, "roads", emojisFor))
    }

    @Test
    fun `an emoji as the query still finds its stickers`() {
        assertEquals(listOf("grumpy"), find("😾"))
    }

    // --- The manager's sections ---

    private val favourites = testStickerPack("f", StickerPackKind.FAVOURITES, tagged)
    private val shelves = listOf(favourites, pack("Animals", tagged, dog, grumpy), pack("Empty"), pack("Love", heart, untagged))
    private val shownName: (StickerPack) -> String = { if (it.kind == StickerPackKind.FAVOURITES) "Favourites" else it.name }

    private fun sections(query: String) = StickerSearch.sections(shelves, query, emojisFor, shownName).map { section ->
        section.pack.id to section.stickers.map { it.id }
    }

    @Test
    fun `a blank query shows every sticker under its pack and leaves out an empty pack`() {
        val all = listOf(
            "f" to listOf("tagged"),
            "Animals" to listOf("tagged", "dog", "grumpy"),
            "Love" to listOf("heart", "untagged"),
        )

        assertEquals(all, sections(""))
        assertEquals(all, sections("  "))
    }

    @Test
    fun `a sticker that two packs hold is in both sections, and a name comes before an emoji hit`() {
        assertEquals(listOf("f" to listOf("tagged"), "Animals" to listOf("grumpy", "tagged")), sections(" cat "))
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
        assertEquals(listOf("f" to listOf("tagged")), sections("favour"))
    }

    @Test
    fun `a query that names nothing shows nothing`() {
        assertTrue(sections("fox").isEmpty())
        // An empty pack has nothing to show, also when its name matches.
        assertTrue(sections("Empty").isEmpty())
    }

    // --- The manager's filters ---

    private val typed = testSticker("typed").copy(names = listOf("Grumpy cat"))
    private val found = testSticker("found").copy(autoName = StickerAutoName(nameEn = "Sleepy bear"))
    private val both = testSticker("both").copy(names = listOf("Mine"), autoName = StickerAutoName(nameDe = "Müder Bär"))
    private val nameless = testSticker("nameless", "😺")
    private val named = listOf(pack("Zoo", typed, found, both, nameless), pack("Typed only", typed))

    private fun filtered(filter: StickerNameFilter, query: String = "") =
        StickerSearch.sections(named, query, emojisFor, filter = filter).map { section ->
            section.pack.id to section.stickers.map { it.id }
        }

    @Test
    fun `the no-name filter shows the stickers nobody named, and an emoji is no name`() {
        assertEquals(listOf("Zoo" to listOf("nameless")), filtered(StickerNameFilter.NO_NAME))
    }

    @Test
    fun `the named-automatically filter shows what the service named, whatever a person named it since`() {
        assertEquals(listOf("Zoo" to listOf("found", "both")), filtered(StickerNameFilter.NAMED_AUTOMATICALLY))
    }

    @Test
    fun `a filter narrows a search, and a pack it leaves nothing of is left out`() {
        assertEquals(listOf("Zoo" to listOf("found")), filtered(StickerNameFilter.NAMED_AUTOMATICALLY, "bear"))
        assertTrue(filtered(StickerNameFilter.NO_NAME, "grumpy").isEmpty())
        // A pack's name still shows the pack, as far as the filter lets it.
        assertEquals(listOf("Zoo" to listOf("nameless")), filtered(StickerNameFilter.NO_NAME, "zoo"))
    }

    @Test
    fun `without a filter every sticker shows`() {
        assertEquals(
            listOf("Zoo" to listOf("typed", "found", "both", "nameless"), "Typed only" to listOf("typed")),
            filtered(StickerNameFilter.ALL),
        )
    }
}
