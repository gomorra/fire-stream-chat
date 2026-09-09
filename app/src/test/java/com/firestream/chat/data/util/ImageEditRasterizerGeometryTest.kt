package com.firestream.chat.data.util

import com.firestream.chat.data.util.ImageEditRasterizer.Companion.cappedSize
import com.firestream.chat.data.util.ImageEditRasterizer.Companion.cropRect
import com.firestream.chat.data.util.ImageEditRasterizer.Companion.estimatedDimensions
import com.firestream.chat.data.util.ImageEditRasterizer.Companion.normalizeQuarterTurn
import com.firestream.chat.data.util.ImageEditRasterizer.Companion.outputSize
import com.firestream.chat.data.util.ImageEditRasterizer.Companion.resizedSize
import com.firestream.chat.data.util.ImageEditRasterizer.RasterOp
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The dimension arithmetic behind [ImageEditRasterizer.rasterize], on the JVM.
 *
 * Split out from the bitmap work on purpose: what an op list *does to the
 * dimensions* is the part an editor screen has to agree with — a crop handle is
 * drawn against these numbers, and a resize preset is labelled with them — so it
 * is worth checking without a decoder in the loop.
 */
class ImageEditRasterizerGeometryTest {

    @Test
    fun `a source under the ceiling decodes at its own size`() {
        assertEquals(3000 to 2000, cappedSize(3000, 2000))
        assertEquals(4096 to 4096, cappedSize(4096, 4096))
    }

    @Test
    fun `a 108 megapixel original is capped on its long edge`() {
        // 12000 x 9000. The long edge lands on the ceiling and the aspect holds:
        // 4096 / 12000 = 0.34133, x 9000 = 3072.
        assertEquals(4096 to 3072, cappedSize(12000, 9000))
        assertEquals(3072 to 4096, cappedSize(9000, 12000))
    }

    @Test
    fun `a degenerate source is passed through rather than divided by`() {
        assertEquals(0 to 0, cappedSize(0, 0))
        assertEquals(-1 to 10, cappedSize(-1, 10))
    }

    @Test
    fun `quarter turns normalise in both directions`() {
        assertEquals(0, normalizeQuarterTurn(0))
        assertEquals(90, normalizeQuarterTurn(90))
        assertEquals(0, normalizeQuarterTurn(360))
        assertEquals(270, normalizeQuarterTurn(-90))
        assertEquals(90, normalizeQuarterTurn(450))
    }

    @Test
    fun `a quarter turn swaps the dimensions and a half turn does not`() {
        assertEquals(600 to 800, outputSize(800, 600, listOf(RasterOp.Rotate(90))))
        assertEquals(800 to 600, outputSize(800, 600, listOf(RasterOp.Rotate(180))))
        assertEquals(600 to 800, outputSize(800, 600, listOf(RasterOp.Rotate(270))))
        assertEquals(800 to 600, outputSize(800, 600, listOf(RasterOp.Rotate(360))))
    }

    @Test
    fun `a flip changes no dimension`() {
        assertEquals(800 to 600, outputSize(800, 600, listOf(RasterOp.Flip(horizontal = true))))
        assertEquals(800 to 600, outputSize(800, 600, listOf(RasterOp.Flip(horizontal = false))))
    }

    @Test
    fun `a crop takes the fractions of the image it is given`() {
        val half = RasterOp.Crop(left = 0.25f, top = 0.5f, right = 0.75f, bottom = 1f)
        assertEquals(ImageEditRasterizer.PixelRect(200, 300, 400, 300), cropRect(800, 600, half))
        assertEquals(400 to 300, outputSize(800, 600, listOf(half)))
    }

    @Test
    fun `an inverted or degenerate crop still yields at least one pixel`() {
        // Handles dragged past each other: the rect is normalised, not rejected.
        val inverted = RasterOp.Crop(left = 0.75f, top = 1f, right = 0.25f, bottom = 0.5f)
        assertEquals(ImageEditRasterizer.PixelRect(200, 300, 400, 300), cropRect(800, 600, inverted))

        val collapsed = RasterOp.Crop(left = 0.5f, top = 0.5f, right = 0.5f, bottom = 0.5f)
        val rect = cropRect(800, 600, collapsed)
        assertEquals(1, rect.width)
        assertEquals(1, rect.height)
    }

    @Test
    fun `a crop outside the image is clamped to it`() {
        val overshoot = RasterOp.Crop(left = -0.5f, top = -0.5f, right = 1.5f, bottom = 1.5f)
        assertEquals(ImageEditRasterizer.PixelRect(0, 0, 800, 600), cropRect(800, 600, overshoot))
    }

    @Test
    fun `a resize scales the long edge and keeps the aspect`() {
        assertEquals(1080 to 810, resizedSize(4000, 3000, 1080))
        assertEquals(810 to 1080, resizedSize(3000, 4000, 1080))
        assertEquals(1080 to 1080, resizedSize(2000, 2000, 1080))
    }

    @Test
    fun `a resize never upscales`() {
        // The presets exist to make an image smaller; upscaling a JPEG adds
        // bytes and no detail.
        assertEquals(800 to 600, resizedSize(800, 600, 2048))
        assertEquals(800 to 600, resizedSize(800, 600, 800))
        assertEquals(800 to 600, resizedSize(800, 600, 0))
    }

    @Test
    fun `ops compose in order, each against what the last one left`() {
        // Rotate a landscape shot upright, crop its lower half, then cap it at 720.
        val ops = listOf(
            RasterOp.Rotate(90),
            RasterOp.Crop(left = 0f, top = 0.5f, right = 1f, bottom = 1f),
            RasterOp.Resize(720),
        )
        // 4000x3000 -> rotate -> 3000x4000 -> crop -> 3000x2000 -> resize -> 720x480.
        assertEquals(720 to 480, outputSize(4000, 3000, ops))
    }

    @Test
    fun `the working ceiling applies before the ops do`() {
        // A 108 MP original is decoded at 4096x3072 first, so a half-height crop
        // is half of *that*, not half of the original.
        val ops = listOf(RasterOp.Crop(left = 0f, top = 0f, right = 1f, bottom = 0.5f))
        assertEquals(4096 to 1536, outputSize(12000, 9000, ops))
    }

    @Test
    fun `an HD send keeps the source resolution and a standard send caps it`() {
        assertEquals(4000 to 3000, estimatedDimensions(4000, 3000, hd = true))
        // ImageCompressor's own MAX_DIMENSION, quoted rather than restated.
        assertEquals(1600 to 1200, estimatedDimensions(4000, 3000, hd = false))
        assertEquals(800 to 600, estimatedDimensions(800, 600, hd = false))
        assertEquals(0 to 0, estimatedDimensions(0, 0, hd = false))
    }
}
