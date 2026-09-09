package com.firestream.chat.data.util

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ImageDecoder
import android.graphics.Matrix
import android.net.Uri
import android.provider.OpenableColumns
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.roundToInt

/**
 * Every full-resolution bitmap operation the image editor performs, and the
 * lifecycle of the files it writes.
 *
 * ### Why one class owns all of it
 *
 * Edits **rasterize per editor screen** (`.claude/plans/image-editor.md` §2.1):
 * pressing Done flattens that screen's layer into a new JPEG in
 * `cacheDir/edits/` and hands the URI back, so the chain of files *is* the undo
 * history and the send pipeline never learns that editing exists. That makes
 * this the single place holding a full-size bitmap, which is why it is also the
 * single place taking a [MediaProcessingLimiter] permit — the process-wide cap
 * of two decoded bitmaps is what stands between a twenty-image batch and an OOM
 * (docs/PATTERNS.md#mediaprocessinglimiter-owns-the-concurrency-bound-callers-own-ordering).
 *
 * ### One name crosses the layer boundary
 *
 * [RasterOp] and [SizeEstimate] are nested rather than top-level on purpose.
 * `ArchitectureTest` forbids `data → ui` imports, so the editor screens convert
 * their Compose-flavoured state into ops at the boundary and the ops carry plain
 * numbers, never Compose types. Nesting means the whole editor surface reaches
 * the UI through the *one* `UI_ALLOWED_DATA_IMPORTS` entry §2.2 budgets for it —
 * a screen writes `ImageEditRasterizer.RasterOp.Crop(…)` under a single import
 * instead of spending a fresh allowlist entry per type.
 *
 * ### What it deliberately does not do
 *
 * - **It never decodes at true source resolution.** A 108 MP camera original
 *   would OOM, so an edit pass works at [WORKING_MAX_DIMENSION] on the long
 *   edge. An HD send of an *edited* photo is therefore capped at 4096 px while
 *   an HD send of an untouched photo stays at full resolution — a deliberate
 *   trade, recorded in `TECH_DEBT.md` with its revisit trigger.
 * - **It never uses a subsampled `BitmapFactory` to decode.** Large camera
 *   originals come back **black** through a heavily subsampled decode; see
 *   `ScaledImageDecoder`'s KDoc for the pathology. [ImageDecoder.setTargetSize]
 *   does a proper scaled decode and honours EXIF orientation on the way.
 * - **It preserves no EXIF.** Re-encoding through [Bitmap.compress] drops GPS
 *   and camera metadata, so an edited image carries none. That is a privacy
 *   property worth keeping, not a regression to fix.
 *
 * Intermediates are written at [INTERMEDIATE_QUALITY]; only the final send goes
 * through [ImageCompressor]. Three edit passes at q95 followed by one q80 is not
 * visually distinguishable from a single q80 — a real but bounded cost of the
 * rasterize-per-screen model.
 */
@Singleton
class ImageEditRasterizer @Inject constructor(
    @ApplicationContext private val context: Context,
    private val processingLimiter: MediaProcessingLimiter,
) {

    /**
     * One flattening step, in image space and plain numbers.
     *
     * Ops apply in list order, each to the result of the last, so a crop
     * expressed in fractions of the image means fractions of the image *as the
     * previous op left it* — which is what an editor screen naturally produces,
     * because the user is looking at that intermediate.
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
         * resize presets exist to make an image smaller, and upscaling a JPEG
         * would add bytes and no detail, so a [longEdge] above the current one
         * is a no-op.
         */
        data class Resize(val longEdge: Int) : RasterOp
    }

    /**
     * What the HD sheet needs to describe a send: the pixels it would produce
     * and roughly how many bytes that is.
     *
     * "Roughly" is the contract, and the sheet says so. Measuring exactly means
     * a second full decode-and-encode per image, which is the very thing the
     * limiter exists to prevent (§2.5), so this is arithmetic over the source's
     * header and file size against the numbers [ImageCompressor] actually uses.
     */
    data class SizeEstimate(val width: Int, val height: Int, val bytes: Long)

    /** Where a [RasterOp.Crop] lands, in whole pixels of the image it applies to. */
    internal data class PixelRect(val x: Int, val y: Int, val width: Int, val height: Int)

    private val sequence = AtomicLong(0)

    /** `cacheDir/edits/`, created on demand. */
    private val editsDir: File
        get() = File(context.cacheDir, EDITS_DIR).apply { mkdirs() }

    /**
     * Applies [ops] to [source] and returns the URI of the new JPEG.
     *
     * Holds a [MediaProcessingLimiter] permit for the whole decode-transform-
     * encode, because that is exactly the window in which a full-size bitmap is
     * resident. Runs on IO; callers stay responsible for ordering.
     *
     * Writing the result may push `cacheDir/edits/` over [CACHE_BUDGET_BYTES],
     * in which case the oldest files there are evicted — see [enforceBudget].
     * [liveSteps] is **the step every item in the batch is currently sitting
     * on**, and those are exempt from that eviction. It has no default, because
     * a caller that forgets it does not get a compile error but a user who
     * loses the crop they just made on page 3 while editing page 1: eviction is
     * globally oldest-first, and an item nobody has touched for a minute owns
     * some of the oldest files in the directory even though its newest one is
     * the image the pager is showing. Losing undo *depth* is the acceptable
     * cost of the budget; losing the current step is not.
     */
    suspend fun rasterize(source: Uri, ops: List<RasterOp>, liveSteps: Set<Uri>): Uri =
        processingLimiter.withPermit {
            withContext(Dispatchers.IO) {
                var bitmap = decodeCapped(source)
                try {
                    for (op in ops) {
                        val next = applyOp(bitmap, op)
                        if (next !== bitmap) {
                            bitmap.recycle()
                            bitmap = next
                        }
                    }
                    val output = File(editsDir, "edit_${System.currentTimeMillis()}_${sequence.incrementAndGet()}.jpg")
                    output.outputStream().use { out ->
                        bitmap.compress(Bitmap.CompressFormat.JPEG, INTERMEDIATE_QUALITY, out)
                    }
                    enforceBudget(CACHE_BUDGET_BYTES, keep = editFiles(liveSteps) + output)
                    Uri.fromFile(output)
                } finally {
                    bitmap.recycle()
                }
            }
        }

    /**
     * Output dimensions and approximate encoded size for sending [source] with
     * [hd] on or off, or null when the URI cannot be read at all — the sheet
     * then renders its rows without a size line rather than with a confident
     * number nobody should trust.
     *
     * Reads the source's header only (`inJustDecodeBounds`), so it allocates no
     * bitmap and takes no permit. `BitmapFactory` is safe *here* precisely
     * because nothing is decoded: the black-bitmap pathology is a property of a
     * subsampled decode, not of reading a header.
     */
    suspend fun estimateSize(source: Uri, hd: Boolean): SizeEstimate? =
        withContext(Dispatchers.IO) {
            val header = probe(source) ?: return@withContext null
            val (width, height) = estimatedDimensions(header.width, header.height, hd)
            SizeEstimate(width, height, estimatedBytes(header, hd))
        }

    /**
     * Deletes rasterized steps the history no longer reaches — the tail
     * abandoned when a new edit lands on top of an undo, or a whole item's
     * chain when its batch is dismissed.
     *
     * Ignores anything that is not a file inside `cacheDir/edits/`. The caller
     * passes URIs straight out of an item's history, and that list also contains
     * the pick's own `content://` URI in every other code path; deleting the
     * user's gallery original because a list got mixed up is not a mistake worth
     * leaving available.
     */
    fun discard(uris: Collection<Uri>) {
        // Deliberately not the `editsDir` accessor: that one creates the
        // directory, and deleting nothing is no reason to make a folder.
        val dir = runCatching { File(context.cacheDir, EDITS_DIR).canonicalFile }.getOrNull() ?: return
        for (uri in uris) {
            val file = editFile(uri, dir) ?: continue
            runCatching { file.delete() }
        }
    }

    /** True when a history entry's file is still on disk (§4: the OS may reclaim it). */
    fun exists(uri: Uri): Boolean {
        if (uri.scheme != "file") return true
        val path = uri.path ?: return false
        return runCatching { File(path).exists() }.getOrDefault(false)
    }

    /**
     * Drops edit files older than [MAX_AGE_MILLIS]. Called once at app start.
     *
     * By age rather than wholesale: a send that was interrupted mid-upload is
     * flipped to FAILED at startup and keeps its manual retry, and that retry
     * re-reads the URI it was given. Emptying the directory on every launch
     * would turn every such retry into a broken image, so recent steps survive
     * a restart and only genuinely abandoned ones are collected — the same shape
     * as `FireStreamApp.cleanOldSharedMedia`.
     */
    fun sweepStale() {
        val cutoff = System.currentTimeMillis() - MAX_AGE_MILLIS
        editFiles().forEach { file ->
            if (file.lastModified() < cutoff) runCatching { file.delete() }
        }
    }

    /** Everything currently in `cacheDir/edits/`, without creating it. */
    private fun editFiles(): List<File> =
        File(context.cacheDir, EDITS_DIR).listFiles()?.filter { it.isFile }.orEmpty()

    /** [liveSteps] as files, dropping any URI that is not a local path. */
    private fun editFiles(liveSteps: Set<Uri>): Set<File> =
        liveSteps.mapNotNullTo(mutableSetOf()) { uri -> uri.path?.let(::File) }

    /**
     * Trims `cacheDir/edits/` back under [budget], oldest first, never touching
     * anything in [keep].
     *
     * Keeping redo alive is what makes this load-bearing rather than tidy: undo
     * can no longer free the file it steps off, so the cache grows per edit
     * *step*, not per image, and stays grown for the whole preview session.
     * Twenty picks × eight steps of 4096 px JPEG is comfortably past a gigabyte.
     *
     * Oldest-first is the plan's "trim the oldest steps of the least recently
     * touched item" (§3) without per-item bookkeeping in the data layer: each
     * item's steps are written in order and an item the user is working on keeps
     * writing new files, so modification time already ranks the steps the way
     * that rule wants them ranked. What it costs is undo *depth* on an item
     * nobody has touched for a while, which the missing-file fallback in
     * `PendingMedia.onSurvivingStep` absorbs by design.
     *
     * [keep] is what that ranking cannot see: the file just written plus every
     * item's current step. Without them, "oldest globally" and "oldest for this
     * item" diverge the moment a batch has more than one edited image, and the
     * eviction silently throws away an edit the user can still see.
     */
    internal fun enforceBudget(budget: Long, keep: Set<File>) {
        val files = editFiles().sortedBy { it.lastModified() }
        var total = files.sumOf { it.length() }
        for (file in files) {
            if (total <= budget) return
            if (file in keep) continue
            val size = file.length()
            if (runCatching { file.delete() }.getOrDefault(false)) total -= size
        }
    }

    /** Decodes [source] with its long edge capped at [WORKING_MAX_DIMENSION]. */
    private fun decodeCapped(source: Uri): Bitmap {
        val decoderSource = ImageDecoder.createSource(context.contentResolver, source)
        return ImageDecoder.decodeBitmap(decoderSource) { decoder, info, _ ->
            // Software allocation because the ops below read and re-encode these
            // pixels; a hardware bitmap cannot be read back.
            decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
            decoder.isMutableRequired = false
            val (width, height) = cappedSize(info.size.width, info.size.height)
            if (width > 0 && height > 0) decoder.setTargetSize(width, height)
        }
    }

    private fun applyOp(bitmap: Bitmap, op: RasterOp): Bitmap = when (op) {
        is RasterOp.Rotate -> {
            val degrees = normalizeQuarterTurn(op.degrees)
            if (degrees == 0) {
                bitmap
            } else {
                val matrix = Matrix().apply { postRotate(degrees.toFloat()) }
                Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
            }
        }

        is RasterOp.Flip -> {
            val matrix = Matrix().apply {
                if (op.horizontal) postScale(-1f, 1f) else postScale(1f, -1f)
            }
            Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
        }

        is RasterOp.Crop -> {
            val rect = cropRect(bitmap.width, bitmap.height, op)
            if (rect.width == bitmap.width && rect.height == bitmap.height) {
                bitmap
            } else {
                Bitmap.createBitmap(bitmap, rect.x, rect.y, rect.width, rect.height)
            }
        }

        is RasterOp.Resize -> {
            val (width, height) = resizedSize(bitmap.width, bitmap.height, op.longEdge)
            if (width == bitmap.width && height == bitmap.height) {
                bitmap
            } else {
                Bitmap.createScaledBitmap(bitmap, width, height, true)
            }
        }
    }

    private fun probe(source: Uri): Probe? = try {
        val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        context.contentResolver.openInputStream(source)?.use {
            BitmapFactory.decodeStream(it, null, options)
        }
        if (options.outWidth <= 0 || options.outHeight <= 0) {
            null
        } else {
            Probe(options.outWidth, options.outHeight, sourceBytes(source))
        }
    } catch (_: Exception) {
        null
    }

    /** File size for a `content://` or `file://` URI; 0 when the provider withholds it. */
    private fun sourceBytes(uri: Uri): Long = try {
        context.contentResolver.query(uri, arrayOf(OpenableColumns.SIZE), null, null, null)
            ?.use { cursor ->
                if (cursor.moveToFirst() && !cursor.isNull(0)) cursor.getLong(0) else 0L
            }
            ?: context.contentResolver.openAssetFileDescriptor(uri, "r")
                ?.use { it.length.coerceAtLeast(0L) }
            ?: 0L
    } catch (_: Exception) {
        0L
    }

    /** A file inside `cacheDir/edits/`, or null for anything [discard] must not touch. */
    private fun editFile(uri: Uri, dir: File): File? {
        if (uri.scheme != "file") return null
        val path = uri.path ?: return null
        val file = runCatching { File(path).canonicalFile }.getOrNull() ?: return null
        return if (file.parentFile == dir) file else null
    }

    /** Header facts about a source image: enough to estimate, not enough to decode. */
    private data class Probe(val width: Int, val height: Int, val sourceBytes: Long)

    companion object {
        /** Subdirectory of `cacheDir` holding every rasterized step. */
        internal const val EDITS_DIR = "edits"

        /**
         * Long-edge ceiling for an edit pass (§2.1). Rasterizing at true source
         * resolution OOMs on a 108 MP original; 4096 px is past what any phone
         * screen or messaging recipient resolves and still leaves headroom for
         * a rotation, which holds source and destination at once.
         */
        const val WORKING_MAX_DIMENSION = 4096

        /** Quality for intermediate steps; only the send re-encodes at q80/q100. */
        private const val INTERMEDIATE_QUALITY = 95

        /**
         * Ceiling on `cacheDir/edits/`. Eight steps of a 4096 px q95 JPEG is
         * roughly 30 MB per image, so this is about eight fully-edited images
         * held at once — comfortably more than a preview session needs, and two
         * orders of magnitude under the gigabyte an unbounded cache reaches.
         */
        private const val CACHE_BUDGET_BYTES = 256L * 1024 * 1024

        /** How long an edit file survives a process restart; see [sweepStale]. */
        private val MAX_AGE_MILLIS = TimeUnit.HOURS.toMillis(24)

        /**
         * Bytes per pixel assumed when the source's own file size is unknown —
         * roughly a q85 photo, which is what a camera or gallery pick usually is.
         */
        private const val DEFAULT_BYTES_PER_PIXEL = 0.22f

        /**
         * The source's own bytes-per-pixel is the best available signal, but
         * only inside the range a JPEG photo actually occupies. A PNG screenshot
         * or a near-lossless export sits far above it and would inflate both
         * rows; a heavily-recompressed thumbnail sits below and would flatter
         * them.
         */
        private const val MIN_BYTES_PER_PIXEL = 0.05f
        private const val MAX_BYTES_PER_PIXEL = 0.60f

        /** q80 against a typical source encode — the standard row's discount. */
        private const val STANDARD_QUALITY_FACTOR = 0.85f

        /** q100 against the same source — re-encoding at maximum quality inflates. */
        private const val HD_QUALITY_FACTOR = 1.6f

        /** The decode target for a source of [width] × [height], capped on the long edge. */
        internal fun cappedSize(
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
        internal fun normalizeQuarterTurn(degrees: Int): Int = ((degrees % 360) + 360) % 360

        /** Resolves a normalized [RasterOp.Crop] against real pixel dimensions. */
        internal fun cropRect(width: Int, height: Int, op: RasterOp.Crop): PixelRect {
            val left = (minOf(op.left, op.right) * width).roundToInt().coerceIn(0, width - 1)
            val top = (minOf(op.top, op.bottom) * height).roundToInt().coerceIn(0, height - 1)
            val right = (maxOf(op.left, op.right) * width).roundToInt().coerceIn(left + 1, width)
            val bottom = (maxOf(op.top, op.bottom) * height).roundToInt().coerceIn(top + 1, height)
            return PixelRect(left, top, right - left, bottom - top)
        }

        /** Dimensions after a [RasterOp.Resize]; never upscales. */
        internal fun resizedSize(width: Int, height: Int, longEdge: Int): Pair<Int, Int> {
            val current = maxOf(width, height)
            if (longEdge <= 0 || longEdge >= current) return width to height
            val scale = longEdge.toFloat() / current
            return (width * scale).roundToInt().coerceAtLeast(1) to
                (height * scale).roundToInt().coerceAtLeast(1)
        }

        /**
         * Dimensions [ops] would produce from a source of [width] × [height],
         * without decoding anything — the arithmetic half of [rasterize], split
         * out so it can be checked on the JVM and so an editor screen can label
         * a preset with its result before the user commits to it.
         */
        internal fun outputSize(width: Int, height: Int, ops: List<RasterOp>): Pair<Int, Int> {
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

        /** Output dimensions for a send at [hd] or standard quality. */
        internal fun estimatedDimensions(width: Int, height: Int, hd: Boolean): Pair<Int, Int> {
            if (width <= 0 || height <= 0) return 0 to 0
            if (hd) return width to height
            return resizedSize(width, height, ImageCompressor.MAX_DIMENSION)
        }

        private fun estimatedBytes(probe: Probe, hd: Boolean): Long {
            val sourcePixels = probe.width.toLong() * probe.height.toLong()
            if (sourcePixels <= 0) return 0
            val bytesPerPixel = if (probe.sourceBytes > 0) {
                (probe.sourceBytes.toFloat() / sourcePixels)
                    .coerceIn(MIN_BYTES_PER_PIXEL, MAX_BYTES_PER_PIXEL)
            } else {
                DEFAULT_BYTES_PER_PIXEL
            }
            val (width, height) = estimatedDimensions(probe.width, probe.height, hd)
            val pixels = width.toLong() * height.toLong()
            val factor = if (hd) HD_QUALITY_FACTOR else STANDARD_QUALITY_FACTOR
            return (pixels * bytesPerPixel * factor).toLong()
        }
    }
}
