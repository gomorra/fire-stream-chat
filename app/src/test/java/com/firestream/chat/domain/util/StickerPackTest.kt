package com.firestream.chat.domain.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The bundled pack is hand-written coordinates, which is exactly the kind of
 * data that is wrong in a way nothing notices.
 *
 * A sticker with an odd number of coordinates loses its last point silently; one
 * with a coordinate outside `0..1` is drawn partly outside the box the editor
 * put its selection frame around, so the handles no longer sit on it; a
 * duplicated id makes one sticker unreachable and makes a saved placement
 * restore as the wrong picture. None of those throw, and none of them are
 * visible in a thumbnail grid. So they are asserted rather than eyeballed.
 *
 * What this cannot check is whether a heart looks like a heart. That is on the
 * device pass.
 */
class StickerPackTest {

    @Test
    fun `the pack is not empty and every id is unique`() {
        val ids = StickerPack.stickers.map { it.id }

        assertTrue("the sticker tab needs something to show", ids.isNotEmpty())
        assertEquals("duplicate ids make one sticker unreachable", ids.size, ids.toSet().size)
    }

    @Test
    fun `every sticker has a name and at least one part`() {
        StickerPack.stickers.forEach { design ->
            assertTrue("${design.id} has no name", design.name.isNotBlank())
            assertTrue("${design.id} draws nothing", design.parts.isNotEmpty())
        }
    }

    @Test
    fun `every coordinate stays inside the sticker's own box`() {
        StickerPack.stickers.forEach { design ->
            design.parts.forEach { part ->
                when (part) {
                    is StickerPart.Circle -> {
                        assertInBox(design.id, part.centerX)
                        assertInBox(design.id, part.centerY)
                        assertTrue("${design.id} has a circle with no radius", part.radius > 0f)
                    }

                    is StickerPart.Polygon -> part.points.forEach { assertInBox(design.id, it) }
                    is StickerPart.Line -> {
                        part.points.forEach { assertInBox(design.id, it) }
                        assertTrue("${design.id} has a hairline", part.width > 0f)
                    }
                }
            }
        }
    }

    @Test
    fun `every polygon and line has whole points and enough of them`() {
        StickerPack.stickers.forEach { design ->
            design.parts.forEach { part ->
                val points = when (part) {
                    is StickerPart.Polygon -> part.points
                    is StickerPart.Line -> part.points
                    is StickerPart.Circle -> return@forEach
                }
                assertEquals("${design.id} has a dangling coordinate", 0, points.size % 2)
                val minimum = if (part is StickerPart.Polygon) 6 else 4
                assertTrue("${design.id} has a degenerate part", points.size >= minimum)
            }
        }
    }

    @Test
    fun `a placement finds its design by id, and an unknown id finds nothing`() {
        val first = StickerPack.stickers.first()

        assertNotNull(StickerPack.byId(first.id))
        assertEquals(first, StickerPack.byId(first.id))
        // A sticker dropped from a future pack must resolve to nothing rather
        // than to whatever happens to be at that index — the renderers skip a
        // null, which loses one sticker instead of drawing the wrong one.
        assertNull(StickerPack.byId("no-such-sticker"))
    }

    private fun assertInBox(id: String, value: Float) {
        assertTrue("$id has a coordinate outside 0..1: $value", value in 0f..1f)
    }
}
