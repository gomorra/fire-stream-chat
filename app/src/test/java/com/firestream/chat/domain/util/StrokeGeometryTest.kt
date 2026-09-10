package com.firestream.chat.domain.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The draw screen's arithmetic, on the JVM.
 *
 * This is the file that matters most in Phase 4, for the same reason
 * `CropGeometryTest` mattered most in Phase 3: everything visible on the draw
 * screen is a gesture over a coordinate mapping, and a Robolectric test renders
 * a composable without proving that the mapping under it is right. Worse, the
 * *same* numbers are consumed twice — once by the Compose preview and once by
 * the `android.graphics` flatten — so a stroke's width, its path and the mosaic
 * it reveals have to be computed here, once, or the preview and the file get
 * two chances to disagree. On a blur tool that disagreement is a privacy
 * failure rather than a cosmetic one.
 */
class StrokeGeometryTest {

    // ── Width ─────────────────────────────────────────────────────────────────

    @Test
    fun `a width is a fraction of the long edge, so it survives any output size`() {
        // The same stored stroke, drawn on a 1200 px preview and flattened into
        // a 4096 px file, must cover the same fraction of the photo.
        val onPreview = StrokeGeometry.widthPx(0.02f, longEdgePx = 1200f)
        val onOutput = StrokeGeometry.widthPx(0.02f, longEdgePx = 4096f)

        assertEquals(24f, onPreview, 0.01f)
        assertEquals(81.92f, onOutput, 0.01f)
        assertEquals(onPreview / 1200f, onOutput / 4096f, 1e-6f)
    }

    @Test
    fun `a width is clamped to the slider's range and never thinner than a pixel`() {
        assertEquals(
            StrokeGeometry.widthPx(StrokeGeometry.MAX_WIDTH, 1000f),
            StrokeGeometry.widthPx(StrokeGeometry.MAX_WIDTH * 4f, 1000f),
            0.001f,
        )
        assertEquals(
            StrokeGeometry.widthPx(StrokeGeometry.MIN_WIDTH, 1000f),
            StrokeGeometry.widthPx(0f, 1000f),
            0.001f,
        )
        // A thumbnail-sized target still gets a visible stroke rather than none.
        assertEquals(1f, StrokeGeometry.widthPx(StrokeGeometry.MIN_WIDTH, 20f), 0.001f)
    }

    // ── The mosaic ────────────────────────────────────────────────────────────

    @Test
    fun `the mosaic is a fixed number of blocks across, not a fixed scale factor`() {
        // The privacy-critical property: the redaction is the same fraction of
        // the photo on the preview and in the file, so what the user checks is
        // what gets written. A fixed ×1/16 scale would give a 4096 px file 16 px
        // blocks and a 1200 px preview 16 px blocks — two different redactions.
        val preview = StrokeGeometry.pixelatedSize(1200, 900)
        val output = StrokeGeometry.pixelatedSize(4096, 3072)

        assertEquals(StrokeGeometry.PIXELATE_BLOCKS, preview.first)
        assertEquals(StrokeGeometry.PIXELATE_BLOCKS, output.first)
        assertEquals(preview.second, output.second)
    }

    @Test
    fun `the mosaic keeps the aspect ratio and never collapses to nothing`() {
        assertEquals(StrokeGeometry.PIXELATE_BLOCKS / 2, StrokeGeometry.pixelatedSize(800, 400).second)
        assertEquals(StrokeGeometry.PIXELATE_BLOCKS, StrokeGeometry.pixelatedSize(400, 800).second)
        assertEquals(1, StrokeGeometry.pixelatedSize(4000, 3).second)
        assertEquals(1 to 1, StrokeGeometry.pixelatedSize(0, 0))
    }

    @Test
    fun `an image already coarser than the mosaic is left alone`() {
        // Upscaling a 20 px image to 48 blocks would invent detail and then
        // "redact" it, which is a worse redaction than the pixels it started with.
        assertEquals(20 to 12, StrokeGeometry.pixelatedSize(20, 12))
    }

    // ── Render order ──────────────────────────────────────────────────────────

    @Test
    fun `blur is applied under everything drawn, whatever order it was drawn in`() {
        // Blur redacts the photo; pen and highlighter annotate it. Rendering in
        // capture order instead would let a blur drawn afterwards swallow the
        // arrow that pointed at the thing being blurred, because the mosaic is
        // a copy of the *photo* and knows nothing about the strokes over it.
        val pen = stroke(StrokeTool.PEN, 0xFFFF0000)
        // Distinguishable from each other, so the assertion checks the order
        // *within* the blur group and not merely that two equal values are there.
        val blurA = stroke(StrokeTool.BLUR, 0, points = listOf(StrokePoint(0f, 0f), StrokePoint(0.1f, 0.1f)))
        val highlighter = stroke(StrokeTool.HIGHLIGHTER, 0xFFFFFF00)
        val blurB = stroke(StrokeTool.BLUR, 0, points = listOf(StrokePoint(0.9f, 0.9f), StrokePoint(1f, 1f)))

        val layers = StrokeGeometry.layers(listOf(pen, blurA, highlighter, blurB))

        assertEquals(listOf(blurA, blurB), layers.blur)
        assertEquals(listOf(pen, highlighter), layers.painted)
    }

    @Test
    fun `a drawing with no blur in it is rendered exactly as it was captured`() {
        val strokes = listOf(stroke(StrokeTool.PEN, 1), stroke(StrokeTool.HIGHLIGHTER, 2))

        assertEquals(strokes, StrokeGeometry.layers(strokes).painted)
        assertEquals(emptyList<Stroke>(), StrokeGeometry.layers(strokes).blur)
        assertFalse(StrokeGeometry.hasBlur(strokes))
        assertTrue(StrokeGeometry.hasBlur(strokes + stroke(StrokeTool.BLUR, 0)))
    }

    // ── Paths ─────────────────────────────────────────────────────────────────

    @Test
    fun `a two point stroke is a straight line in the space it is drawn into`() {
        val sink = RecordingSink()

        StrokeGeometry.buildPath(
            points = listOf(StrokePoint(0f, 0f), StrokePoint(1f, 0.5f)),
            scaleX = 200f,
            scaleY = 100f,
            offsetX = 10f,
            offsetY = 20f,
            sink = sink,
        )

        assertEquals(listOf("M 10.0 20.0", "L 210.0 70.0"), sink.calls)
    }

    @Test
    fun `a longer stroke curves through the midpoints between its samples`() {
        // Quadratics with the captured samples as control points and the
        // midpoints as ends — the standard smoothing, written once here so the
        // preview and the flatten cannot round a corner differently.
        val sink = RecordingSink()

        StrokeGeometry.buildPath(
            points = listOf(
                StrokePoint(0f, 0f),
                StrokePoint(0.5f, 0f),
                StrokePoint(0.5f, 1f),
                StrokePoint(1f, 1f),
            ),
            scaleX = 100f,
            scaleY = 100f,
            offsetX = 0f,
            offsetY = 0f,
            sink = sink,
        )

        assertEquals(
            listOf(
                "M 0.0 0.0",
                "Q 50.0 0.0 50.0 50.0",
                "Q 50.0 100.0 75.0 100.0",
                "L 100.0 100.0",
            ),
            sink.calls,
        )
    }

    @Test
    fun `a single sample is a dot rather than a path with no length`() {
        val sink = RecordingSink()
        val tap = stroke(StrokeTool.PEN, 0xFFFFFFFF, points = listOf(StrokePoint(0.5f, 0.5f)))

        StrokeGeometry.buildPath(tap.points, 100f, 100f, 0f, 0f, sink)

        assertTrue(StrokeGeometry.isDot(tap))
        assertFalse(StrokeGeometry.isDot(tap.copy(points = tap.points + StrokePoint(0.6f, 0.5f))))
        // Nothing is emitted: a caller that ignored [isDot] draws no path at all
        // rather than a zero-length one whose cap may or may not be painted.
        assertEquals(emptyList<String>(), sink.calls)
    }

    @Test
    fun `an empty stroke emits nothing at all`() {
        val sink = RecordingSink()

        StrokeGeometry.buildPath(emptyList(), 100f, 100f, 0f, 0f, sink)

        assertEquals(emptyList<String>(), sink.calls)
    }

    // ── Capture ───────────────────────────────────────────────────────────────

    @Test
    fun `a sample that has barely moved is dropped rather than stored`() {
        val last = StrokePoint(0.5f, 0.5f)

        assertFalse(StrokeGeometry.shouldAppend(last, StrokePoint(0.5001f, 0.5f)))
        assertTrue(StrokeGeometry.shouldAppend(last, StrokePoint(0.5f + StrokeGeometry.MIN_POINT_SPACING * 2f, 0.5f)))
        // The first sample of a stroke always lands.
        assertTrue(StrokeGeometry.shouldAppend(null, last))
    }

    @Test
    fun `alpha is a property of the tool, so both renderers dilute a highlighter alike`() {
        assertEquals(1f, StrokeGeometry.alphaFor(StrokeTool.PEN), 0f)
        assertEquals(StrokeGeometry.HIGHLIGHTER_ALPHA, StrokeGeometry.alphaFor(StrokeTool.HIGHLIGHTER), 0f)
        assertTrue(StrokeGeometry.alphaFor(StrokeTool.HIGHLIGHTER) < 1f)
    }

    // ── Bounding what a saver has to carry ────────────────────────────────────

    @Test
    fun `a very long stroke is thinned for saving, keeping both of its ends`() {
        val points = (0..2000).map { StrokePoint(it / 2000f, 0f) }

        val thinned = StrokeGeometry.subsampled(points, max = 100)

        assertTrue(thinned.size <= 100)
        assertEquals(points.first(), thinned.first())
        assertEquals(points.last(), thinned.last())
    }

    @Test
    fun `a stroke already short enough is saved untouched`() {
        val points = (0..9).map { StrokePoint(it / 10f, 0f) }

        assertEquals(points, StrokeGeometry.subsampled(points, max = 100))
    }

    private fun stroke(
        tool: StrokeTool,
        color: Long,
        points: List<StrokePoint> = listOf(StrokePoint(0f, 0f), StrokePoint(1f, 1f)),
    ) = Stroke(tool = tool, colorArgb = color, width = StrokeGeometry.DEFAULT_WIDTH, points = points)

    /** Records what a path builder was told to do, in the order it was told. */
    private class RecordingSink : PathSink {
        val calls = mutableListOf<String>()
        override fun moveTo(x: Float, y: Float) { calls += "M $x $y" }
        override fun lineTo(x: Float, y: Float) { calls += "L $x $y" }
        override fun quadTo(controlX: Float, controlY: Float, x: Float, y: Float) {
            calls += "Q $controlX $controlY $x $y"
        }
    }
}
