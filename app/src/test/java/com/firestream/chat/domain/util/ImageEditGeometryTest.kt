package com.firestream.chat.domain.util

import com.firestream.chat.domain.util.ImageEditGeometry.cappedSize
import com.firestream.chat.domain.util.ImageEditGeometry.cropRect
import com.firestream.chat.domain.util.ImageEditGeometry.estimatedDimensions
import com.firestream.chat.domain.util.ImageEditGeometry.normalizeQuarterTurn
import com.firestream.chat.domain.util.ImageEditGeometry.outputSize
import com.firestream.chat.domain.util.ImageEditGeometry.resizedSize
import com.firestream.chat.domain.util.ImageEditGeometry.straightenScale
import com.firestream.chat.domain.util.ImageEditGeometry.straightenSize
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The dimension arithmetic behind the image editor, on the JVM.
 *
 * Split out from the bitmap work on purpose: what an op list *does to the
 * dimensions* is the part an editor screen has to agree with — a crop handle is
 * drawn against these numbers, and a resize preset is labelled with them — so it
 * is worth checking without a decoder in the loop.
 */
class ImageEditGeometryTest {

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
        assertEquals(PixelRect(200, 300, 400, 300), cropRect(800, 600, half))
        assertEquals(400 to 300, outputSize(800, 600, listOf(half)))
    }

    @Test
    fun `an inverted or degenerate crop still yields at least one pixel`() {
        // Handles dragged past each other: the rect is normalised, not rejected.
        val inverted = RasterOp.Crop(left = 0.75f, top = 1f, right = 0.25f, bottom = 0.5f)
        assertEquals(PixelRect(200, 300, 400, 300), cropRect(800, 600, inverted))

        val collapsed = RasterOp.Crop(left = 0.5f, top = 0.5f, right = 0.5f, bottom = 0.5f)
        val rect = cropRect(800, 600, collapsed)
        assertEquals(1, rect.width)
        assertEquals(1, rect.height)
    }

    @Test
    fun `a crop outside the image is clamped to it`() {
        val overshoot = RasterOp.Crop(left = -0.5f, top = -0.5f, right = 1.5f, bottom = 1.5f)
        assertEquals(PixelRect(0, 0, 800, 600), cropRect(800, 600, overshoot))
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
        // The cap is passed in, not referenced: it is ImageCompressor's number
        // and domain cannot import the data layer.
        val standard = 1600
        assertEquals(4000 to 3000, estimatedDimensions(4000, 3000, hd = true, standardMaxDimension = standard))
        assertEquals(1600 to 1200, estimatedDimensions(4000, 3000, hd = false, standardMaxDimension = standard))
        assertEquals(800 to 600, estimatedDimensions(800, 600, hd = false, standardMaxDimension = standard))
        assertEquals(0 to 0, estimatedDimensions(0, 0, hd = false, standardMaxDimension = standard))
    }

    @Test
    fun `a standard estimate is smaller than an HD one for the same source`() {
        val hd = ImageEditGeometry.estimatedSize(4000, 2000, sourceBytes = 2_000_000, hd = true, standardMaxDimension = 1600)
        val standard = ImageEditGeometry.estimatedSize(4000, 2000, sourceBytes = 2_000_000, hd = false, standardMaxDimension = 1600)

        assertEquals(4000, hd.width)
        assertEquals(1600, standard.width)
        assertTrue(hd.bytes > standard.bytes)
        assertTrue(standard.bytes > 0)
    }

    @Test
    fun `a degenerate source estimates nothing rather than dividing by zero`() {
        val estimate = ImageEditGeometry.estimatedSize(0, 0, sourceBytes = 0, hd = false, standardMaxDimension = 1600)

        assertEquals(0, estimate.width)
        assertEquals(0L, estimate.bytes)
    }

    // ── Straighten ────────────────────────────────────────────────────────────

    @Test
    fun `no tilt keeps every pixel`() {
        assertEquals(1f, straightenScale(4000, 3000, 0f), 0.0001f)
        assertEquals(4000 to 3000, straightenSize(4000, 3000, 0f))
    }

    @Test
    fun `a straighten costs pixels in proportion to the angle`() {
        // The auto-crop is what keeps the corners from going black, and it is not
        // free: the user is meant to see the image grow as they drag, and these
        // are the numbers that growth is.
        val gentle = straightenScale(4000, 3000, 2f)
        val steep = straightenScale(4000, 3000, 20f)

        assertTrue("a tilt always costs something", gentle < 1f)
        assertTrue("a bigger tilt costs more", steep < gentle)
        assertTrue("but a gentle tilt costs very little", gentle > 0.95f)
    }

    @Test
    fun `the cost is symmetric in the direction of the tilt`() {
        assertEquals(straightenScale(4000, 3000, 7f), straightenScale(4000, 3000, -7f), 0.0001f)
    }

    @Test
    fun `a square at the limit keeps the inscribed square`() {
        // A 45-degree turn of a square inscribes a square of side w/(cos+sin),
        // i.e. 1/root-two — the one case with a closed form worth pinning.
        assertEquals(0.7071f, straightenScale(1000, 1000, 45f), 0.001f)
    }

    @Test
    fun `an angle past the limit is clamped rather than allowed to invert`() {
        assertEquals(straightenScale(1000, 1000, 45f), straightenScale(1000, 1000, 120f), 0.0001f)
        assertEquals(straightenScale(1000, 1000, -45f), straightenScale(1000, 1000, -400f), 0.0001f)
    }

    @Test
    fun `a straighten preserves the aspect ratio it started with`() {
        val (width, height) = straightenSize(4000, 3000, 12f)

        assertEquals(4000f / 3000f, width.toFloat() / height, 0.01f)
        assertTrue(width < 4000)
    }

    @Test
    fun `the inscribed rectangle actually fits inside the rotated photo`() {
        // The property the closed form is meant to have, checked directly: rotate
        // the kept rectangle back into the photo's own frame and its bounding box
        // must still fit the photo. If it does not, the output has black corners.
        for (degrees in listOf(1f, 5f, 12.5f, 30f, 45f)) {
            for ((width, height) in listOf(4000 to 3000, 3000 to 4000, 1000 to 1000, 4000 to 1000)) {
                val scale = straightenScale(width, height, degrees)
                val radians = Math.toRadians(degrees.toDouble())
                val cos = kotlin.math.abs(kotlin.math.cos(radians))
                val sin = kotlin.math.abs(kotlin.math.sin(radians))
                val boundsWidth = scale * (width * cos + height * sin)
                val boundsHeight = scale * (width * sin + height * cos)
                assertTrue(
                    "$degrees on $width x $height overflows the width",
                    boundsWidth <= width + 0.5,
                )
                assertTrue(
                    "$degrees on $width x $height overflows the height",
                    boundsHeight <= height + 0.5,
                )
            }
        }
    }

    @Test
    fun `a degenerate image straightens to nothing rather than dividing by zero`() {
        assertEquals(1f, straightenScale(0, 0, 20f), 0.0001f)
        assertEquals(0 to 0, straightenSize(0, 0, 20f))
    }

    @Test
    fun `outputSize walks a straighten like every other op`() {
        val direct = straightenSize(4000, 3000, 10f)

        assertEquals(direct, outputSize(4000, 3000, listOf(RasterOp.Straighten(10f))))
    }

    @Test
    fun `a straighten composes with the ops around it`() {
        // The order matters and the arithmetic has to follow it: the quarter turn
        // swaps the edges before the straighten measures them.
        val ops = listOf(RasterOp.Rotate(90), RasterOp.Straighten(15f), RasterOp.Resize(1000))
        val (width, height) = outputSize(4000, 3000, ops)

        val (turnedWidth, turnedHeight) = 3000 to 4000
        val (straightened) = straightenSize(turnedWidth, turnedHeight, 15f)
        assertEquals(1000, maxOf(width, height))
        assertTrue(straightened < turnedWidth)
    }

    // ── Pricing an output ─────────────────────────────────────────────────────

    @Test
    fun `the resize row and the HD sheet price the same photo the same way`() {
        // Two callers, one formula: a preset labelled "~340 KB" and an HD row
        // labelled "about 340 KB" must not disagree about the same image.
        val source = SourceImage(4000, 3000, bytes = 2_400_000)
        val viaEstimate = ImageEditGeometry.estimatedSize(
            width = source.width,
            height = source.height,
            sourceBytes = source.bytes,
            hd = true,
            standardMaxDimension = 1600,
        )

        assertEquals(
            viaEstimate.bytes,
            ImageEditGeometry.estimatedBytes(source.width, source.height, source, hd = true),
        )
    }

    @Test
    fun `a smaller output is priced smaller, and an unmeasurable one at nothing`() {
        val source = SourceImage(4000, 3000, bytes = 2_400_000)

        val large = ImageEditGeometry.estimatedBytes(2048, 1536, source, hd = false)
        val small = ImageEditGeometry.estimatedBytes(720, 540, source, hd = false)

        assertTrue(large > small)
        assertTrue(small > 0)
        assertEquals(0L, ImageEditGeometry.estimatedBytes(0, 0, source, hd = false))
        assertEquals(0L, ImageEditGeometry.estimatedBytes(100, 100, SourceImage(0, 0, 0), hd = false))
    }

    @Test
    fun `a source with no reported size is still priced as a photo`() {
        // A provider that withholds the size must not make the row read "0 KB".
        val bytes = ImageEditGeometry.estimatedBytes(1600, 1200, SourceImage(4000, 3000, 0), hd = false)

        assertTrue(bytes > 0)
    }
}
