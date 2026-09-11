package com.firestream.chat.ui.chat.imageedit

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The crop frame's arithmetic on the JVM, where the cases a finger on glass
 * would take an afternoon to reach are one line each: dragging a corner past its
 * opposite, past the edge of the photo, and into an aspect ratio that no longer
 * fits where the drag is pulling.
 *
 * This is the half of the crop tool a Robolectric test genuinely cannot check —
 * it renders a composable without ever moving a pointer across it — which is
 * exactly why the geometry is a pure object and not a lambda inside the screen.
 */
class CropGeometryTest {

    private val landscape = 4000 to 3000
    private val portrait = 3000 to 4000

    private fun assertRect(
        expected: CropRect,
        actual: CropRect,
        tolerance: Float = 0.001f,
    ) {
        assertEquals("left", expected.left, actual.left, tolerance)
        assertEquals("top", expected.top, actual.top, tolerance)
        assertEquals("right", expected.right, actual.right, tolerance)
        assertEquals("bottom", expected.bottom, actual.bottom, tolerance)
    }

    // ── Keeping the handles reachable ─────────────────────────────────────────

    private val grab = 24f

    /** A phone in portrait: canvas full-width, under a top bar and above a bottom panel. */
    private val portraitCanvas = EdgeInsetsPx(left = 0f, top = 80f, right = 0f, bottom = 180f)
    private val gestureStrips = EdgeInsetsPx(left = 32f, top = 24f, right = 32f, bottom = 48f)

    @Test
    fun `a canvas that runs to the screen edge keeps a third of full clearance from the back strip`() {
        // Regression: the photo was fitted edge to edge, so a corner sat on the
        // edge of the glass — half its target off the screen and the rest inside
        // the back-gesture strip, which takes the touch before the app sees it.
        // Full clearance (strip + grab radius, 56) cost too much photo, so the
        // margin keeps a third of it.
        val margin = CropGeometry.reachableInsets(portraitCanvas, gestureStrips, grab)

        assertEquals(56f / 3f, margin.left, 0.001f)
        assertEquals(56f / 3f, margin.right, 0.001f)
    }

    @Test
    fun `a third of the clearance still leaves the inner side of a corner's target past the strip`() {
        // What the reduced margin is allowed to rely on: a finger aimed at or just
        // inside the bracket must land beyond the gesture strip. If the fraction
        // is ever lowered past this, the corner is unreachable again.
        val margin = CropGeometry.reachableInsets(portraitCanvas, gestureStrips, grab)

        assertTrue(margin.left + grab > gestureStrips.left)
        assertTrue(margin.right + grab > gestureStrips.right)
    }

    @Test
    fun `an edge the editor's own bars already hold clear of its strip keeps only the grab-radius part`() {
        // The top bar and the bottom panel stand between the canvas and the
        // status and home strips; counting those strips again would shrink the
        // photo for nothing.
        val margin = CropGeometry.reachableInsets(portraitCanvas, gestureStrips, grab)

        assertEquals(8f, margin.top, 0.001f)
        assertEquals(8f, margin.bottom, 0.001f)
    }

    @Test
    fun `a canvas partway into a gesture strip clears only the rest of it`() {
        val margin = CropGeometry.reachableInsets(
            canvas = EdgeInsetsPx(left = 10f, top = 0f, right = 40f, bottom = 0f),
            gestures = EdgeInsetsPx(left = 32f, top = 0f, right = 32f, bottom = 0f),
            grabRadius = grab,
        )

        assertEquals(46f / 3f, margin.left, 0.001f)
        assertEquals("already clear of the strip", 8f, margin.right, 0.001f)
    }

    // ── Aspect ratios cross two spaces ────────────────────────────────────────

    @Test
    fun `a square crop of a landscape photo is not a square frame`() {
        // The frame is normalized to the image, so a 1:1 *output* on a 4:3 photo
        // is a frame three-quarters as wide as it is tall. Treating the two
        // spaces as one is the bug this conversion exists to prevent.
        val ratio = CropGeometry.normalizedRatio(CropAspect.SQUARE, landscape.first, landscape.second)

        assertEquals(0.75f, requireNotNull(ratio), 0.001f)
    }

    @Test
    fun `the original preset resolves against the image, not against a constant`() {
        assertEquals(
            4000f / 3000f,
            requireNotNull(CropGeometry.pixelAspect(CropAspect.ORIGINAL, landscape.first, landscape.second)),
            0.001f,
        )
        assertNull(CropGeometry.pixelAspect(CropAspect.FREE, landscape.first, landscape.second))
    }

    @Test
    fun `a centred square on a landscape photo touches top and bottom`() {
        val rect = CropGeometry.centered(CropAspect.SQUARE, landscape.first, landscape.second)

        assertRect(CropRect(0.125f, 0f, 0.875f, 1f), rect)
    }

    @Test
    fun `a centred square on a portrait photo touches left and right`() {
        val rect = CropGeometry.centered(CropAspect.SQUARE, portrait.first, portrait.second)

        assertRect(CropRect(0f, 0.125f, 1f, 0.875f), rect)
    }

    @Test
    fun `the original preset keeps the whole photo`() {
        val rect = CropGeometry.centered(CropAspect.ORIGINAL, landscape.first, landscape.second)

        assertRect(CropRect.Full, rect)
        assertTrue(rect.isFull)
    }

    @Test
    fun `a degenerate image falls back to the full frame rather than dividing by zero`() {
        assertRect(CropRect.Full, CropGeometry.centered(CropAspect.SQUARE, 0, 0))
        assertNull(CropGeometry.normalizedRatio(CropAspect.SQUARE, 0, 100))
    }

    // ── Free-corner drags ─────────────────────────────────────────────────────

    @Test
    fun `dragging a corner leaves the opposite one exactly where it was`() {
        val dragged = CropGeometry.drag(
            rect = CropRect.Full,
            handle = CropHandle.TOP_LEFT,
            x = 0.3f,
            y = 0.2f,
            aspect = CropAspect.FREE,
            imageWidth = landscape.first,
            imageHeight = landscape.second,
        )

        assertRect(CropRect(0.3f, 0.2f, 1f, 1f), dragged)
    }

    @Test
    fun `a corner dragged outside the photo stops at its edge`() {
        // A finger that runs off the photo must not crop black in from outside it.
        val dragged = CropGeometry.drag(
            rect = CropRect(0.2f, 0.2f, 0.8f, 0.8f),
            handle = CropHandle.BOTTOM_RIGHT,
            x = 1.9f,
            y = 1.4f,
            aspect = CropAspect.FREE,
            imageWidth = landscape.first,
            imageHeight = landscape.second,
        )

        assertRect(CropRect(0.2f, 0.2f, 1f, 1f), dragged)
    }

    @Test
    fun `a corner dragged past its opposite collapses to the minimum, not through it`() {
        val dragged = CropGeometry.drag(
            rect = CropRect.Full,
            handle = CropHandle.TOP_LEFT,
            x = 1f,
            y = 1f,
            aspect = CropAspect.FREE,
            imageWidth = landscape.first,
            imageHeight = landscape.second,
        )

        assertEquals(CropGeometry.MIN_SIDE, dragged.width, 0.001f)
        assertEquals(CropGeometry.MIN_SIDE, dragged.height, 0.001f)
        assertTrue("the frame must not invert", dragged.right > dragged.left)
        assertTrue("the frame must not invert", dragged.bottom > dragged.top)
    }

    @Test
    fun `a corner dragged well beyond its opposite stays at the minimum instead of growing back`() {
        // Regression: the drag measured an unsigned distance from the anchor, so a
        // finger that kept going past the opposite corner grew the frame again,
        // mirrored back onto the near side of the anchor. The test above drags
        // exactly onto the anchor, where the mirror is zero, so it never saw it.
        val dragged = CropGeometry.drag(
            rect = CropRect(0.2f, 0.2f, 0.8f, 0.8f),
            handle = CropHandle.TOP_LEFT,
            x = 0.95f,
            y = 0.95f,
            aspect = CropAspect.FREE,
            imageWidth = landscape.first,
            imageHeight = landscape.second,
        )

        assertRect(
            CropRect(0.8f - CropGeometry.MIN_SIDE, 0.8f - CropGeometry.MIN_SIDE, 0.8f, 0.8f),
            dragged,
        )
    }

    // ── Aspect-locked drags ───────────────────────────────────────────────────

    @Test
    fun `an aspect-locked drag keeps the output ratio whatever the finger does`() {
        val dragged = CropGeometry.drag(
            rect = CropGeometry.centered(CropAspect.SQUARE, landscape.first, landscape.second),
            handle = CropHandle.BOTTOM_RIGHT,
            x = 0.6f,
            // Pulling hard on one axis only: the frame must still come out square
            // in *pixels*, not in normalized units.
            y = 0.95f,
            aspect = CropAspect.SQUARE,
            imageWidth = landscape.first,
            imageHeight = landscape.second,
        )

        val pixelWidth = dragged.width * landscape.first
        val pixelHeight = dragged.height * landscape.second
        assertEquals(1f, pixelWidth / pixelHeight, 0.01f)
    }

    @Test
    fun `an aspect-locked drag that would leave the photo is scaled down to fit`() {
        // Anchored at the top-left, a 16:9 frame pulled to the far corner cannot
        // have both edges: one has to give, and the frame must stay inside.
        val dragged = CropGeometry.drag(
            rect = CropGeometry.centered(CropAspect.WIDE, landscape.first, landscape.second),
            handle = CropHandle.BOTTOM_RIGHT,
            x = 1.5f,
            y = 1.5f,
            aspect = CropAspect.WIDE,
            imageWidth = landscape.first,
            imageHeight = landscape.second,
        )

        assertTrue(dragged.left >= -0.001f && dragged.right <= 1.001f)
        assertTrue(dragged.top >= -0.001f && dragged.bottom <= 1.001f)
        val pixelWidth = dragged.width * landscape.first
        val pixelHeight = dragged.height * landscape.second
        assertEquals(16f / 9f, pixelWidth / pixelHeight, 0.02f)
    }

    @Test
    fun `an aspect-locked drag collapsed onto its anchor keeps the ratio at the minimum`() {
        val start = CropGeometry.centered(CropAspect.PORTRAIT, landscape.first, landscape.second)
        val dragged = CropGeometry.drag(
            rect = start,
            handle = CropHandle.TOP_LEFT,
            x = start.right,
            y = start.bottom,
            aspect = CropAspect.PORTRAIT,
            imageWidth = landscape.first,
            imageHeight = landscape.second,
        )

        assertTrue(dragged.width >= CropGeometry.MIN_SIDE - 0.001f)
        assertTrue(dragged.height >= CropGeometry.MIN_SIDE - 0.001f)
        val pixelWidth = dragged.width * landscape.first
        val pixelHeight = dragged.height * landscape.second
        assertEquals(4f / 5f, pixelWidth / pixelHeight, 0.02f)
    }

    // ── Side drags ────────────────────────────────────────────────────────────

    @Test
    fun `a free side drag moves only its own edge`() {
        val dragged = CropGeometry.drag(
            rect = CropRect(0.2f, 0.2f, 0.8f, 0.8f),
            handle = CropHandle.RIGHT,
            x = 0.6f,
            // A finger never travels in a straight line; the vertical wobble must
            // not leak into a grip that owns no vertical edge.
            y = 0.95f,
            aspect = CropAspect.FREE,
            imageWidth = landscape.first,
            imageHeight = landscape.second,
        )

        assertRect(CropRect(0.2f, 0.2f, 0.6f, 0.8f), dragged)
    }

    @Test
    fun `a free top drag stops at the edge of the photo`() {
        val dragged = CropGeometry.drag(
            rect = CropRect(0.2f, 0.2f, 0.8f, 0.8f),
            handle = CropHandle.TOP,
            x = 0.1f,
            y = -0.4f,
            aspect = CropAspect.FREE,
            imageWidth = landscape.first,
            imageHeight = landscape.second,
        )

        assertRect(CropRect(0.2f, 0f, 0.8f, 0.8f), dragged)
    }

    @Test
    fun `a side dragged past its opposite collapses to the minimum, not through it`() {
        val dragged = CropGeometry.drag(
            rect = CropRect(0.2f, 0.2f, 0.8f, 0.8f),
            handle = CropHandle.LEFT,
            x = 0.95f,
            y = 0.5f,
            aspect = CropAspect.FREE,
            imageWidth = landscape.first,
            imageHeight = landscape.second,
        )

        assertRect(CropRect(0.8f - CropGeometry.MIN_SIDE, 0.2f, 0.8f, 0.8f), dragged)
    }

    @Test
    fun `an aspect-locked side drag keeps the ratio by resizing the other axis about its centre`() {
        // 1:1 on a 4:3 photo starts as (0.125, 0, 0.875, 1). Pulling the right
        // side in to 0.5 leaves 0.375 of width, so 0.5 of height, centred.
        val dragged = CropGeometry.drag(
            rect = CropGeometry.centered(CropAspect.SQUARE, landscape.first, landscape.second),
            handle = CropHandle.RIGHT,
            x = 0.5f,
            y = 0.5f,
            aspect = CropAspect.SQUARE,
            imageWidth = landscape.first,
            imageHeight = landscape.second,
        )

        assertRect(CropRect(0.125f, 0.25f, 0.5f, 0.75f), dragged)
    }

    @Test
    fun `a vertical side drag uses the ratio the other way up`() {
        // Pulling the top of the same square down to 0.5 leaves 0.5 of height —
        // 1500 px — so 0.375 of width, centred. Using width-over-height here
        // unconverted would give a frame 2.25 times too narrow.
        val dragged = CropGeometry.drag(
            rect = CropGeometry.centered(CropAspect.SQUARE, landscape.first, landscape.second),
            handle = CropHandle.TOP,
            x = 0.5f,
            y = 0.5f,
            aspect = CropAspect.SQUARE,
            imageWidth = landscape.first,
            imageHeight = landscape.second,
        )

        assertRect(CropRect(0.3125f, 0.5f, 0.6875f, 1f), dragged)
    }

    @Test
    fun `an aspect-locked frame resting on an edge grows away from it`() {
        // A square on the bottom edge cannot grow about its centre without
        // leaving the photo, so it is pushed back up rather than refusing to grow.
        val dragged = CropGeometry.drag(
            rect = CropRect(0.125f, 0.5f, 0.5f, 1f),
            handle = CropHandle.LEFT,
            x = 0f,
            y = 0.75f,
            aspect = CropAspect.SQUARE,
            imageWidth = landscape.first,
            imageHeight = landscape.second,
        )

        assertRect(CropRect(0f, 1f / 3f, 0.5f, 1f), dragged)
    }

    @Test
    fun `an aspect-locked side drag stops where the other axis fills the photo`() {
        // A square on a 4:3 photo can be no wider than the photo is tall, so a
        // left side pulled all the way out stops at 0.75 of width.
        val dragged = CropGeometry.drag(
            rect = CropRect(0.7f, 0.4f, 0.9f, 0.4f + 800f / 3000f),
            handle = CropHandle.LEFT,
            x = -1f,
            y = 0.5f,
            aspect = CropAspect.SQUARE,
            imageWidth = landscape.first,
            imageHeight = landscape.second,
        )

        assertRect(CropRect(0.15f, 0f, 0.9f, 1f), dragged)
    }

    // ── Moving the whole frame ────────────────────────────────────────────────

    @Test
    fun `moving the frame stops at the edge instead of sliding off it`() {
        val moved = CropGeometry.move(CropRect(0.6f, 0.6f, 0.9f, 0.9f), dx = 0.5f, dy = 0.5f)

        assertRect(CropRect(0.7f, 0.7f, 1f, 1f), moved)
        assertEquals("the size must not change while moving", 0.3f, moved.width, 0.001f)
    }

    @Test
    fun `moving the frame backwards stops at the origin`() {
        val moved = CropGeometry.move(CropRect(0.1f, 0.1f, 0.4f, 0.4f), dx = -0.9f, dy = -0.9f)

        assertRect(CropRect(0f, 0f, 0.3f, 0.3f), moved)
    }

    // ── Which handle the finger has ───────────────────────────────────────────

    @Test
    fun `a touch near a corner grabs that corner`() {
        val rect = CropRect(0.2f, 0.2f, 0.8f, 0.8f)

        assertEquals(
            CropHandle.TOP_LEFT,
            CropGeometry.handleAt(rect, 0.22f, 0.23f, toleranceX = 0.06f, toleranceY = 0.06f),
        )
        assertEquals(
            CropHandle.BOTTOM_RIGHT,
            CropGeometry.handleAt(rect, 0.79f, 0.78f, toleranceX = 0.06f, toleranceY = 0.06f),
        )
    }

    @Test
    fun `a touch at the middle of a side grabs that side`() {
        val rect = CropRect(0.2f, 0.2f, 0.8f, 0.8f)

        assertEquals(
            CropHandle.RIGHT,
            CropGeometry.handleAt(rect, 0.79f, 0.52f, toleranceX = 0.06f, toleranceY = 0.06f),
        )
        assertEquals(
            CropHandle.TOP,
            CropGeometry.handleAt(rect, 0.48f, 0.21f, toleranceX = 0.06f, toleranceY = 0.06f),
        )
    }

    @Test
    fun `a touch on a side away from its middle is not a grip`() {
        // The side grips are a fingertip, like the corners, not the whole edge —
        // the rest of the edge still belongs to moving the frame.
        val rect = CropRect(0.2f, 0.2f, 0.8f, 0.8f)

        assertNull(CropGeometry.handleAt(rect, 0.8f, 0.35f, toleranceX = 0.06f, toleranceY = 0.06f))
    }

    @Test
    fun `a grip reaches further into the frame than out of it`() {
        // Regression: a corner answered only within its tolerance, and on a phone
        // the outer half of that sits in the back-gesture strip. The inward reach
        // is what lets a finger take it from inside the frame instead.
        val rect = CropRect(0.2f, 0.2f, 0.8f, 0.8f)

        assertNull(
            "without an inward reach this touch is too far in",
            CropGeometry.handleAt(rect, 0.33f, 0.32f, toleranceX = 0.06f, toleranceY = 0.06f),
        )
        assertEquals(
            CropHandle.TOP_LEFT,
            CropGeometry.handleAt(
                rect, 0.33f, 0.32f, toleranceX = 0.06f, toleranceY = 0.06f, reachX = 0.16f, reachY = 0.16f,
            ),
        )
        assertEquals(
            "a side grip reaches inward across its side",
            CropHandle.RIGHT,
            CropGeometry.handleAt(
                rect, 0.68f, 0.5f, toleranceX = 0.06f, toleranceY = 0.06f, reachX = 0.16f, reachY = 0.16f,
            ),
        )
    }

    @Test
    fun `outside the frame a grip reaches no further than its tolerance`() {
        val rect = CropRect(0.2f, 0.2f, 0.8f, 0.8f)

        assertNull(
            CropGeometry.handleAt(
                rect, 0.12f, 0.2f, toleranceX = 0.06f, toleranceY = 0.06f, reachX = 0.16f, reachY = 0.16f,
            ),
        )
    }

    @Test
    fun `a small frame caps the inward reach so its middle still moves the frame`() {
        // Uncapped, the 0.16 reach of the top grip would cover the centre of this
        // 0.2 frame, and the frame could never be dragged bodily again.
        val small = CropRect(0.4f, 0.4f, 0.6f, 0.6f)

        assertNull(
            CropGeometry.handleAt(
                small, 0.5f, 0.5f, toleranceX = 0.06f, toleranceY = 0.06f, reachX = 0.16f, reachY = 0.16f,
            ),
        )
        assertTrue(CropGeometry.contains(small, 0.5f, 0.5f))
    }

    @Test
    fun `a side grip's drag starts from the middle of its side`() {
        val rect = CropRect(0.2f, 0.2f, 0.8f, 0.6f)

        assertEquals(FitPoint(0.8f, 0.4f), CropGeometry.gripPoint(rect, CropHandle.RIGHT))
        assertEquals(FitPoint(0.5f, 0.6f), CropGeometry.gripPoint(rect, CropHandle.BOTTOM))
        assertEquals(FitPoint(0.2f, 0.2f), CropGeometry.gripPoint(rect, CropHandle.TOP_LEFT))
    }

    @Test
    fun `a touch in the middle grabs no corner but is inside the frame`() {
        val rect = CropRect(0.2f, 0.2f, 0.8f, 0.8f)

        assertNull(CropGeometry.handleAt(rect, 0.5f, 0.5f, toleranceX = 0.06f, toleranceY = 0.06f))
        assertTrue(CropGeometry.contains(rect, 0.5f, 0.5f))
        assertFalse(CropGeometry.contains(rect, 0.05f, 0.5f))
    }

    @Test
    fun `a touch within reach of several corners takes the nearest`() {
        // A tiny frame puts all four corners inside one fingertip. Picking by
        // distance is the only answer that matches the one the user is aiming at.
        val tiny = CropRect(0.48f, 0.48f, 0.52f, 0.52f)

        assertEquals(
            CropHandle.TOP_LEFT,
            CropGeometry.handleAt(tiny, 0.485f, 0.485f, toleranceX = 0.2f, toleranceY = 0.2f),
        )
        assertEquals(
            CropHandle.BOTTOM_RIGHT,
            CropGeometry.handleAt(tiny, 0.515f, 0.515f, toleranceX = 0.2f, toleranceY = 0.2f),
        )
    }

    @Test
    fun `a zero tolerance does not divide by zero`() {
        assertNotNull(
            CropGeometry.handleAt(CropRect.Full, 0f, 0f, toleranceX = 0f, toleranceY = 0f),
        )
    }

    // ── Turning a frame into an op ────────────────────────────────────────────

    @Test
    fun `a frame dragged back to the corners writes no crop op`() {
        // Sub-pixel float drift must not make Done re-encode a byte-identical
        // copy of the photo and burn a history step on it.
        assertNull(CropRect(0.0002f, 0f, 0.9998f, 1f).toOp())
        assertNotNull(CropRect(0.1f, 0f, 1f, 1f).toOp())
    }
}
