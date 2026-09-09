package com.firestream.chat.domain.util

import kotlin.math.roundToInt

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
        val sourcePixels = width.toLong() * height.toLong()
        val (outputWidth, outputHeight) = estimatedDimensions(width, height, hd, standardMaxDimension)
        if (sourcePixels <= 0) return SizeEstimate(outputWidth, outputHeight, 0)
        val bytesPerPixel = if (sourceBytes > 0) {
            (sourceBytes.toFloat() / sourcePixels).coerceIn(MIN_BYTES_PER_PIXEL, MAX_BYTES_PER_PIXEL)
        } else {
            DEFAULT_BYTES_PER_PIXEL
        }
        val pixels = outputWidth.toLong() * outputHeight.toLong()
        val factor = if (hd) HD_QUALITY_FACTOR else STANDARD_QUALITY_FACTOR
        return SizeEstimate(outputWidth, outputHeight, (pixels * bytesPerPixel * factor).toLong())
    }
}
