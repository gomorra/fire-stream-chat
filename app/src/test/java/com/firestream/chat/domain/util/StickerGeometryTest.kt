package com.firestream.chat.domain.util

import com.firestream.chat.domain.model.StickerCrop
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.hypot

class StickerGeometryTest {

    private val tolerance = 0.0001f

    @Test
    fun `an untouched picture is fitted and centred in the square`() {
        val wide = StickerGeometry.placement(800, 400, StickerCrop())
        assertEquals(0f, wide.left, tolerance)
        assertEquals(1f, wide.right, tolerance)
        assertEquals(0.25f, wide.top, tolerance)
        assertEquals(0.75f, wide.bottom, tolerance)

        val tall = StickerGeometry.placement(300, 600, StickerCrop())
        assertEquals(0.25f, tall.left, tolerance)
        assertEquals(0.75f, tall.right, tolerance)
        assertEquals(0f, tall.top, tolerance)
        assertEquals(1f, tall.bottom, tolerance)
    }

    @Test
    fun `a zoom grows the picture about the centre and an offset moves it by a share of the square`() {
        val placement = StickerGeometry.placement(400, 400, StickerCrop(scale = 2f, offsetX = 0.25f, offsetY = -0.5f))

        assertEquals(2f, placement.width, tolerance)
        assertEquals(2f, placement.height, tolerance)
        assertEquals(-0.25f, placement.left, tolerance)
        assertEquals(-1f, placement.top, tolerance)
    }

    @Test
    fun `the scale stays between the whole picture and the limit`() {
        assertEquals(1f, StickerGeometry.clamp(StickerCrop(scale = 0.2f), 100, 100).scale, tolerance)
        assertEquals(StickerGeometry.MAX_SCALE, StickerGeometry.clamp(StickerCrop(scale = 40f), 100, 100).scale, tolerance)
        assertEquals(StickerCrop(), StickerGeometry.clamp(StickerCrop(Float.NaN, Float.NaN, Float.NaN), 100, 100))
    }

    @Test
    fun `a picture that does not fill an axis stays centred on it`() {
        // 800 x 400 at scale 1.5 is 1.5 wide and 0.75 high: it can move sideways only.
        val crop = StickerGeometry.clamp(StickerCrop(scale = 1.5f, offsetX = 0.1f, offsetY = 0.3f), 800, 400)

        assertEquals(0.1f, crop.offsetX, tolerance)
        assertEquals(0f, crop.offsetY, tolerance)
    }

    @Test
    fun `a drag never opens a gap on an axis the picture fills`() {
        val crop = StickerGeometry.transformed(StickerCrop(scale = 2f), zoom = 1f, panX = 3f, panY = -3f, width = 500, height = 500)
        val placement = StickerGeometry.placement(500, 500, crop)

        assertEquals("the left edge stops at the square's", 0f, placement.left, tolerance)
        assertEquals("the bottom edge stops at the square's", 1f, placement.bottom, tolerance)
    }

    @Test
    fun `a pinch multiplies the scale and a drag adds to the offset`() {
        val crop = StickerGeometry.transformed(StickerCrop(scale = 2f, offsetX = 0.1f), zoom = 1.5f, panX = 0.2f, panY = 0f, width = 100, height = 100)

        assertEquals(3f, crop.scale, tolerance)
        assertEquals(0.3f, crop.offsetX, tolerance)
    }

    @Test
    fun `zooming out pulls a moved picture back inside`() {
        val moved = StickerCrop(scale = 3f, offsetX = 1f)
        val crop = StickerGeometry.transformed(moved, zoom = 1f / 3f, panX = 0f, panY = 0f, width = 100, height = 100)

        assertEquals(StickerCrop(), crop)
    }

    @Test
    fun `the outline grows with the subject and never vanishes`() {
        assertEquals(13, StickerGeometry.outlineWidth(512))
        assertEquals(26, StickerGeometry.outlineWidth(1024))
        assertEquals(2, StickerGeometry.outlineWidth(10))
    }

    @Test
    fun `no outline stamp lies farther out than the outline's width`() {
        val shifts = StickerGeometry.outlineShifts(13)

        val farthest = shifts.maxOf { hypot(it.dx, it.dy) }
        assertEquals(13f, farthest, 0.001f)
    }

    @Test
    fun `neighbouring stamps of the outer ring are at most two pixels apart`() {
        val width = 26
        val outer = StickerGeometry.outlineShifts(width).filter { hypot(it.dx, it.dy) > width - 0.5f }

        assertTrue(outer.size >= 8)
        (outer + outer.first()).zipWithNext { a, b ->
            assertTrue("gap ${hypot(a.dx - b.dx, a.dy - b.dy)}", hypot(a.dx - b.dx, a.dy - b.dy) <= 2.01f)
        }
    }

    @Test
    fun `an inner ring fills what the outer one leaves open`() {
        val shifts = StickerGeometry.outlineShifts(20)

        assertTrue(shifts.any { hypot(it.dx, it.dy) in 9.5f..10.5f })
    }
}
