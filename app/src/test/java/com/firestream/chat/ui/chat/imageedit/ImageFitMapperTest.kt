package com.firestream.chat.ui.chat.imageedit

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The mapping every overlay tool asks "where on the bitmap did the finger land"
 * through (`.claude/plans/image-editor.md` §2.3). Pure JVM: no Robolectric, no
 * Compose — the whole point of the helper is that this arithmetic is checkable
 * without a device, because a few pixels of drift here is a blur that misses the
 * face it was meant to cover.
 *
 * Expected values are worked by hand from the fit rule rather than recomputed
 * the way the code computes them.
 */
class ImageFitMapperTest {

    private val tolerance = 0.001f

    /** 100×100 image in a 400×200 canvas: fits to 200×200 with 100 px bars either side. */
    private val pillarbox = ImageFitMapper(canvasWidth = 400f, canvasHeight = 200f, imageWidth = 100, imageHeight = 100)

    /** 200×100 image in a 200×400 canvas: fits to 200×100 with 150 px bars top and bottom. */
    private val letterbox = ImageFitMapper(canvasWidth = 200f, canvasHeight = 400f, imageWidth = 200, imageHeight = 100)

    private fun assertPoint(expectedX: Float, expectedY: Float, actual: FitPoint) {
        assertEquals("x", expectedX, actual.x, tolerance)
        assertEquals("y", expectedY, actual.y, tolerance)
    }

    @Test
    fun `a square image in a wide canvas is pillarboxed and centred`() {
        assertEquals(2f, pillarbox.scale, tolerance)
        assertEquals(200f, pillarbox.fittedWidth, tolerance)
        assertEquals(200f, pillarbox.fittedHeight, tolerance)
        assertEquals(100f, pillarbox.offsetX, tolerance)
        assertEquals(0f, pillarbox.offsetY, tolerance)
    }

    @Test
    fun `a wide image in a tall canvas is letterboxed and centred`() {
        assertEquals(1f, letterbox.scale, tolerance)
        assertEquals(200f, letterbox.fittedWidth, tolerance)
        assertEquals(100f, letterbox.fittedHeight, tolerance)
        assertEquals(0f, letterbox.offsetX, tolerance)
        assertEquals(150f, letterbox.offsetY, tolerance)
    }

    @Test
    fun `an origin moves the fit area without changing the fit`() {
        // The crop tool fits the photo inside a margin but keeps its gesture
        // layer on the whole canvas, so every mapping has to carry the origin —
        // a handle drawn at the shifted place and hit-tested at the unshifted one
        // would be a margin's width from the finger.
        val inset = ImageFitMapper(
            canvasWidth = 400f,
            canvasHeight = 200f,
            imageWidth = 100,
            imageHeight = 100,
            originX = 30f,
            originY = 10f,
        )

        assertEquals(2f, inset.scale, tolerance)
        assertEquals(130f, inset.offsetX, tolerance)
        assertEquals(10f, inset.offsetY, tolerance)
        assertPoint(0f, 0f, inset.screenToNormalized(FitPoint(130f, 10f)))
        assertPoint(330f, 210f, inset.normalizedToScreen(FitPoint(1f, 1f)))
        assertTrue(inset.containsScreen(FitPoint(130f, 10f)))
        assertFalse(inset.containsScreen(FitPoint(129f, 10f)))
    }

    @Test
    fun `a screen point maps onto the bitmap through the pillarbox`() {
        // 50 px into the 200 px-wide image, at 2x, is bitmap pixel 25.
        assertPoint(0.25f, 0.25f, pillarbox.screenToNormalized(FitPoint(150f, 50f)))
        assertPoint(25f, 25f, pillarbox.screenToBitmap(FitPoint(150f, 50f)))
    }

    @Test
    fun `a screen point maps onto the bitmap through the letterbox`() {
        assertPoint(0.25f, 0.5f, letterbox.screenToNormalized(FitPoint(50f, 200f)))
        assertPoint(50f, 50f, letterbox.screenToBitmap(FitPoint(50f, 200f)))
    }

    @Test
    fun `a bitmap point maps back out to where it is drawn`() {
        assertPoint(0.75f, 0.5f, pillarbox.bitmapToNormalized(FitPoint(75f, 50f)))
        assertPoint(250f, 100f, pillarbox.bitmapToScreen(FitPoint(75f, 50f)))
    }

    @Test
    fun `screen and bitmap round-trip in both directions`() {
        for (mapper in listOf(pillarbox, letterbox)) {
            val screen = FitPoint(37f, 213f)
            assertPoint(screen.x, screen.y, mapper.bitmapToScreen(mapper.screenToBitmap(screen)))

            val bitmap = FitPoint(11f, 62f)
            assertPoint(bitmap.x, bitmap.y, mapper.screenToBitmap(mapper.bitmapToScreen(bitmap)))

            val normalized = FitPoint(0.3f, 0.8f)
            assertPoint(normalized.x, normalized.y, mapper.screenToNormalized(mapper.normalizedToScreen(normalized)))
            assertPoint(normalized.x, normalized.y, mapper.bitmapToNormalized(mapper.normalizedToBitmap(normalized)))
        }
    }

    @Test
    fun `a point on a bar is off the image but still maps`() {
        // The pillarbox's left bar: normalized x goes negative rather than clamping,
        // so a stroke that leaves the photo is the caller's decision, not a silent
        // snap to the edge.
        assertFalse(pillarbox.containsScreen(FitPoint(99f, 100f)))
        assertTrue(pillarbox.screenToNormalized(FitPoint(99f, 100f)).x < 0f)

        assertTrue(pillarbox.containsScreen(FitPoint(100f, 100f)))
        assertTrue(pillarbox.containsScreen(FitPoint(300f, 200f)))
        assertFalse(pillarbox.containsScreen(FitPoint(301f, 100f)))
    }

    @Test
    fun `an unmeasured canvas maps to the origin instead of infinity`() {
        // First composition, before layout: dividing by a zero canvas would put
        // NaN into every stroke the user then draws.
        val unmeasured = ImageFitMapper(canvasWidth = 0f, canvasHeight = 0f, imageWidth = 100, imageHeight = 100)

        assertEquals(0f, unmeasured.scale, tolerance)
        assertPoint(0f, 0f, unmeasured.screenToNormalized(FitPoint(50f, 50f)))
        assertPoint(0f, 0f, unmeasured.bitmapToScreen(FitPoint(50f, 50f)))
        assertFalse(unmeasured.containsScreen(FitPoint(0f, 0f)))
    }

    @Test
    fun `an undecoded image maps to the origin instead of infinity`() {
        val noImage = ImageFitMapper(canvasWidth = 400f, canvasHeight = 200f, imageWidth = 0, imageHeight = 0)

        assertEquals(0f, noImage.scale, tolerance)
        assertPoint(0f, 0f, noImage.screenToNormalized(FitPoint(50f, 50f)))
        assertPoint(0f, 0f, noImage.bitmapToNormalized(FitPoint(50f, 50f)))
    }
}
