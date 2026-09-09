package com.firestream.chat.domain.util

import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin

/**
 * One flattening step of the image editor, in image space and plain numbers.
 *
 * Ops apply in list order, each to the result of the last, so a crop expressed
 * in fractions of the image means fractions of the image *as the previous op
 * left it* — which is what an editor screen naturally produces, because that
 * intermediate is what the user is looking at.
 *
 * Lives in `domain/` rather than beside the rasterizer that executes it, and
 * that placement is load-bearing: an op is pure arithmetic over floats with no
 * Android type anywhere in it, so both the editor screens that *build* ops and
 * the data-layer rasterizer that *applies* them can depend on it without the UI
 * reaching into `data/` (`ArchitectureTest`, and
 * `.claude/plans/image-editor.md` §2.2). Keeping it here is what lets the
 * editor screens import their geometry freely while `ImageEditRasterizer`
 * stays the single allowlisted platform adapter.
 */
sealed interface RasterOp {
    /** Quarter-turn clockwise; [degrees] is normalised to 0 / 90 / 180 / 270. */
    data class Rotate(val degrees: Int) : RasterOp

    /** Mirror across the vertical axis when [horizontal], else the horizontal one. */
    data class Flip(val horizontal: Boolean) : RasterOp

    /**
     * Level a tilted horizon by rotating [degrees] clockwise, then **crop back
     * to the largest centred rectangle of the same aspect ratio that the
     * rotation still covers** — so the output is a full photo, never one with
     * black triangles in its corners.
     *
     * The auto-crop is not a separate step the user can forget. It was chosen
     * over expanding the bounds and offering an explicit "auto crop" button
     * (`.claude/plans/image-editor.md` §3, decided 2026-09-09) precisely
     * because that alternative has a failure mode this one cannot have:
     * straighten, miss the button, press Done, and send a photo with black
     * corners. What it costs is pixels — [straightenScale] says how many — and
     * that cost is what the editor shows live as the image scales up under the
     * slider.
     *
     * [degrees] is clamped to ±[ImageEditGeometry.STRAIGHTEN_LIMIT]; past that
     * the inscribed rectangle has thrown away more of the photo than a
     * straighten is worth, and a quarter-turn [Rotate] is the honest tool.
     */
    data class Straighten(val degrees: Float) : RasterOp

    /**
     * Keep the sub-rectangle bounded by these fractions of the current image,
     * `0..1` from the top-left. Values are clamped and the result is never
     * narrower or shorter than one pixel, so a degenerate drag yields a tiny
     * image rather than a crash.
     */
    data class Crop(
        val left: Float,
        val top: Float,
        val right: Float,
        val bottom: Float,
    ) : RasterOp

    /**
     * Scale so the long edge is [longEdge] pixels. **Downscale only** — the
     * resize presets exist to make an image smaller, and upscaling a JPEG would
     * add bytes and no detail, so a [longEdge] above the current one is a no-op.
     */
    data class Resize(val longEdge: Int) : RasterOp
}

/** Where a [RasterOp.Crop] lands, in whole pixels of the image it applies to. */
data class PixelRect(val x: Int, val y: Int, val width: Int, val height: Int)

/**
 * What an image's header says about it: its true pixel dimensions and the size
 * of the file behind it, with nothing decoded.
 *
 * The editor needs all three before it can label anything — a resize preset's
 * `W × H` is arithmetic over the source dimensions, and its approximate file
 * size needs the source's own bytes-per-pixel to be anything better than a
 * guess. [bytes] is `0` when the provider withholds a size, which
 * [ImageEditGeometry.estimatedBytes] treats as "assume a typical photo" rather
 * than as "zero bytes".
 */
data class SourceImage(val width: Int, val height: Int, val bytes: Long)

/**
 * Output pixels and approximate encoded bytes for one send — what the HD sheet
 * labels its two rows with.
 *
 * "Approximate" is the contract, and the sheet says so: measuring exactly means
 * a second full decode-and-encode per image, which is what
 * `MediaProcessingLimiter` exists to prevent (§2.5).
 */
data class SizeEstimate(val width: Int, val height: Int, val bytes: Long)

/**
 * The arithmetic behind the image editor: what an op list does to an image's
 * dimensions, without decoding anything.
 *
 * Split from the bitmap work on purpose. These are the numbers an editor screen
 * has to agree with — a crop handle is drawn against them and a resize preset is
 * labelled with them — so they are worth having as pure functions that a JVM
 * test can check and that the UI can call without touching the data layer.
 */
object ImageEditGeometry {

    /**
     * Long-edge ceiling for an edit pass (§2.1). Rasterizing at true source
     * resolution OOMs on a 108 MP original; 4096 px is past what any phone
     * screen or messaging recipient resolves and still leaves headroom for a
     * rotation, which holds source and destination at once.
     */
    const val WORKING_MAX_DIMENSION = 4096

    /**
     * How far [RasterOp.Straighten] may tilt, in either direction.
     *
     * Past 45° the inscribed rectangle keeps less than half of a square photo,
     * so the tool would be quietly throwing away more than it fixes; a
     * quarter-turn [RasterOp.Rotate] is what a bigger correction actually
     * wants. It is also the range a slider can resolve by hand — ±45° across
     * ~330 dp is about a quarter of a degree per pixel.
     */
    const val STRAIGHTEN_LIMIT = 45f

    /**
     * Bytes per pixel assumed when the source's own file size is unknown —
     * roughly a q85 photo, which is what a camera or gallery pick usually is.
     */
    private const val DEFAULT_BYTES_PER_PIXEL = 0.22f

    /**
     * The source's own bytes-per-pixel is the best available signal, but only
     * inside the range a JPEG photo actually occupies. A PNG screenshot or a
     * near-lossless export sits far above it and would inflate both rows; a
     * heavily-recompressed thumbnail sits below and would flatter them.
     */
    private const val MIN_BYTES_PER_PIXEL = 0.05f
    private const val MAX_BYTES_PER_PIXEL = 0.60f

    /** q80 against a typical source encode — the standard row's discount. */
    private const val STANDARD_QUALITY_FACTOR = 0.85f

    /** q100 against the same source — re-encoding at maximum quality inflates. */
    private const val HD_QUALITY_FACTOR = 1.6f

    /** The decode target for a source of [width] × [height], capped on the long edge. */
    fun cappedSize(
        width: Int,
        height: Int,
        ceiling: Int = WORKING_MAX_DIMENSION,
    ): Pair<Int, Int> {
        if (width <= 0 || height <= 0) return width to height
        val longEdge = maxOf(width, height)
        if (longEdge <= ceiling) return width to height
        val scale = ceiling.toFloat() / longEdge
        return (width * scale).roundToInt().coerceAtLeast(1) to
            (height * scale).roundToInt().coerceAtLeast(1)
    }

    /** 0 / 90 / 180 / 270, for any multiple of 90 in either direction. */
    fun normalizeQuarterTurn(degrees: Int): Int = ((degrees % 360) + 360) % 360

    /** Resolves a normalized [RasterOp.Crop] against real pixel dimensions. */
    fun cropRect(width: Int, height: Int, op: RasterOp.Crop): PixelRect {
        val left = (minOf(op.left, op.right) * width).roundToInt().coerceIn(0, width - 1)
        val top = (minOf(op.top, op.bottom) * height).roundToInt().coerceIn(0, height - 1)
        val right = (maxOf(op.left, op.right) * width).roundToInt().coerceIn(left + 1, width)
        val bottom = (maxOf(op.top, op.bottom) * height).roundToInt().coerceIn(top + 1, height)
        return PixelRect(left, top, right - left, bottom - top)
    }

    /** Dimensions after a [RasterOp.Resize]; never upscales. */
    fun resizedSize(width: Int, height: Int, longEdge: Int): Pair<Int, Int> {
        val current = maxOf(width, height)
        if (longEdge <= 0 || longEdge >= current) return width to height
        val scale = longEdge.toFloat() / current
        return (width * scale).roundToInt().coerceAtLeast(1) to
            (height * scale).roundToInt().coerceAtLeast(1)
    }

    /**
     * How much of the image a [RasterOp.Straighten] of [degrees] keeps, as a
     * factor on each edge — `1` for no tilt, about `0.71` for a square at the
     * ±45° limit.
     *
     * The straighten crops back to the largest **centred rectangle of the same
     * aspect ratio** that the rotated photo still covers, so there is one
     * unknown: the common scale `s` applied to both edges. Rotating that
     * candidate back into the photo's own frame gives it a bounding box of
     * `s·(W·cos + H·sin)` by `s·(W·sin + H·cos)`, and a centred convex shape
     * fits inside a centred axis-aligned rectangle exactly when its bounding
     * box does. Each edge therefore caps `s`, and the smaller cap wins.
     *
     * Depends only on the aspect ratio, not on the pixel count — which is what
     * lets the editor preview a straighten by scaling up a screen-sized bitmap
     * and still promise the same framing at full resolution.
     */
    fun straightenScale(width: Int, height: Int, degrees: Float): Float {
        if (width <= 0 || height <= 0) return 1f
        val clamped = degrees.coerceIn(-STRAIGHTEN_LIMIT, STRAIGHTEN_LIMIT)
        val radians = Math.toRadians(clamped.toDouble())
        val cosine = abs(cos(radians))
        val sine = abs(sin(radians))
        if (sine == 0.0) return 1f
        val w = width.toDouble()
        val h = height.toDouble()
        val scale = minOf(w / (w * cosine + h * sine), h / (w * sine + h * cosine))
        return scale.toFloat().coerceIn(0f, 1f)
    }

    /** Dimensions after a [RasterOp.Straighten]; the aspect ratio is preserved. */
    fun straightenSize(width: Int, height: Int, degrees: Float): Pair<Int, Int> {
        val scale = straightenScale(width, height, degrees)
        if (scale >= 1f) return width to height
        return (width * scale).roundToInt().coerceAtLeast(1) to
            (height * scale).roundToInt().coerceAtLeast(1)
    }

    /**
     * Dimensions [ops] would produce from a source of [width] × [height],
     * without decoding anything — the arithmetic half of the rasterize, so an
     * editor screen can label a preset with its result before the user commits
     * to it.
     */
    fun outputSize(width: Int, height: Int, ops: List<RasterOp>): Pair<Int, Int> {
        var (currentWidth, currentHeight) = cappedSize(width, height)
        for (op in ops) {
            when (op) {
                is RasterOp.Rotate ->
                    if (normalizeQuarterTurn(op.degrees) % 180 == 90) {
                        val swap = currentWidth
                        currentWidth = currentHeight
                        currentHeight = swap
                    }

                is RasterOp.Flip -> Unit

                is RasterOp.Straighten -> {
                    val (straightenedWidth, straightenedHeight) =
                        straightenSize(currentWidth, currentHeight, op.degrees)
                    currentWidth = straightenedWidth
                    currentHeight = straightenedHeight
                }

                is RasterOp.Crop -> {
                    val rect = cropRect(currentWidth, currentHeight, op)
                    currentWidth = rect.width
                    currentHeight = rect.height
                }

                is RasterOp.Resize -> {
                    val (resizedWidth, resizedHeight) =
                        resizedSize(currentWidth, currentHeight, op.longEdge)
                    currentWidth = resizedWidth
                    currentHeight = resizedHeight
                }
            }
        }
        return currentWidth to currentHeight
    }

    /**
     * Output dimensions for a send at [hd] or standard quality.
     *
     * [standardMaxDimension] is the compressor's own long-edge cap, passed in
     * rather than referenced: the number belongs to the data layer, and domain
     * cannot import it. The rasterizer supplies `ImageCompressor.MAX_DIMENSION`,
     * which is what keeps this estimate honest about what will actually be sent.
     */
    fun estimatedDimensions(
        width: Int,
        height: Int,
        hd: Boolean,
        standardMaxDimension: Int,
    ): Pair<Int, Int> {
        if (width <= 0 || height <= 0) return 0 to 0
        if (hd) return width to height
        return resizedSize(width, height, standardMaxDimension)
    }

    /**
     * Approximate encoded size for a source of [width] × [height] whose file is
     * [sourceBytes] on disk (0 when the provider withholds it).
     */
    fun estimatedSize(
        width: Int,
        height: Int,
        sourceBytes: Long,
        hd: Boolean,
        standardMaxDimension: Int,
    ): SizeEstimate {
        val (outputWidth, outputHeight) = estimatedDimensions(width, height, hd, standardMaxDimension)
        return SizeEstimate(
            width = outputWidth,
            height = outputHeight,
            bytes = estimatedBytes(
                outputWidth = outputWidth,
                outputHeight = outputHeight,
                source = SourceImage(width, height, sourceBytes),
                hd = hd,
            ),
        )
    }

    /**
     * Approximate encoded bytes for an output of [outputWidth] × [outputHeight]
     * derived from [source] — the number a resize preset is labelled with, and
     * the arithmetic half of [estimatedSize].
     *
     * Taken separately because the two callers know different things: the HD
     * sheet knows only a source URI and asks what sending it costs, while the
     * adjust screen already knows the exact output dimensions its own op stack
     * produces and only needs them priced. Both price them the same way, which
     * is the point of sharing this: a resize preset labelled `~340 KB` and an
     * HD row labelled `about 340 KB` must not disagree about the same photo.
     *
     * `0` when there is nothing to measure against, which the callers render as
     * no size line at all rather than as a confident zero.
     */
    fun estimatedBytes(
        outputWidth: Int,
        outputHeight: Int,
        source: SourceImage,
        hd: Boolean,
    ): Long {
        val sourcePixels = source.width.toLong() * source.height.toLong()
        val pixels = outputWidth.toLong() * outputHeight.toLong()
        if (sourcePixels <= 0 || pixels <= 0) return 0
        val bytesPerPixel = if (source.bytes > 0) {
            (source.bytes.toFloat() / sourcePixels).coerceIn(MIN_BYTES_PER_PIXEL, MAX_BYTES_PER_PIXEL)
        } else {
            DEFAULT_BYTES_PER_PIXEL
        }
        val factor = if (hd) HD_QUALITY_FACTOR else STANDARD_QUALITY_FACTOR
        return (pixels * bytesPerPixel * factor).toLong()
    }
}
