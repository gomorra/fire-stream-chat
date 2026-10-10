package com.firestream.chat.ui.chat.picker

import com.firestream.chat.domain.model.Sticker
import com.firestream.chat.domain.model.StickerPackKind
import com.firestream.chat.test.fakes.testSticker
import com.firestream.chat.test.fakes.testStickerPack
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** The pack row of the composer's sticker tab, as [stickerShelves] builds it. */
class StickerShelvesTest {

    private fun stickers(vararg ids: String) = ids.map { testSticker(it) }.toTypedArray()

    /** A pack in the row, as every pack is that no WhatsApp import made. */
    private fun shown(id: String, kind: StickerPackKind, vararg stickers: Sticker) = testStickerPack(id, kind, *stickers)

    /** A pack behind the *WhatsApp* thumbnail. */
    private fun grouped(id: String, vararg stickers: Sticker) =
        testStickerPack(id, StickerPackKind.USER, *stickers).copy(shownInRow = false)

    @Test
    fun `the row is Recents, Favourites, WhatsApp, then the packs with a thumbnail in their order`() {
        val shelves = stickerShelves(
            packs = listOf(
                shown("made", StickerPackKind.USER, *stickers("m1")),
                grouped("wa1", *stickers("w1")),
                shown("saved", StickerPackKind.SAVED, *stickers("s1")),
                shown("favs", StickerPackKind.FAVOURITES, *stickers("f1")),
                grouped("wa2", *stickers("w2")),
                shown("friend", StickerPackKind.INSTALLED, *stickers("i1")),
            ),
            recents = stickers("r1").toList(),
        )

        assertEquals(listOf(RECENTS_SHELF_KEY, "favs", WHATSAPP_SHELF_KEY, "made", "saved", "friend"), shelves.map { it.key })
        assertEquals(listOf("Recents", "Favourites", "WhatsApp", "made", "Saved stickers", "friend"), shelves.map { it.label })
    }

    @Test
    fun `a shelf that is not the WhatsApp shelf is one section, sent with its pack`() {
        val shelves = stickerShelves(
            packs = listOf(shown("made", StickerPackKind.USER, *stickers("m1", "m2"))),
            recents = stickers("m2").toList(),
        )

        val (recents, made) = shelves
        assertEquals(listOf("Recents · 1"), recents.sections.map { it.title })
        assertNull(recents.packIdOf("m2"))
        assertEquals(listOf("made · 2"), made.sections.map { it.title })
        assertEquals("made", made.packIdOf("m2"))
    }

    @Test
    fun `a grouped pack of four stickers has a title, and a pack of three goes to More`() {
        val shelf = stickerShelves(
            packs = listOf(
                grouped("three", *stickers("t1", "t2", "t3")),
                grouped("four", *stickers("a1", "a2", "a3", "a4")),
                grouped("one", *stickers("o1")),
                grouped("five", *stickers("b1", "b2", "b3", "b4", "b5")),
            ),
            recents = emptyList(),
        ).single()

        // The titled packs keep their order, and More is last.
        assertEquals(listOf("four", "five", MORE_SECTION_KEY), shelf.sections.map { it.key })
        assertEquals(listOf("four · 4", "five · 5", "More · 4"), shelf.sections.map { it.title })
        assertEquals(listOf("t1", "t2", "t3", "o1"), shelf.sections.last().stickers.map { it.id })
    }

    @Test
    fun `a pick is sent with the pack the sticker sits in, in a titled section and in More`() {
        val shelf = stickerShelves(
            packs = listOf(
                grouped("small", *stickers("s1")),
                grouped("big", *stickers("a1", "a2", "a3", "a4")),
                grouped("tiny", *stickers("y1")),
            ),
            recents = emptyList(),
        ).single()

        assertEquals("big", shelf.packIdOf("a3"))
        assertEquals("small", shelf.packIdOf("s1"))
        assertEquals("tiny", shelf.packIdOf("y1"))
    }

    @Test
    fun `the WhatsApp shelf is left out when no grouped pack holds a sticker`() {
        val shelves = stickerShelves(
            packs = listOf(grouped("empty"), shown("made", StickerPackKind.USER, *stickers("m1"))),
            recents = emptyList(),
        )

        assertEquals(listOf("made"), shelves.map { it.key })
    }

    @Test
    fun `small packs alone make a WhatsApp shelf of one More section`() {
        val shelf = stickerShelves(packs = listOf(grouped("a", *stickers("a1")), grouped("b", *stickers("b1"))), recents = emptyList())
            .single()

        assertEquals(listOf("More · 2"), shelf.sections.map { it.title })
    }

    @Test
    fun `a sticker in two grouped packs is shown once, by the first pack that holds it`() {
        val shelf = stickerShelves(
            packs = listOf(
                grouped("first", *stickers("a1", "a2", "a3", "shared")),
                grouped("second", *stickers("shared", "b1", "b2", "b3")),
            ),
            recents = emptyList(),
        ).single()

        val ids = shelf.stickers.map { it.id }
        assertEquals(ids.distinct(), ids)
        assertEquals("first", shelf.packIdOf("shared"))
        // The second pack shows three stickers, so it has no title of its own.
        assertEquals(listOf("first · 4", "More · 3"), shelf.sections.map { it.title })
    }

    @Test
    fun `Favourites and Saved stickers stay in the row whatever their flag says`() {
        val shelves = stickerShelves(
            packs = listOf(
                shown("favs", StickerPackKind.FAVOURITES, *stickers("f1")).copy(shownInRow = false),
                shown("saved", StickerPackKind.SAVED, *stickers("s1")).copy(shownInRow = false),
            ),
            recents = emptyList(),
        )

        assertEquals(listOf("favs", "saved"), shelves.map { it.key })
    }
}
