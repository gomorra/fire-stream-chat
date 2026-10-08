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
    fun `a frame lands on screen where the zoomed photo draws it`() {
        // At 3x the photo is drawn 600 × 150 with its left edge 200 px past the
        // box's: the middle third of the photo is the box's full width, and the
        // photo's 150 px sit 125 px down.
        val frame = ViewportGeometry.toScreen(
            CropRect(1f / 3f, 0f, 2f / 3f, 1f), ZoomTransform(3f, 0f, 0f), boxWidth, boxHeight, imageWidth, imageHeight,
        )!!
        assertEquals(0f, frame.left, tolerance)
        assertEquals(125f, frame.top, tolerance)
        assertEquals(200f, frame.right, tolerance)
        assertEquals(275f, frame.bottom, tolerance)
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

    /**
     * The keyboard case, in a phone's numbers: a 3000 × 4000 photo in a
     * 1080 × 2200 box fits to 1080 × 1440 (0.36 screen px per photo px). When
     * the keyboard slides over the caption field the box drops to 1080 × 1300,
     * where the photo fits by its height instead: 975 × 1300 (0.325).
     */
    private val phoneWidth = 1080f
    private val phoneHeight = 2200f
    private val keyboardHeight = 1300f
    private val photoWidth = 3000
    private val photoHeight = 4000

    private fun phoneVisible(transform: ZoomTransform) =
        ViewportGeometry.visible(transform, phoneWidth, phoneHeight, photoWidth, photoHeight)

    private fun resized(anchor: ZoomTransform, frame: CropRect, toHeight: Float, fromHeight: Float = phoneHeight) =
        ViewportGeometry.transformAfterResize(
            anchor = anchor,
            anchorBoxWidth = phoneWidth,
            anchorBoxHeight = fromHeight,
            frame = frame,
            boxWidth = phoneWidth,
            boxHeight = toHeight,
            imageWidth = photoWidth,
            imageHeight = photoHeight,
            maxScale = 10f,
        )

    private fun onScreen(frame: CropRect, transform: ZoomTransform, boxHeight: Float) =
        ViewportGeometry.toScreen(frame, transform, phoneWidth, boxHeight, photoWidth, photoHeight)!!

    @Test
    fun `a square frame keeps its size and stays centred when the keyboard shortens the box`() {
        // 2x: the box shows the middle half of the photo across and 0.118–0.882
        // down, 1500 × 3057 photo px. The largest square in that is 1500 px, a
        // 0.5 × 0.375 frame drawn 1080 × 1080 — the whole box width.
        //
        // Keeping the 2x would draw it at 975 × 975, because the fit shrank;
        // keeping the old offset would also slide it sideways. Either way the
        // frame the user lined up no longer looks like the one they lined up.
        // Holding the photo's on-screen size instead needs 2 × 0.36 / 0.325 =
        // 2.215x, at which the square is 1080 × 1080 again and centred in the
        // shorter box: 110 px of photo above and below it.
        val anchor = ZoomTransform(2f, 0f, 0f)
        val frame = CropGeometry.fitInside(CropAspect.SQUARE, phoneVisible(anchor), photoWidth, photoHeight)
        assertRect(0.25f, 0.3125f, 0.75f, 0.6875f, frame)

        val rect = onScreen(frame, resized(anchor, frame, keyboardHeight), keyboardHeight)

        assertEquals("left", 0f, rect.left, 0.5f)
        assertEquals("right", phoneWidth, rect.right, 0.5f)
        assertEquals("top", 110f, rect.top, 0.5f)
        assertEquals("bottom", 1190f, rect.bottom, 0.5f)
    }

    @Test
    fun `a frame panned off centre comes back to the middle of the shorter box, whole`() {
        // Panned 300 px right at 2x, the photo's 0.11–0.61 strip is on screen.
        // The square is still that strip's full width, and in the shorter box
        // it has to be all there: centred, 1080 wide, not shifted against an edge.
        val anchor = ZoomTransform(2f, 300f, 0f)
        val frame = CropGeometry.fitInside(CropAspect.SQUARE, phoneVisible(anchor), photoWidth, photoHeight)

        val rect = onScreen(frame, resized(anchor, frame, keyboardHeight), keyboardHeight)

        assertEquals("left", 0f, rect.left, 0.5f)
        assertEquals("right", phoneWidth, rect.right, 0.5f)
        assertEquals("width = height", rect.width, rect.height, 0.5f)
    }

    @Test
    fun `a frame too tall for the shorter box is zoomed out to fit, never cut`() {
        // A free crop is the whole viewport, 1080 × 2200 on screen: at the
        // photo's old on-screen size it would not fit 1300 px. It is contained
        // instead, so everything that will be sent stays in view.
        val anchor = ZoomTransform(2f, 0f, 0f)
        val frame = phoneVisible(anchor)

        val transform = resized(anchor, frame, keyboardHeight)
        val rect = onScreen(frame, transform, keyboardHeight)

        assertTrue("top ${rect.top}", rect.top >= -0.5f)
        assertTrue("bottom ${rect.bottom}", rect.bottom <= keyboardHeight + 0.5f)
        assertEquals("height", keyboardHeight, rect.height, 0.5f)
        assertEquals("centred", phoneWidth / 2f, (rect.left + rect.right) / 2f, 0.5f)
    }

    @Test
    fun `the keyboard going away puts back exactly the zoom the user made`() {
        // Every frame of the keyboard's slide is worked out from the zoom the
        // user made, not from the frame before, so the trip down and back up
        // cannot drift.
        val anchor = ZoomTransform(2f, 300f, -100f)
        val frame = CropGeometry.fitInside(CropAspect.SQUARE, phoneVisible(anchor), photoWidth, photoHeight)

        val back = resized(anchor, frame, toHeight = phoneHeight)

        assertEquals(anchor.scale, back.scale, tolerance)
        assertEquals(anchor.offsetX, back.offsetX, 0.5f)
        assertEquals(anchor.offsetY, back.offsetY, 0.5f)
    }

    @Test
    fun `an unzoomed photo stays unzoomed when the box changes`() {
        // At 1x the photo simply fits the new box; zooming in to keep it the
        // same size would make a single-finger swipe pan instead of page.
        val frame = CropGeometry.fitInside(CropAspect.SQUARE, CropRect.Full, photoWidth, photoHeight)
        assertEquals(ZoomTransform.Identity, resized(ZoomTransform.Identity, frame, keyboardHeight))
    }

    @Test
    fun `a frame off the view's centre is still pulled wholly into the shorter box`() {
        // A restore against the photo's top edge leaves the frame below the
        // box centre: here a square on the photo's top strip, while the view
        // is centred lower down. Centring the view alone would cut the frame's
        // top off in the 1300 px box.
        val anchor = ZoomTransform(2f, 0f, 340f)
        val frame = CropRect(0.25f, 0f, 0.75f, 0.375f)

        val rect = onScreen(frame, resized(anchor, frame, keyboardHeight), keyboardHeight)

        assertTrue("top ${rect.top}", rect.top >= -0.5f)
        assertTrue("bottom ${rect.bottom}", rect.bottom <= keyboardHeight + 0.5f)
        assertEquals("width", phoneWidth, rect.width, 0.5f)
    }
}
