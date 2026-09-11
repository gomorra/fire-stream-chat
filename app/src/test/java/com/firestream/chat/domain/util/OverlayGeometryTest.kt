package com.firestream.chat.domain.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.hypot

/**
 * The overlay screen's arithmetic, on the JVM.
 *
 * This is the layer a Robolectric test of the screen cannot reach: a composed
 * screen renders without a finger ever crossing it, so "the scale handle is
 * where the corner is" and "88° becomes 90°" are only ever really checked here.
 * Same seam and same reason as `CropGeometryTest` and `StrokeGeometryTest` —
 * and it matters more than usual because these numbers are the *only* thing
 * keeping the Compose preview and the `android.graphics` flatten in agreement
 * about where a sticker sits.
 */
class OverlayGeometryTest {

    // ── Size ─────────────────────────────────────────────────────────────────

    @Test
    fun `text wraps at the photo's width, whichever space it is measured in`() {
        val onPreview = OverlayGeometry.textWrapWidthPx(imageWidthPx = 1080f)
        val onFile = OverlayGeometry.textWrapWidthPx(imageWidthPx = 3024f)

        // The same fraction of each: a line that broke after "the" on screen
        // breaks after "the" in the file.
        assertEquals(onPreview / 1080f, onFile / 3024f, 0.0001f)
        assertEquals(OverlayGeometry.TEXT_WRAP_WIDTH, onPreview / 1080f, 0.0001f)
    }


    @Test
    fun `size is a fraction of the long edge, so a placement survives the jump to full resolution`() {
        val onPreview = OverlayGeometry.sizePx(scale = 1f, imageLongEdgePx = 1600f)
        val onFile = OverlayGeometry.sizePx(scale = 1f, imageLongEdgePx = 4096f)

        // The same fraction of each, which is the whole promise.
        assertEquals(OverlayGeometry.BASE_SIZE, onPreview / 1600f, 0.0001f)
        assertEquals(OverlayGeometry.BASE_SIZE, onFile / 4096f, 0.0001f)
    }

    @Test
    fun `scale is clamped at both ends`() {
        val tiny = OverlayGeometry.sizePx(scale = 0.001f, imageLongEdgePx = 1000f)
        val huge = OverlayGeometry.sizePx(scale = 99f, imageLongEdgePx = 1000f)

        assertEquals(OverlayGeometry.BASE_SIZE * OverlayGeometry.MIN_SCALE * 1000f, tiny, 0.01f)
        assertEquals(OverlayGeometry.BASE_SIZE * OverlayGeometry.MAX_SCALE * 1000f, huge, 0.01f)
    }

    // ── Angles ───────────────────────────────────────────────────────────────

    @Test
    fun `angles fold into the signed range a readout can show`() {
        assertEquals(-170f, OverlayGeometry.normalizeAngle(190f), 0.001f)
        assertEquals(0f, OverlayGeometry.normalizeAngle(360f), 0.001f)
        assertEquals(180f, OverlayGeometry.normalizeAngle(-180f), 0.001f)
        assertEquals(-90f, OverlayGeometry.normalizeAngle(270f), 0.001f)
    }

    @Test
    fun `rotation snaps to the nearest fifteen when it is close enough`() {
        assertEquals(15f, OverlayGeometry.snapRotation(16f), 0.001f)
        assertEquals(45f, OverlayGeometry.snapRotation(43f), 0.001f)
        assertEquals(-30f, OverlayGeometry.snapRotation(-31.5f), 0.001f)
    }

    @Test
    fun `an angle between two stops is left where the finger put it`() {
        // 22° is 7° from 15 and 8° from 30: past the tolerance either way, so
        // snapping must not quietly round it. Every angle stays reachable.
        assertEquals(22f, OverlayGeometry.snapRotation(22f), 0.001f)
    }

    @Test
    fun `the cardinals snap from further out than the other stops`() {
        // 84° is 6° from 90 — inside the cardinal tolerance, outside the ordinary
        // one. "Exactly square" is the angle a hand cannot hit, and the one a
        // rectangle drawn round something most often wants.
        assertEquals(90f, OverlayGeometry.snapRotation(84f), 0.001f)
        assertEquals(0f, OverlayGeometry.snapRotation(-6f), 0.001f)
        assertEquals(180f, OverlayGeometry.snapRotation(174f), 0.001f)

        // The same 6° gap from a non-cardinal stop does not snap.
        assertEquals(51f, OverlayGeometry.snapRotation(51f), 0.001f)
    }

    // ── Local ⇄ world ────────────────────────────────────────────────────────

    @Test
    fun `a point round-trips through the object's own frame at any angle`() {
        val point = OverlayPoint(310f, 190f)
        for (angle in listOf(0f, 15f, 37f, 90f, -128f)) {
            val local = OverlayGeometry.toLocal(point, 200f, 150f, angle)
            val back = OverlayGeometry.toWorld(local, 200f, 150f, angle)
            assertEquals("x at $angle°", point.x, back.x, 0.01f)
            assertEquals("y at $angle°", point.y, back.y, 0.01f)
        }
    }

    @Test
    fun `a quarter turn puts the object's own right where the screen's down is`() {
        // The object is rotated 90° clockwise, so a point directly below its
        // centre on screen is straight out along its own +x.
        val local = OverlayGeometry.toLocal(OverlayPoint(100f, 150f), 100f, 100f, 90f)

        assertEquals(50f, local.x, 0.01f)
        assertEquals(0f, local.y, 0.01f)
    }

    // ── Hit testing ──────────────────────────────────────────────────────────

    @Test
    fun `a rotated object is hit where it actually is, not where its upright box was`() {
        // A wide, short object turned on its side: the point 60 px *below* the
        // centre is on it, and the point 60 px to its right is not.
        val below = OverlayPoint(100f, 160f)
        val right = OverlayPoint(160f, 100f)

        assertTrue(
            OverlayGeometry.contains(below, 100f, 100f, halfWidth = 80f, halfHeight = 20f, rotationDegrees = 90f),
        )
        assertFalse(
            OverlayGeometry.contains(right, 100f, 100f, halfWidth = 80f, halfHeight = 20f, rotationDegrees = 90f),
        )
    }

    @Test
    fun `padding is what gives a small object a finger-sized target`() {
        val justOutside = OverlayPoint(100f, 132f)

        assertFalse(
            OverlayGeometry.contains(justOutside, 100f, 100f, halfWidth = 30f, halfHeight = 30f, rotationDegrees = 0f),
        )
        assertTrue(
            OverlayGeometry.contains(
                justOutside, 100f, 100f, halfWidth = 30f, halfHeight = 30f, rotationDegrees = 0f, padding = 10f,
            ),
        )
    }

    // ── Handles ──────────────────────────────────────────────────────────────

    @Test
    fun `scale sits at the bottom-right corner and rotate at the top-right`() {
        val scale = OverlayGeometry.handleCenter(
            OverlayHandle.SCALE, 100f, 100f, halfWidth = 40f, halfHeight = 30f, rotationDegrees = 0f,
        )
        val rotate = OverlayGeometry.handleCenter(
            OverlayHandle.ROTATE, 100f, 100f, halfWidth = 40f, halfHeight = 30f, rotationDegrees = 0f,
        )

        assertEquals(140f, scale.x, 0.01f)
        assertEquals(130f, scale.y, 0.01f)
        assertEquals(140f, rotate.x, 0.01f)
        assertEquals(70f, rotate.y, 0.01f)
    }

    @Test
    fun `the handles travel with the object when it is turned`() {
        // Turned 180°, the bottom-right corner is up and to the left.
        val scale = OverlayGeometry.handleCenter(
            OverlayHandle.SCALE, 100f, 100f, halfWidth = 40f, halfHeight = 30f, rotationDegrees = 180f,
        )

        assertEquals(60f, scale.x, 0.01f)
        assertEquals(70f, scale.y, 0.01f)
    }

    // ── Drags ────────────────────────────────────────────────────────────────

    @Test
    fun `rotating is a difference of angles, so grabbing the handle does not jerk the object`() {
        // The finger lands 30° round from where the handle is and drags 20°
        // further: the object turns by 20, not to 50.
        val next = OverlayGeometry.rotationFromDrag(
            startRotation = 0f,
            startAngle = 30f,
            currentAngle = 52f,
        )

        assertEquals(22f, next, 0.001f)
    }

    @Test
    fun `a rotation drag lands on a snap the same way a direct angle does`() {
        val next = OverlayGeometry.rotationFromDrag(startRotation = 0f, startAngle = 0f, currentAngle = 88f)

        assertEquals(90f, next, 0.001f)
    }

    @Test
    fun `scaling tracks the finger as a ratio of its distance from the centre`() {
        val start = hypot(40f, 30f)

        assertEquals(2f, OverlayGeometry.scaleFromDrag(1f, start, start * 2f), 0.001f)
        assertEquals(0.5f, OverlayGeometry.scaleFromDrag(1f, start, start / 2f), 0.001f)
    }

    @Test
    fun `a scale drag cannot take an object past its bounds or divide by zero`() {
        assertEquals(OverlayGeometry.MAX_SCALE, OverlayGeometry.scaleFromDrag(1f, 10f, 9999f), 0.001f)
        assertEquals(OverlayGeometry.MIN_SCALE, OverlayGeometry.scaleFromDrag(1f, 10f, 0f), 0.001f)
        // A grab exactly on the centre has no ratio to form; the scale holds.
        assertEquals(1.3f, OverlayGeometry.scaleFromDrag(1.3f, 0f, 50f), 0.001f)
    }

    // ── Moving ───────────────────────────────────────────────────────────────

    @Test
    fun `an object may hang off the edge but its centre may not leave the photo`() {
        val overlay = ImageOverlay(OverlayContent.Emoji("🎉"), centerX = 0.9f, centerY = 0.5f)

        val moved = OverlayGeometry.movedTo(overlay, centerX = 1.3f, centerY = -0.4f)

        // Clamped, because a centre outside the image is an object nothing on
        // screen can be tapped to get back.
        assertEquals(1f, moved.centerX, 0.001f)
        assertEquals(0f, moved.centerY, 0.001f)
    }

    @Test
    fun `a move is a target, so applying the same drag event twice lands in the same place`() {
        val overlay = ImageOverlay(OverlayContent.Emoji("🎉"), centerX = 0.5f, centerY = 0.5f)

        // The drag reads the object as last drawn, and when two pointer events
        // land before the next frame both are applied to the newest state. A step
        // would count the first event again; a target cannot.
        val once = OverlayGeometry.movedTo(overlay, centerX = 0.6f, centerY = 0.45f)
        val twice = OverlayGeometry.movedTo(once, centerX = 0.6f, centerY = 0.45f)

        assertEquals(once, twice)
        assertEquals(0.6f, twice.centerX, 0.001f)
        assertEquals(0.45f, twice.centerY, 0.001f)
    }

    // ── Readouts ─────────────────────────────────────────────────────────────

    @Test
    fun `the readouts say what the handle is doing`() {
        assertEquals("1.4×", OverlayGeometry.scaleLabel(1.44f))
        assertEquals("-8°", OverlayGeometry.rotationLabel(-8f))
        // Signed rather than 0-359: the question is "how far off upright", and
        // 352° is a worse answer to it than -8°.
        assertEquals("-8°", OverlayGeometry.rotationLabel(352f))
    }

    @Test
    fun `a line and an arrow are wider than they are tall, a box less so`() {
        assertTrue(OverlayGeometry.aspectFor(ShapeKind.ARROW) > OverlayGeometry.aspectFor(ShapeKind.RECTANGLE))
        assertTrue(OverlayGeometry.aspectFor(ShapeKind.RECTANGLE) > 1f)
    }
}
