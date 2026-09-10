package com.firestream.chat.domain.util

import kotlin.math.abs
import kotlin.math.roundToInt

/** A drawing's two layers, in paint order — see [StrokeGeometry.layers]. */
data class StrokeLayers(val blur: List<Stroke>, val painted: List<Stroke>)

/**
 * Somewhere to emit a stroke's path, whatever kind of path the caller is
 * building.
 *
 * The draw screen has two renderers and no way to share a path object between
 * them: the live preview builds an `androidx.compose.ui.graphics.Path` and the
 * flatten builds an `android.graphics.Path`. What they *can* share — and what
 * [StrokeGeometry.buildPath] exists to make them share — is the decision about
 * where the curve goes. Each supplies a sink over its own path type and gets the
 * same sequence of instructions.
 */
interface PathSink {
    fun moveTo(x: Float, y: Float)
    fun lineTo(x: Float, y: Float)
    fun quadTo(controlX: Float, controlY: Float, x: Float, y: Float)
}

/**
 * The draw screen's arithmetic: how wide a stroke is, where its curve runs, and
 * how coarse the mosaic a blur stroke reveals.
 *
 * ### Why this is a separate, Android-free file
 *
 * Everything a [RasterOp.Strokes] means is computed **twice** — once by Compose
 * to show the user what they are drawing, once by `android.graphics` to write
 * the file — and the two must not be able to disagree. On a pen stroke a
 * disagreement is cosmetic; on a blur stroke it is a redaction that covered the
 * face on screen and missed it in the JPEG, which is a privacy failure. So the
 * numbers live here, in one place, as pure functions a JVM test can pin down —
 * the same seam and the same reason as `ImageFitMapper` and `CropGeometry`
 * (`.claude/plans/image-editor.md` §2.3, §3 Phase 3 departure 1).
 *
 * It sits in `domain/` rather than beside either renderer because both need it
 * and `ArchitectureTest` forbids `data → ui`: a helper in `ui/chat/imageedit/`
 * would be unreachable from `ImageEditRasterizer`, and one in `data/util/` would
 * cost the editor a second `UI_ALLOWED_DATA_IMPORTS` entry. Pure floats belong
 * in neither layer.
 */
object StrokeGeometry {

    /**
     * Thinnest and thickest a stroke may be, as a fraction of the image's long
     * edge — the two ends of the width slider.
     *
     * A fraction rather than a dp value because a stroke is stored against the
     * *image*, not against the screen it was drawn on: 0.006 is a fine line and
     * 0.09 is a brush wide enough to redact a face in a couple of passes,
     * whether the photo ends up 720 px or 4096 px on its long edge.
     */
    const val MIN_WIDTH = 0.006f
    const val MAX_WIDTH = 0.09f

    /** Where the slider starts: a pen line that reads clearly without shouting. */
    const val DEFAULT_WIDTH = 0.02f

    /**
     * How translucent a highlighter is. Multiplied into the photo rather than
     * painted over it, so the detail underneath stays legible — which is the
     * whole difference between a highlighter and a pen.
     */
    const val HIGHLIGHTER_ALPHA = 0.38f

    /**
     * How many mosaic blocks span the image's long edge.
     *
     * **A fixed block count, not a fixed scale factor**, and the difference is
     * the point. The plan sketched blur as "a ×1/16 downscale"; taken literally
     * that makes a block 16 px of whatever bitmap it is computed from, so the
     * editor's ~1200 px preview would show blocks 1.3% of the frame wide while
     * the 4096 px file got blocks 0.4% wide — a preview that promises a
     * redaction stronger than the one written, which is exactly the failure
     * mode the plan calls a privacy failure rather than a cosmetic one. Pinning
     * the count instead makes the mosaic the same fraction of the photo
     * everywhere, and 48 blocks across is coarse enough that a face inside a
     * stroke is gone rather than merely softened.
     */
    const val PIXELATE_BLOCKS = 48

    /**
     * How far the finger must travel before another sample is stored, in
     * fractions of the image.
     *
     * Every sample is a point the saver has to carry through a `Bundle`, so a
     * capture that recorded every pointer event would trade a smoother curve
     * nobody can see for a rotation that can fail. At 0.003 the samples are
     * ~4 px apart on a phone-sized preview, which the quadratic smoothing in
     * [buildPath] turns into a curve with no visible corners.
     */
    const val MIN_POINT_SPACING = 0.003f

    /**
     * The most samples one stroke keeps when it is saved.
     *
     * `rememberSaveable` writes into the process's `Bundle`, which is bounded,
     * and a drawing is the one piece of editor state whose size the *user*
     * controls — a minute of scribbling is thousands of points. Saving is
     * therefore allowed to thin a stroke ([subsampled]) while drawing is not:
     * losing a little fidelity on a rotation is a fair price for a rotation
     * that cannot throw the drawing away.
     */
    const val MAX_SAVED_POINTS = 600

    /** Stroke width in the pixels of a space whose long edge is [longEdgePx]. */
    fun widthPx(width: Float, longEdgePx: Float): Float =
        (width.coerceIn(MIN_WIDTH, MAX_WIDTH) * longEdgePx).coerceAtLeast(1f)

    /** The opacity [tool] paints at; see [HIGHLIGHTER_ALPHA]. */
    fun alphaFor(tool: StrokeTool): Float =
        if (tool == StrokeTool.HIGHLIGHTER) HIGHLIGHTER_ALPHA else 1f

    /**
     * Dimensions of the downscaled copy a blur stroke reveals — [PIXELATE_BLOCKS]
     * across the long edge, the short edge in proportion, never below one pixel.
     *
     * Callers upscale this back over the image with **nearest-neighbour**
     * filtering, which is what turns a small bitmap into visible blocks rather
     * than a smooth blur. Keeping the copy small is not only cheap: it means the
     * preview and the flatten scale the *same* few thousand pixels up to their
     * own sizes, so the block edges land at the same fractions of the photo in
     * both.
     *
     * An image already coarser than the mosaic is returned unchanged — upscaling
     * it to 48 blocks would invent detail and then redact the invention.
     */
    fun pixelatedSize(imageWidth: Int, imageHeight: Int): Pair<Int, Int> {
        if (imageWidth <= 0 || imageHeight <= 0) return 1 to 1
        val longEdge = maxOf(imageWidth, imageHeight)
        if (longEdge <= PIXELATE_BLOCKS) return imageWidth to imageHeight
        val scale = PIXELATE_BLOCKS.toFloat() / longEdge
        return (imageWidth * scale).roundToInt().coerceAtLeast(1) to
            (imageHeight * scale).roundToInt().coerceAtLeast(1)
    }

    /** True when anything in [strokes] needs the mosaic computed at all. */
    fun hasBlur(strokes: List<Stroke>): Boolean = strokes.any { it.tool == StrokeTool.BLUR }

    /**
     * [strokes] split into the two layers they are painted in: **every blur
     * first**, then everything drawn, each keeping the order it was captured in.
     *
     * Two lists rather than one ordered one because the layers are painted
     * differently and not merely in sequence — the blur layer is a single masked
     * group revealing one mosaic, the painted layer is stroke by stroke — so
     * both renderers need the split, not just the order. See [RasterOp.Strokes]
     * for why blur goes underneath.
     *
     * Undo and redo are unaffected: they still walk the capture order, because
     * the unit a user expects back is the last thing they did, not the last
     * thing painted.
     */
    fun layers(strokes: List<Stroke>): StrokeLayers {
        val (blur, painted) = strokes.partition { it.tool == StrokeTool.BLUR }
        return StrokeLayers(blur = blur, painted = painted)
    }

    /** True when a stroke is a tap: a dot to be drawn, not a path to be stroked. */
    fun isDot(stroke: Stroke): Boolean = stroke.points.size == 1

    /** Whether [next] is far enough from [last] to be worth storing. */
    fun shouldAppend(last: StrokePoint?, next: StrokePoint): Boolean {
        if (last == null) return true
        return abs(next.x - last.x) >= MIN_POINT_SPACING || abs(next.y - last.y) >= MIN_POINT_SPACING
    }

    /**
     * Emits [points] into [sink] as a smoothed path, scaled by [scaleX] /
     * [scaleY] and shifted by [offsetX] / [offsetY].
     *
     * Quadratics with the captured samples as control points and the midpoints
     * between them as ends — the standard way to round the corners a sampled
     * finger leaves, chosen here mostly because it is trivially identical in
     * both renderers and depends on nothing but the points.
     *
     * Nothing is emitted for fewer than two points: a single sample is a dot
     * ([isDot]) and is drawn as a circle, because a zero-length path may or may
     * not paint its cap depending on the renderer — which is precisely the kind
     * of divergence this file exists to remove.
     */
    fun buildPath(
        points: List<StrokePoint>,
        scaleX: Float,
        scaleY: Float,
        offsetX: Float,
        offsetY: Float,
        sink: PathSink,
    ) {
        if (points.size < 2) return
        fun x(point: StrokePoint) = offsetX + point.x * scaleX
        fun y(point: StrokePoint) = offsetY + point.y * scaleY

        sink.moveTo(x(points[0]), y(points[0]))
        for (index in 1 until points.lastIndex) {
            val control = points[index]
            val next = points[index + 1]
            sink.quadTo(
                controlX = x(control),
                controlY = y(control),
                x = (x(control) + x(next)) / 2f,
                y = (y(control) + y(next)) / 2f,
            )
        }
        sink.lineTo(x(points.last()), y(points.last()))
    }

    /**
     * At most [max] evenly-spaced samples of [points], always including both
     * ends — how a stroke is thinned for saving (see [MAX_SAVED_POINTS]).
     *
     * Evenly spaced rather than by curvature: a smarter simplification would
     * keep more of the shape for the same budget, but this runs on a saver in
     * the middle of a configuration change and its only job is to be bounded
     * and to keep the stroke where it was drawn.
     */
    fun subsampled(points: List<StrokePoint>, max: Int = MAX_SAVED_POINTS): List<StrokePoint> {
        if (max < 2 || points.size <= max) return points
        val step = (points.size - 1).toFloat() / (max - 1)
        return (0 until max).map { index ->
            points[(index * step).roundToInt().coerceIn(0, points.lastIndex)]
        }
    }
}
