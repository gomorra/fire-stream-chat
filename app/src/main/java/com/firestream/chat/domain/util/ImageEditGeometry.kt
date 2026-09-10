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

    /**
     * Paint [strokes] into the image: pen and highlighter marks, and blur
     * strokes that reveal a pixelated copy of the photo underneath them.
     *
     * ### What comes out
     *
     * The image keeps its dimensions — a drawing changes pixels, never shape —
     * and everything about *where* a stroke lands is stored as a fraction of
     * the image, so one captured stroke means the same thing on the editor's
     * screen-sized preview and in the full-resolution file. [StrokeGeometry]
     * owns that arithmetic and is deliberately the only copy of it: the
     * preview is drawn by Compose and the file by `android.graphics`, and two
     * implementations of "how wide is this stroke" would be two chances for
     * the preview to promise a redaction the file does not deliver.
     *
     * ### Blur goes under, not over
     *
     * Blur strokes are rendered **first**, whatever order they were drawn in
     * ([StrokeGeometry.layers]), because the mosaic they reveal is a copy
     * of the *photo* and knows nothing about the marks over it. Rendering in
     * capture order would let a blur drawn afterwards swallow the arrow that
     * pointed at the thing being blurred. Blur redacts; pen and highlighter
     * annotate; the annotation is on top.
     *
     * ### Blur is pixelation, and irreversibly so
     *
     * A downscale-then-nearest-upscale copy, not a gaussian
     * (`.claude/plans/image-editor.md` §3, Phase 4): it is cheaper, it reads
     * unambiguously as *redacted*, and — the part that matters when someone is
     * hiding a face or a bank card — the detail is genuinely gone from the
     * output rather than merely smeared. The tool is still labelled "Blur".
     */
    data class Strokes(val strokes: List<Stroke>) : RasterOp

    /**
     * Place [overlays] on the image: emoji, stickers, text runs and shapes,
     * each at its own position, size and angle.
     *
     * ### What comes out
     *
     * The image keeps its dimensions, exactly as [Strokes] does — placing an
     * object repaints pixels, it never changes how many there are. Everything
     * about *where* an object sits and how big it is is stored as a fraction of
     * the image ([ImageOverlay]), so one placement means the same thing on the
     * editor's screen-sized preview and in the full-resolution file, and
     * [OverlayGeometry] is deliberately the only copy of that arithmetic.
     *
     * ### Painted in list order, which is the z-order
     *
     * Unlike [Strokes], there is no layer split here: the last thing you placed
     * is the thing on top, which is the only ordering anyone would predict from
     * dragging objects around. Blur needed the split because a mosaic is a copy
     * of the *photo* and would have swallowed the arrow pointing at it; an
     * emoji has no such relationship with what it covers.
     *
     * ### Overlays are not a redaction
     *
     * A sticker over a face hides it in the flattened JPEG as thoroughly as a
     * blur does — the pixels underneath are gone from the output, since this is
     * the same rasterize-per-screen flatten. That is a consequence, not a
     * promise: the tool for redacting is the draw screen's blur, which says so.
     */
    data class Overlays(val overlays: List<ImageOverlay>) : RasterOp
}

/**
 * One object placed on the image, in the image's own coordinates.
 *
 * [centerX] and [centerY] are fractions of the image, and [scale] multiplies a
 * base size that is itself a fraction of the image's long edge
 * ([OverlayGeometry.BASE_SIZE]) — so nothing here is a pixel count, and a
 * placement made on a 1600 px preview lands identically in a 4096 px flatten.
 * The same reason [StrokePoint] is normalized (`.claude/plans/image-editor.md`
 * §2.3), and the same reason both survive a device rotation.
 *
 * [rotationDegrees] is clockwise, `0` upright, and is what the rotate handle
 * writes — snapped to [OverlayGeometry.ROTATION_SNAP_DEGREES] as it goes.
 */
data class ImageOverlay(
    val content: OverlayContent,
    val centerX: Float,
    val centerY: Float,
    val scale: Float = 1f,
    val rotationDegrees: Float = 0f,
)

/**
 * What a placed overlay actually is.
 *
 * Four kinds, one manipulation. Drag, scale, rotate, z-order and delete are the
 * same machinery whichever of these is selected, which is why the editor has
 * one overlay screen rather than four (`.claude/plans/image-editor.md` §3
 * Phase 5) — the kinds differ only in how they are *painted*.
 */
sealed interface OverlayContent {
    /** An emoji, painted as text: at this size a glyph is a glyph. */
    data class Emoji(val emoji: String) : OverlayContent

    /**
     * One sticker from the bundled pack, by its [StickerPack] id.
     *
     * An id rather than a bitmap or a resource: a sticker is a list of flat
     * coloured parts in normalized space ([StickerPart]), so both renderers
     * build it themselves from the same description and there is no asset to
     * decode, scale or disagree about.
     */
    data class Sticker(val stickerId: String) : OverlayContent

    /**
     * A typed run, painted at [OverlayGeometry.BASE_SIZE] of the long edge and
     * centred on the placement.
     *
     * [filled] paints solid glyphs; `false` strokes their outlines, which is
     * the same fill-or-outline choice a [Shape] offers and reads as the same
     * control in the picker. Multi-line text is not offered — the run is one
     * line, because a text box that wraps needs a width the single scale handle
     * cannot express.
     */
    data class Text(val text: String, val colorArgb: Long, val filled: Boolean) : OverlayContent

    /**
     * An annotation primitive — the other half of the blur tool's job.
     * "Put a box round this" is what makes a redaction legible, and both serve
     * the same redact-before-sending purpose.
     *
     * [filled] is ignored for [ShapeKind.LINE] and [ShapeKind.ARROW], which
     * have no interior to fill.
     */
    data class Shape(val kind: ShapeKind, val colorArgb: Long, val filled: Boolean) : OverlayContent
}

/** The shapes the shape tab offers, each with its own default proportions. */
enum class ShapeKind {
    RECTANGLE,
    ROUNDED_RECTANGLE,
    ELLIPSE,
    LINE,
    ARROW,
}

/** Which of the draw screen's three tools laid a stroke down. */
enum class StrokeTool {
    /** An opaque mark in the chosen colour. */
    PEN,

    /** A translucent mark that multiplies into the photo, like a marker pen. */
    HIGHLIGHTER,

    /** Not a colour at all: a mask revealing the pixelated copy (see [RasterOp.Strokes]). */
    BLUR,
}

/**
 * One sample along a stroke, as a fraction of the image it was drawn on.
 *
 * Normalized for the reason all overlay geometry is
 * (`.claude/plans/image-editor.md` §2.3): the point means "40% across this
 * photo", not "212 px into the canvas I happened to be laid out in", so a
 * stroke survives a device rotation, a screen-size change and the jump from the
 * editor's preview-sized bitmap to the full-resolution flatten without drifting.
 */
data class StrokePoint(val x: Float, val y: Float)

/**
 * One continuous mark: everything the two renderers need to draw it identically.
 *
 * [width] is a fraction of the image's **long edge**, not a pixel count, so it
 * scales with the output exactly as [points] do — a stroke that covered a face
 * on screen covers it in the file. [colorArgb] is a plain `Long` because
 * [RasterOp] carries no Compose and no Android types; it is ignored entirely
 * for [StrokeTool.BLUR], which has no colour to choose.
 */
data class Stroke(
    val tool: StrokeTool,
    val colorArgb: Long,
    val width: Float,
    val points: List<StrokePoint>,
)

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

                // A drawing and a placed overlay both repaint pixels; neither
                // changes how many there are.
                is RasterOp.Strokes -> Unit

                is RasterOp.Overlays -> Unit
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
