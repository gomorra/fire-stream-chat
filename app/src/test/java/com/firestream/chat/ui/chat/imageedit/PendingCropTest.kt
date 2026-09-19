package com.firestream.chat.ui.chat.imageedit

import com.firestream.chat.domain.util.RasterOp
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The crop a fullscreen photo would get from its zoom and the pill's preset.
 * Pure JVM: the frame is what gets written, so every rule here is a rule about
 * which pixels a photo keeps.
 */
class PendingCropTest {

    private val tolerance = 0.001f

    /** A 400 × 100 photo: a square is a quarter of its width and all of its height. */
    private val wide = PendingCrop(imageWidth = 400, imageHeight = 100)

    private fun assertRect(left: Float, top: Float, right: Float, bottom: Float, actual: CropRect) {
        assertEquals("left", left, actual.left, tolerance)
        assertEquals("top", top, actual.top, tolerance)
        assertEquals("right", right, actual.right, tolerance)
        assertEquals("bottom", bottom, actual.bottom, tolerance)
    }

    @Test
    fun `nothing pending crops nothing`() {
        assertFalse(PendingCrop.None.isZoomed)
        assertFalse(PendingCrop.None.hasCrop)
        assertNull(PendingCrop.None.toOp())
    }

    @Test
    fun `a free crop is the viewport itself`() {
        val zoomed = wide.copy(viewport = CropRect(0.25f, 0f, 0.75f, 1f))
        assertTrue(zoomed.isZoomed)
        assertEquals(zoomed.viewport, zoomed.frame)
        assertEquals(RasterOp.Crop(0.25f, 0f, 0.75f, 1f), zoomed.toOp())
    }

    @Test
    fun `a square of an unzoomed wide photo is its middle quarter, full height`() {
        val square = wide.copy(aspect = CropAspect.SQUARE)
        assertFalse(square.isZoomed)
        assertTrue(square.hasCrop)
        assertRect(0.375f, 0f, 0.625f, 1f, square.frame)
    }

    @Test
    fun `an aspect is cut from the zoomed viewport, not from the whole photo`() {
        // The middle half of the width is 200 × 100 photo px; a 16:9 frame of
        // it is limited by its height: 177.8 px wide, or 0.444 of the photo.
        val zoomed = wide.copy(viewport = CropRect(0.25f, 0f, 0.75f, 1f), aspect = CropAspect.WIDE)
        val expectedWidth = (100f * 16f / 9f) / 400f
        assertRect(0.5f - expectedWidth / 2f, 0f, 0.5f + expectedWidth / 2f, 1f, zoomed.frame)
    }

    @Test
    fun `original keeps the whole photo until it is zoomed`() {
        assertFalse(wide.copy(aspect = CropAspect.ORIGINAL).hasCrop)
        // Zoomed to a sliver, Original asks for the photo's own 4:1 inside it.
        val zoomed = wide.copy(viewport = CropRect(0.4f, 0f, 0.6f, 1f), aspect = CropAspect.ORIGINAL)
        // 0.2 of the width is 80 px; 4:1 of full height would be 400 px, so the
        // width limits: 80 × 20 px, or 0.2 wide and 0.2 tall, centred.
        assertRect(0.4f, 0.4f, 0.6f, 0.6f, zoomed.frame)
    }

    @Test
    fun `an aspect on a photo whose size is not known yet is not applied`() {
        val unsized = PendingCrop(aspect = CropAspect.SQUARE)
        assertFalse(unsized.hasCrop)
        assertEquals(CropRect.Full, unsized.frame)
    }

    @Test
    fun `the pill cycles through every preset and wraps`() {
        var crop = PendingCrop.None
        val seen = mutableListOf<CropAspect>()
        repeat(CropAspect.entries.size) {
            crop = crop.cycleAspect()
            seen += crop.aspect
        }
        assertEquals(CropAspect.entries.drop(1) + CropAspect.FREE, seen)
    }

    @Test
    fun `the saver round-trips every field`() {
        val crop = PendingCrop(CropRect(0.1f, 0.2f, 0.7f, 0.9f), CropAspect.PORTRAIT, 3000, 4000)
        assertEquals(crop, PendingCrop.restore(PendingCrop.save(crop)))
    }

    @Test
    fun `the saver rejects a run that is not a crop`() {
        assertNull(PendingCrop.restore(listOf("nope")))
        assertNull(PendingCrop.restore(listOf(0f, 0f, 1f, 1f, "NOT_AN_ASPECT", 1, 1)))
    }
}
