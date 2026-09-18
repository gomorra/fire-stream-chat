package com.firestream.chat.ui.chat.imageedit

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The arithmetic that turns a pinch-zoom on the send preview into the crop it
 * sends. Pure JVM, like `CropGeometryTest`: a frame a few percent off here is
 * a face half out of the picture in the JPEG, and every case is a finger
 * position nobody would reach twice by hand.
 *
 * Expected values are worked by hand from the fit rule and the pivot-at-centre
 * transform, not recomputed the way the code computes them.
 */
class ViewportGeometryTest {

    private val tolerance = 0.001f

    /** A 400 × 100 photo in a 200 × 400 box fits to 200 × 50, letterboxed 175 px top and bottom. */
    private val boxWidth = 200f
    private val boxHeight = 400f
    private val imageWidth = 400
    private val imageHeight = 100

    private fun visible(transform: ZoomTransform) =
        ViewportGeometry.visible(transform, boxWidth, boxHeight, imageWidth, imageHeight)

    private fun clamp(transform: ZoomTransform) =
        ViewportGeometry.clamp(transform, boxWidth, boxHeight, imageWidth, imageHeight)

    private fun transformFor(viewport: CropRect) =
        ViewportGeometry.transformFor(viewport, boxWidth, boxHeight, imageWidth, imageHeight, maxScale = 10f)

    private fun assertRect(left: Float, top: Float, right: Float, bottom: Float, actual: CropRect) {
        assertEquals("left", left, actual.left, tolerance)
        assertEquals("top", top, actual.top, tolerance)
        assertEquals("right", right, actual.right, tolerance)
        assertEquals("bottom", bottom, actual.bottom, tolerance)
    }

    @Test
    fun `at 1x the whole image is visible`() {
        assertTrue(visible(ZoomTransform.Identity).isFull)
    }

    @Test
    fun `a centred zoom shows the middle of the axis the image fills and all of the other`() {
        // 3x: the photo is drawn 600 × 150 about the box centre. Horizontally the
        // box shows 200 of 600 px, the middle third; vertically 150 px of photo
        // sit inside 400 px of box, so all of it is on screen.
        assertRect(1f / 3f, 0f, 2f / 3f, 1f, visible(ZoomTransform(3f, 0f, 0f)))
    }

    @Test
    fun `a pan slides the frame the other way`() {
        // Moving the photo 100 px to the right brings its left part into view:
        // the box's left edge is now 100 px into a 600 px drawing, not 200.
        assertRect(100f / 600f, 0f, 300f / 600f, 1f, visible(ZoomTransform(3f, 100f, 0f)))
    }

    @Test
    fun `a zoom past the box height cuts both axes`() {
        // 10x: 2000 × 500 drawn. Horizontally the middle 200 of 2000 px;
        // vertically the middle 400 of 500 px.
        assertRect(0.45f, 0.1f, 0.55f, 0.9f, visible(ZoomTransform(10f, 0f, 0f)))
    }

    @Test
    fun `a view that shows none of the image keeps the whole image`() {
        // Pushed 1000 px right at 3x, the 600 px drawing is entirely off the
        // box. A crop of nothing is a one-pixel JPEG; the only sane reading of
        // it is "no crop".
        assertTrue(visible(ZoomTransform(3f, 1000f, 0f)).isFull)
    }

    @Test
    fun `a degenerate box or image is the whole image`() {
        assertTrue(ViewportGeometry.visible(ZoomTransform(3f, 0f, 0f), 0f, 400f, imageWidth, imageHeight).isFull)
        assertTrue(ViewportGeometry.visible(ZoomTransform(3f, 0f, 0f), boxWidth, boxHeight, 0, imageHeight).isFull)
    }

    @Test
    fun `clamping stops the image short of leaving the box on the axis it fills`() {
        // At 3x the drawing is 600 px wide in a 200 px box: 200 px of slack
        // either side, and no more.
        val clamped = clamp(ZoomTransform(3f, 1000f, 0f))
        assertEquals(200f, clamped.offsetX, tolerance)
        assertEquals(-200f, clamp(ZoomTransform(3f, -1000f, 0f)).offsetX, tolerance)
    }

    @Test
    fun `clamping centres the axis the image does not fill`() {
        // 150 px of photo in a 400 px box has nothing to pan: any vertical
        // offset would only show more letterbox.
        assertEquals(0f, clamp(ZoomTransform(3f, 0f, 90f)).offsetY, tolerance)
        assertEquals(0f, clamp(ZoomTransform(3f, 0f, -90f)).offsetY, tolerance)
    }

    @Test
    fun `clamping leaves an offset inside the slack alone`() {
        val inside = ZoomTransform(3f, 150f, 0f)
        assertEquals(inside, clamp(inside))
    }

    @Test
    fun `clamping at 1x pins the image to the centre`() {
        val clamped = clamp(ZoomTransform(1f, 40f, 40f))
        assertEquals(0f, clamped.offsetX, tolerance)
        assertEquals(0f, clamped.offsetY, tolerance)
    }

    @Test
    fun `a full frame restores to identity`() {
        assertEquals(ZoomTransform.Identity, transformFor(CropRect.Full))
    }

    @Test
    fun `restoring a frame reproduces the transform that made it`() {
        // The round trip is what a rotation relies on: the frame is saved from
        // one box and put back into another, and the second box has to show the
        // same part of the photo.
        val original = ZoomTransform(3f, 100f, 0f)
        val restored = transformFor(visible(original))
        assertEquals(original.scale, restored.scale, tolerance)
        assertEquals(original.offsetX, restored.offsetX, tolerance)
        assertEquals(original.offsetY, restored.offsetY, tolerance)
    }

    @Test
    fun `a frame cut on both axes restores on both`() {
        val original = ZoomTransform(10f, -300f, 40f)
        val restored = transformFor(visible(original))
        assertEquals(original.scale, restored.scale, tolerance)
        assertEquals(original.offsetX, restored.offsetX, tolerance)
        assertEquals(original.offsetY, restored.offsetY, tolerance)
    }

    @Test
    fun `a frame restored into a box of another shape is contained, never cut`() {
        // The middle third the portrait box made is 133 × 100 photo px, taller
        // than a 400 × 200 landscape box is. Filling the box's width would cut
        // the frame's top and bottom off — and send less than the user framed —
        // so the frame is contained instead: 2x, showing the middle half, with
        // everything the user framed still inside it.
        val frame = visible(ZoomTransform(3f, 0f, 0f))
        val restored = ViewportGeometry.transformFor(frame, 400f, 200f, imageWidth, imageHeight, maxScale = 10f)
        assertEquals(2f, restored.scale, tolerance)
        assertRect(0.25f, 0f, 0.75f, 1f, ViewportGeometry.visible(restored, 400f, 200f, imageWidth, imageHeight))
    }

    @Test
    fun `a restore never exceeds the surface's own ceiling`() {
        // A sliver of a frame would need 50x to fill the box; the surface stops
        // at 10x, and the restore must land where a pinch could.
        val restored = transformFor(CropRect(0.49f, 0.49f, 0.51f, 0.51f))
        assertEquals(10f, restored.scale, tolerance)
    }

    @Test
    fun `a restore is clamped so it cannot show past the photo`() {
        // A frame hugging the right edge, restored: the offset that would
        // centre it is pulled back to the slack, and the view still ends on the
        // photo's edge rather than past it.
        val restored = transformFor(CropRect(2f / 3f, 0f, 1f, 1f))
        assertEquals(-200f, restored.offsetX, tolerance)
        assertRect(2f / 3f, 0f, 1f, 1f, visible(restored))
    }
}
