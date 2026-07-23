package com.firestream.chat.ui.components

import coil.size.Dimension
import coil.size.Scale
import coil.size.Size
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ScaledImageDecoderTest {

    @Test
    fun `large source into small square tile scales down preserving aspect ratio`() {
        // 4000x3000 original into a ~360px FILL tile (the black-tile case).
        val (w, h) = computeTargetSize(
            srcWidth = 4000,
            srcHeight = 3000,
            size = Size(360, 360),
            scale = Scale.FILL,
        )
        // FILL covers the box: the smaller source edge (3000) maps to 360.
        assertEquals(360, h)
        assertEquals(480, w)
        // Aspect ratio preserved (4:3).
        assertEquals(4.0 / 3.0, w.toDouble() / h.toDouble(), 0.01)
    }

    @Test
    fun `fill covers the target box, fit is contained within it`() {
        val fill = computeTargetSize(4000, 3000, Size(360, 360), Scale.FILL)
        val fit = computeTargetSize(4000, 3000, Size(360, 360), Scale.FIT)

        // FILL: shorter edge == target (480x360, covers). FIT: longer edge == target (360x270, contained).
        assertEquals(480 to 360, fill)
        assertEquals(360 to 270, fit)
    }

    @Test
    fun `never upscales a source smaller than the target`() {
        val (w, h) = computeTargetSize(
            srcWidth = 100,
            srcHeight = 80,
            size = Size(360, 360),
            scale = Scale.FILL,
        )
        assertEquals(100, w)
        assertEquals(80, h)
    }

    @Test
    fun `undefined dimensions fall back to the source dimension`() {
        val (w, h) = computeTargetSize(
            srcWidth = 4000,
            srcHeight = 3000,
            size = Size(Dimension.Undefined, Dimension.Undefined),
            scale = Scale.FILL,
        )
        // No constraint on either axis -> decode at source size.
        assertEquals(4000, w)
        assertEquals(3000, h)
    }

    @Test
    fun `one undefined axis constrains by the defined axis`() {
        // Width undefined, height capped at 300: FIT/FILL both driven by height.
        val (w, h) = computeTargetSize(
            srcWidth = 4000,
            srcHeight = 3000,
            size = Size(Dimension.Undefined, Dimension.Pixels(300)),
            scale = Scale.FIT,
        )
        assertEquals(400, w)
        assertEquals(300, h)
    }

    @Test
    fun `degenerate source dimensions are returned untouched`() {
        assertEquals(0 to 0, computeTargetSize(0, 0, Size(360, 360), Scale.FILL))
    }

    @Test
    fun `target is at least one pixel`() {
        // Extreme downscale must not round to zero.
        val (w, h) = computeTargetSize(4000, 3000, Size(1, 1), Scale.FIT)
        assertTrue(w >= 1)
        assertTrue(h >= 1)
    }
}
