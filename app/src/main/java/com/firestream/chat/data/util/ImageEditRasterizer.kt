package com.firestream.chat.data.util

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.ImageDecoder
import android.graphics.BlendMode
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.graphics.Rect
import android.net.Uri
import android.provider.OpenableColumns
import androidx.annotation.VisibleForTesting
import com.firestream.chat.domain.util.ImageEditGeometry
import com.firestream.chat.domain.util.ImageOverlay
import com.firestream.chat.domain.util.OverlayContent
import com.firestream.chat.domain.util.OverlayGeometry
import com.firestream.chat.domain.util.PathSink
import com.firestream.chat.domain.util.PixelRect
import com.firestream.chat.domain.util.RasterOp
import com.firestream.chat.domain.util.ShapeKind
import com.firestream.chat.domain.util.SizeEstimate
import com.firestream.chat.domain.util.SourceImage
import com.firestream.chat.domain.util.StickerDesign
import com.firestream.chat.domain.util.StickerPack
import com.firestream.chat.domain.util.StickerPart
import com.firestream.chat.domain.util.Stroke
import com.firestream.chat.domain.util.StrokeGeometry
import com.firestream.chat.domain.util.StrokeTool
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
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
 * ### What crosses the layer boundary
 *
 * The *arithmetic* — [RasterOp] and everything in [ImageEditGeometry] — lives in
 * `domain/util/`, because it is pure functions over floats with no Android type
 * in it. That is what lets an editor screen build its ops and label its resize
 * presets without importing anything from `data/`. What stays here is only what
 * genuinely needs the platform: decode, encode, the cache lifecycle, the
 * limiter permit and the header probe. So this class is the single
 * `UI_ALLOWED_DATA_IMPORTS` entry the editor spends, and only the hosting
 * ViewModel needs even that (`.claude/plans/image-editor.md` §2.2).
 *
 * ### What it deliberately does not do
 *
 * - **It never decodes at true source resolution.** A 108 MP camera original
 *   would OOM, so an edit pass works at [ImageEditGeometry.WORKING_MAX_DIMENSION] on the long
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

    private val sequence = AtomicLong(0)

    /**
     * Guards [inFlight] and serialises eviction.
     *
     * [MediaProcessingLimiter] allows two operations at once, so two
     * [rasterize] calls can overlap. Without this, each would list the
     * directory, compute its own total and delete independently — over-evicting
     * at best, and at worst deleting the other's freshly written output, which
     * is in nobody's `liveSteps` yet because it has not been returned to a
     * caller to record.
     */
    private val evictionLock = Mutex()

    /**
     * Outputs written but not yet handed back to a caller. Registered before the
     * bytes are written and exempt from eviction until [rasterize] returns, at
     * which point the caller records the step and it becomes a live step
     * instead.
     *
     * Verified by construction rather than by a test: reproducing the race needs
     * two rasterize calls genuinely overlapping inside the write window, which
     * has no deterministic seam to hook. A test that merely runs two calls and
     * hopes they interleave asserts nothing on the runs where they do not. What
     * *is* tested is the property this feeds: [enforceBudget] never deletes a
     * file in `keep`, even when that leaves it over budget.
     */
    private val inFlight = mutableSetOf<File>()

    /**
     * The byte ceiling for `cacheDir/edits/`. A `var` only so a test can shrink
     * it: the real budget is [CACHE_BUDGET_BYTES] and nothing in production
     * writes to this.
     */
    @VisibleForTesting
    internal var cacheBudgetBytes: Long = CACHE_BUDGET_BYTES

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
                val bitmap = decodeAndApply(source, ops, ImageEditGeometry.WORKING_MAX_DIMENSION)
                try {
                    val output = File(editsDir, "edit_${System.currentTimeMillis()}_${sequence.incrementAndGet()}.jpg")
                    // Registered before the bytes exist, so a concurrent
                    // rasterize's eviction can never pick it up mid-write.
                    evictionLock.withLock { inFlight += output }
                    try {
                        output.outputStream().use { out ->
                            bitmap.compress(Bitmap.CompressFormat.JPEG, INTERMEDIATE_QUALITY, out)
                        }
                        evictionLock.withLock {
                            enforceBudget(cacheBudgetBytes, keep = editFiles(liveSteps) + inFlight)
                        }
                    } finally {
                        evictionLock.withLock { inFlight -= output }
                    }
                    Uri.fromFile(output)
                } finally {
                    bitmap.recycle()
                }
            }
        }

    /**
     * [ops] applied to [source] at a long edge of at most [maxDimension], as a
     * bitmap that is never written to disk — what an editor screen displays
     * while the user is still deciding.
     *
     * The same decode and the same [applyOp] the real [rasterize] runs, at
     * screen resolution instead of working resolution. That sharing is the
     * whole point: a preview computed by a second implementation would be a
     * second chance to get a rotation's direction or a crop's origin wrong, and
     * the screen would look right while the file came out wrong. What the user
     * sees here is what Done writes, scaled.
     *
     * Takes a permit like [rasterize] does — a screen-sized bitmap is small,
     * but the *decode* still momentarily holds the source at its capped size.
     * Returns null rather than throwing when the URI cannot be read, so a
     * revoked gallery permission closes the editor instead of crashing it.
     */
    suspend fun preview(source: Uri, ops: List<RasterOp>, maxDimension: Int): Bitmap? =
        processingLimiter.withPermit {
            withContext(Dispatchers.IO) {
                val ceiling = maxDimension.coerceIn(1, ImageEditGeometry.WORKING_MAX_DIMENSION)
                runCatching { decodeAndApply(source, ops, ceiling) }.getOrNull()
            }
        }

    /**
     * [source]'s own pixel dimensions and file size, read from its header —
     * the numbers the adjust screen labels its resize presets from.
     *
     * Header-only, so it allocates no bitmap and takes no permit, exactly as
     * [estimateSize] does. Null when the URI cannot be read at all.
     */
    suspend fun probeSource(source: Uri): SourceImage? =
        withContext(Dispatchers.IO) { probe(source) }

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
            ImageEditGeometry.estimatedSize(
                width = header.width,
                height = header.height,
                sourceBytes = header.bytes,
                hd = hd,
                standardMaxDimension = ImageCompressor.MAX_DIMENSION,
            )
        }

    /**
     * Copies [source] — a photo that has already been sent — into the edit
     * cache and returns the copy's URI, to be the untouched original of a new
     * batch (`.claude/plans/image-editor.md` §2.6: editing a sent photo sends a
     * new one).
     *
     * A copy rather than [source] itself, because `PendingMedia.originalUri` is
     * the one URI revert falls back to and is treated as the pick: the sent
     * photo's own file is also what its message bubble and the gallery read,
     * and nothing in the editor should hold a path it could one day write or
     * [discard].
     *
     * A byte copy, not a decode: nothing here needs pixels, so it takes no
     * [MediaProcessingLimiter] permit. EXIF comes along, but a received photo
     * has been through `ImageCompressor` already and carries none.
     *
     * Written to `cacheDir/edits/sources/` rather than `edits/` itself, because
     * the byte budget evicts `edits/` oldest-first and only spares each item's
     * *current* step. An imported original is the oldest file of its batch by
     * construction and stops being anyone's current step as soon as the first
     * edit lands — so in `edits/` the budget would eventually take the one file
     * revert and the missing-step fallback both end at. One original per batch
     * item is bounded without a budget. [discard] and [sweepStale] still reach
     * it, so it is collected like any step.
     */
    suspend fun importSource(source: File): Uri = withContext(Dispatchers.IO) {
        val dir = File(editsDir, SOURCES_DIR).apply { mkdirs() }
        val extension = source.extension.lowercase().ifBlank { "jpg" }
        val output = File(dir, "source_${System.currentTimeMillis()}_${sequence.incrementAndGet()}.$extension")
        try {
            source.inputStream().use { input -> output.outputStream().use { input.copyTo(it) } }
        } catch (e: Exception) {
            // A half-written original would open as a broken preview.
            output.delete()
            throw e
        }
        Uri.fromFile(output)
    }

    /**
     * Deletes rasterized steps the history no longer reaches — the tail
     * abandoned when a new edit lands on top of an undo, or a whole item's
     * chain when its batch is dismissed — and originals [importSource] copied in.
     *
     * Ignores anything that is not a file inside `cacheDir/edits/` or its
     * `sources/`. The caller passes URIs straight out of an item's history, and
     * that list also contains the pick's own `content://` URI in every other
     * code path; deleting the user's gallery original because a list got mixed
     * up is not a mistake worth leaving available.
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
        (editFiles() + sourceFiles()).forEach { file ->
            if (file.lastModified() < cutoff) runCatching { file.delete() }
        }
    }

    /**
     * Every step currently in `cacheDir/edits/`, without creating it. Files
     * only, so the `sources/` subdirectory — and the originals in it — is
     * outside the budget's reach (see [importSource]).
     */
    private fun editFiles(): List<File> =
        File(context.cacheDir, EDITS_DIR).listFiles()?.filter { it.isFile }.orEmpty()

    /** Every original [importSource] has written, without creating the directory. */
    private fun sourceFiles(): List<File> =
        File(File(context.cacheDir, EDITS_DIR), SOURCES_DIR).listFiles()?.filter { it.isFile }.orEmpty()

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
     * [keep] is what that ranking cannot see: every item's current step, plus
     * any output still in flight. Without them, "oldest globally" and "oldest
     * for this item" diverge the moment a batch has more than one edited image,
     * and the eviction silently throws away an edit the user can still see.
     *
     * When [keep] is large enough that the budget cannot be met, this returns
     * over budget rather than deleting a protected file. That is the right way
     * round — a cache slightly over its ceiling costs disk, a deleted live step
     * costs the user their work — and it cannot run away, because live steps are
     * one per batch item.
     *
     * Callers must hold [evictionLock]; concurrent evictions would each compute
     * a total from their own listing and over-delete.
     */
    @VisibleForTesting
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

    /**
     * Decodes [source] at [ceiling] and folds [ops] into it, recycling each
     * intermediate as it goes so only one full bitmap is ever resident.
     *
     * The single interpretation of an op list, shared by [rasterize] and
     * [preview] — see [preview] for why that sharing is not merely tidy.
     */
    private fun decodeAndApply(source: Uri, ops: List<RasterOp>, ceiling: Int): Bitmap {
        var bitmap = decodeCapped(source, ceiling)
        for (op in ops) {
            val next = applyOp(bitmap, op)
            if (next !== bitmap) {
                bitmap.recycle()
                bitmap = next
            }
        }
        return bitmap
    }

    /** Decodes [source] with its long edge capped at [ceiling]. */
    private fun decodeCapped(source: Uri, ceiling: Int): Bitmap {
        val decoderSource = ImageDecoder.createSource(context.contentResolver, source)
        return ImageDecoder.decodeBitmap(decoderSource) { decoder, info, _ ->
            // Software allocation because the ops below read and re-encode these
            // pixels; a hardware bitmap cannot be read back.
            decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
            // Mutable so a drawing can be painted straight into the decode
            // rather than into a copy of it. The draw screen's whole op list is
            // a single `RasterOp.Strokes`, so this is the difference between one
            // working-resolution bitmap resident and two.
            decoder.isMutableRequired = true
            val (width, height) =
                ImageEditGeometry.cappedSize(info.size.width, info.size.height, ceiling)
            if (width > 0 && height > 0) decoder.setTargetSize(width, height)
        }
    }

    private fun applyOp(bitmap: Bitmap, op: RasterOp): Bitmap = when (op) {
        is RasterOp.Rotate -> {
            val degrees = ImageEditGeometry.normalizeQuarterTurn(op.degrees)
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

        is RasterOp.Straighten -> {
            val degrees = op.degrees.coerceIn(
                -ImageEditGeometry.STRAIGHTEN_LIMIT,
                ImageEditGeometry.STRAIGHTEN_LIMIT,
            )
            val (width, height) =
                ImageEditGeometry.straightenSize(bitmap.width, bitmap.height, degrees)
            if (width == bitmap.width && height == bitmap.height) {
                bitmap
            } else {
                // Drawn into an output the size of the inscribed rectangle rather
                // than rotated into a larger canvas and cropped afterwards: the
                // two-step version would hold the expanded bitmap *and* the crop
                // at once, which is the allocation the working-resolution ceiling
                // exists to keep off the heap.
                val output = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
                val matrix = Matrix().apply {
                    postTranslate(-bitmap.width / 2f, -bitmap.height / 2f)
                    postRotate(degrees)
                    postTranslate(width / 2f, height / 2f)
                }
                Canvas(output).drawBitmap(bitmap, matrix, Paint(Paint.FILTER_BITMAP_FLAG))
                output
            }
        }

        is RasterOp.Crop -> {
            val rect: PixelRect = ImageEditGeometry.cropRect(bitmap.width, bitmap.height, op)
            if (rect.width == bitmap.width && rect.height == bitmap.height) {
                bitmap
            } else {
                Bitmap.createBitmap(bitmap, rect.x, rect.y, rect.width, rect.height)
            }
        }

        is RasterOp.Resize -> {
            val (width, height) = ImageEditGeometry.resizedSize(bitmap.width, bitmap.height, op.longEdge)
            if (width == bitmap.width && height == bitmap.height) {
                bitmap
            } else {
                Bitmap.createScaledBitmap(bitmap, width, height, true)
            }
        }

        is RasterOp.Strokes ->
            if (op.strokes.isEmpty()) bitmap else drawStrokes(bitmap, op.strokes)

        is RasterOp.Overlays ->
            if (op.overlays.isEmpty()) bitmap else drawOverlays(bitmap, op.overlays)
    }

    /**
     * Paints placed emoji, stickers, text and shapes into [bitmap] — the flatten
     * half of what the overlay screen has been showing live.
     *
     * In list order, which is the z-order: no layer split, because unlike a blur
     * an overlay has no relationship with what it covers, and the last thing you
     * dragged on top being on top is the only rule anyone would predict.
     *
     * Every number comes from [OverlayGeometry], which is also what the editor's
     * Compose preview draws from, so the two renderers cannot disagree about how
     * big an object is or where its centre lands. Only the *painting* is written
     * twice, and only because the two graphics stacks share no path object —
     * exactly the arrangement [drawStrokes] is in.
     */
    private fun drawOverlays(bitmap: Bitmap, overlays: List<ImageOverlay>): Bitmap {
        val output = if (bitmap.isMutable) {
            bitmap
        } else {
            bitmap.copy(Bitmap.Config.ARGB_8888, true) ?: return bitmap
        }
        val canvas = Canvas(output)
        val longEdge = maxOf(output.width, output.height).toFloat()
        for (overlay in overlays) {
            val size = OverlayGeometry.sizePx(overlay.scale, longEdge)
            if (size <= 0f) continue
            val centerX = overlay.centerX * output.width
            val centerY = overlay.centerY * output.height
            val saved = canvas.save()
            canvas.rotate(overlay.rotationDegrees, centerX, centerY)
            when (val content = overlay.content) {
                is OverlayContent.Emoji ->
                    paintGlyphs(canvas, content.emoji, centerX, centerY, size, WHITE_ARGB, filled = true)

                is OverlayContent.Text -> paintGlyphs(
                    canvas = canvas,
                    text = content.text,
                    centerX = centerX,
                    centerY = centerY,
                    fontSize = size,
                    colorArgb = content.colorArgb,
                    filled = content.filled,
                )

                is OverlayContent.Sticker ->
                    StickerPack.byId(content.stickerId)?.let { design ->
                        paintSticker(canvas, design, centerX, centerY, size)
                    }

                is OverlayContent.Shape -> paintShape(canvas, content, centerX, centerY, size)
            }
            canvas.restoreToCount(saved)
        }
        return output
    }

    /**
     * One emoji or text run, centred on the placement.
     *
     * [fontSize] is the size, not a bounding box: an emoji and a text run both
     * scale by *type size*, which is the only measure that means the same thing
     * to Compose and to `android.graphics` without either of them measuring
     * anything. Vertically centred through the font's own metrics rather than
     * its reported height, so a glyph with descenders sits where its body is.
     */
    private fun paintGlyphs(
        canvas: Canvas,
        text: String,
        centerX: Float,
        centerY: Float,
        fontSize: Float,
        colorArgb: Long,
        filled: Boolean,
    ) {
        if (text.isEmpty()) return
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            textSize = fontSize
            textAlign = Paint.Align.CENTER
            color = colorArgb.toInt()
            if (!filled) {
                style = Paint.Style.STROKE
                strokeWidth = fontSize * OverlayGeometry.TEXT_OUTLINE_RATIO
                strokeJoin = Paint.Join.ROUND
            }
        }
        val metrics = paint.fontMetrics
        val baseline = centerY - (metrics.ascent + metrics.descent) / 2f
        canvas.drawText(text, centerX, baseline, paint)
    }

    /** One sticker, its `0..1` parts scaled into a [size]-square box around the centre. */
    private fun paintSticker(
        canvas: Canvas,
        design: StickerDesign,
        centerX: Float,
        centerY: Float,
        size: Float,
    ) {
        val left = centerX - size / 2f
        val top = centerY - size / 2f
        fun x(value: Float) = left + value * size
        fun y(value: Float) = top + value * size

        for (part in design.parts) {
            val paint = Paint(Paint.ANTI_ALIAS_FLAG)
            when (part) {
                is StickerPart.Circle -> {
                    paint.color = part.colorArgb.toInt()
                    canvas.drawCircle(x(part.centerX), y(part.centerY), part.radius * size, paint)
                }

                is StickerPart.Polygon -> {
                    paint.color = part.colorArgb.toInt()
                    val path = Path()
                    part.points.chunked(2).forEachIndexed { index, pair ->
                        if (pair.size < 2) return@forEachIndexed
                        if (index == 0) path.moveTo(x(pair[0]), y(pair[1]))
                        else path.lineTo(x(pair[0]), y(pair[1]))
                    }
                    path.close()
                    canvas.drawPath(path, paint)
                }

                is StickerPart.Line -> {
                    paint.color = part.colorArgb.toInt()
                    paint.style = Paint.Style.STROKE
                    paint.strokeWidth = part.width * size
                    paint.strokeCap = Paint.Cap.ROUND
                    paint.strokeJoin = Paint.Join.ROUND
                    val path = Path()
                    part.points.chunked(2).forEachIndexed { index, pair ->
                        if (pair.size < 2) return@forEachIndexed
                        if (index == 0) path.moveTo(x(pair[0]), y(pair[1]))
                        else path.lineTo(x(pair[0]), y(pair[1]))
                    }
                    canvas.drawPath(path, paint)
                }
            }
        }
    }

    /** One annotation primitive, in a box [size] tall and as wide as its kind wants. */
    private fun paintShape(
        canvas: Canvas,
        shape: OverlayContent.Shape,
        centerX: Float,
        centerY: Float,
        size: Float,
    ) {
        val halfHeight = size / 2f
        val halfWidth = halfHeight * OverlayGeometry.aspectFor(shape.kind)
        val stroke = size * OverlayGeometry.SHAPE_STROKE_RATIO
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = shape.colorArgb.toInt()
            style = if (OverlayGeometry.isOutlined(shape)) Paint.Style.STROKE else Paint.Style.FILL
            strokeWidth = stroke
            strokeCap = Paint.Cap.ROUND
            strokeJoin = Paint.Join.ROUND
        }
        // Inset by half the stroke so an outline stays inside the box the editor
        // drew its selection frame around, rather than straddling it.
        val inset = if (paint.style == Paint.Style.STROKE) stroke / 2f else 0f
        val left = centerX - halfWidth + inset
        val right = centerX + halfWidth - inset
        val top = centerY - halfHeight + inset
        val bottom = centerY + halfHeight - inset

        when (shape.kind) {
            ShapeKind.RECTANGLE -> canvas.drawRect(left, top, right, bottom, paint)
            ShapeKind.ROUNDED_RECTANGLE -> {
                val radius = size * OverlayGeometry.ROUNDED_SHAPE_RADIUS_RATIO
                canvas.drawRoundRect(left, top, right, bottom, radius, radius, paint)
            }

            ShapeKind.ELLIPSE -> canvas.drawOval(left, top, right, bottom, paint)
            ShapeKind.LINE -> canvas.drawLine(centerX - halfWidth, centerY, centerX + halfWidth, centerY, paint)
            ShapeKind.ARROW -> {
                val head = size * OverlayGeometry.ARROW_HEAD_RATIO
                val tip = centerX + halfWidth
                canvas.drawLine(
                    centerX - halfWidth,
                    centerY,
                    tip - head * OverlayGeometry.ARROW_SHAFT_TRIM_RATIO,
                    centerY,
                    paint,
                )
                val path = Path().apply {
                    moveTo(tip, centerY)
                    lineTo(tip - head, centerY - head * OverlayGeometry.ARROW_BARB_RATIO)
                    lineTo(tip - head, centerY + head * OverlayGeometry.ARROW_BARB_RATIO)
                    close()
                }
                canvas.drawPath(path, Paint(paint).apply { style = Paint.Style.FILL })
            }
        }
    }

    /**
     * Paints a drawing into [bitmap] and returns the result — the flatten half
     * of what the draw screen has been showing live.
     *
     * Both layers come from [StrokeGeometry], which is also what the editor's
     * Compose preview draws from, so the two renderers cannot disagree about
     * how wide a stroke is or where its curve runs. Only the *painting* is
     * written twice, and only because there is no path object the two graphics
     * stacks share.
     *
     * Drawn in place when the decode handed back a mutable bitmap, which is the
     * ordinary case: a copy at working resolution is another 64 MB resident
     * beside the original.
     */
    private fun drawStrokes(bitmap: Bitmap, strokes: List<Stroke>): Bitmap {
        val output = if (bitmap.isMutable) {
            bitmap
        } else {
            bitmap.copy(Bitmap.Config.ARGB_8888, true) ?: return bitmap
        }
        val canvas = Canvas(output)
        val longEdge = maxOf(output.width, output.height).toFloat()
        val layers = StrokeGeometry.layers(strokes)

        if (layers.blur.isNotEmpty()) {
            // The mosaic is computed from the photo as it arrived, not from the
            // canvas as it stands: blur redacts the image, and it must not be
            // able to redact an arrow the user drew pointing at what it hides.
            // Painted strokes go on afterwards for the same reason.
            val mosaic = pixelate(bitmap)
            try {
                // One layer for every blur stroke at once. The strokes are the
                // mask and the mosaic is drawn through them with SRC_IN, which
                // is what makes overlapping strokes reveal the same pixels
                // rather than compounding into something darker.
                val saved = canvas.saveLayer(null, null)
                val mask = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = android.graphics.Color.WHITE }
                for (stroke in layers.blur) paintStroke(canvas, stroke, mask, longEdge, output)
                canvas.drawBitmap(
                    mosaic,
                    null,
                    Rect(0, 0, output.width, output.height),
                    Paint().apply {
                        // Nearest-neighbour on the way back up: this is what makes
                        // the mosaic read as blocks rather than as a soft blur, and
                        // it is the half of "pixelate" that does the redacting.
                        isFilterBitmap = false
                        xfermode = PorterDuffXfermode(PorterDuff.Mode.SRC_IN)
                    },
                )
                canvas.restoreToCount(saved)
            } finally {
                if (mosaic !== bitmap) mosaic.recycle()
            }
        }

        for (stroke in layers.painted) {
            val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = stroke.colorArgb.toInt()
                // After the colour, deliberately: this overwrites whatever alpha
                // the stored ARGB carried, so a highlighter is translucent by
                // virtue of being a highlighter and not by virtue of its swatch.
                alpha = (StrokeGeometry.alphaFor(stroke.tool) * 255f).roundToInt().coerceIn(0, 255)
                if (stroke.tool == StrokeTool.HIGHLIGHTER) blendMode = BlendMode.MULTIPLY
            }
            paintStroke(canvas, stroke, paint, longEdge, output)
        }
        return output
    }

    /** One stroke — a round-capped path, or a dot when the finger never moved. */
    private fun paintStroke(canvas: Canvas, stroke: Stroke, paint: Paint, longEdge: Float, target: Bitmap) {
        val width = StrokeGeometry.widthPx(stroke.width, longEdge)
        if (StrokeGeometry.isDot(stroke)) {
            val point = stroke.points.first()
            val dot = Paint(paint).apply { style = Paint.Style.FILL }
            canvas.drawCircle(point.x * target.width, point.y * target.height, width / 2f, dot)
            return
        }
        val path = Path()
        StrokeGeometry.buildPath(
            points = stroke.points,
            scaleX = target.width.toFloat(),
            scaleY = target.height.toFloat(),
            offsetX = 0f,
            offsetY = 0f,
            sink = object : PathSink {
                override fun moveTo(x: Float, y: Float) = path.moveTo(x, y)
                override fun lineTo(x: Float, y: Float) = path.lineTo(x, y)
                override fun quadTo(controlX: Float, controlY: Float, x: Float, y: Float) =
                    path.quadTo(controlX, controlY, x, y)
            },
        )
        canvas.drawPath(
            path,
            Paint(paint).apply {
                style = Paint.Style.STROKE
                strokeWidth = width
                strokeCap = Paint.Cap.ROUND
                strokeJoin = Paint.Join.ROUND
            },
        )
    }

    /**
     * The downscaled copy a blur stroke reveals — [StrokeGeometry.PIXELATE_BLOCKS]
     * blocks across the long edge. Callers scale it back up with
     * nearest-neighbour filtering; keeping it small is what makes the editor's
     * preview and the flatten produce the *same* blocks at their own sizes.
     *
     * **Halved repeatedly rather than scaled straight down**, and that is the
     * difference between a redaction and a decoration: a single 85× bilinear
     * downscale samples four neighbours per output pixel, so fine detail
     * survives as aliasing instead of being averaged away — a striped or
     * textured region would come back as a pattern rather than as a flat block.
     * Halving averages every source pixel into the result.
     *
     * Public so the editor's live preview reveals the mosaic this produces
     * rather than computing a second one of its own.
     */
    fun pixelate(bitmap: Bitmap): Bitmap {
        val (targetWidth, targetHeight) =
            StrokeGeometry.pixelatedSize(bitmap.width, bitmap.height)
        if (targetWidth >= bitmap.width || targetHeight >= bitmap.height) {
            // Already at or below the mosaic's own resolution. An independent
            // copy, because every caller recycles what this hands back.
            return bitmap.copy(Bitmap.Config.ARGB_8888, false) ?: bitmap
        }
        var stage = bitmap
        var owned = false
        while (stage.width / 2 > targetWidth && stage.height / 2 > targetHeight) {
            val next = Bitmap.createScaledBitmap(stage, stage.width / 2, stage.height / 2, true)
            if (owned) stage.recycle()
            stage = next
            owned = true
        }
        val mosaic = Bitmap.createScaledBitmap(stage, targetWidth, targetHeight, true)
        if (owned && mosaic !== stage) stage.recycle()
        return mosaic
    }

    private fun probe(source: Uri): SourceImage? = try {
        val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        context.contentResolver.openInputStream(source)?.use {
            BitmapFactory.decodeStream(it, null, options)
        }
        if (options.outWidth <= 0 || options.outHeight <= 0) {
            null
        } else {
            SourceImage(options.outWidth, options.outHeight, sourceBytes(source))
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

    /**
     * A file inside `cacheDir/edits/` or its `sources/`, or null for anything
     * [discard] must not touch.
     */
    private fun editFile(uri: Uri, dir: File): File? {
        if (uri.scheme != "file") return null
        val path = uri.path ?: return null
        val file = runCatching { File(path).canonicalFile }.getOrNull() ?: return null
        val parent = file.parentFile
        return if (parent == dir || parent == File(dir, SOURCES_DIR)) file else null
    }

    companion object {
        /** Subdirectory of `cacheDir` holding every rasterized step. */
        internal const val EDITS_DIR = "edits"

        /** Subdirectory of [EDITS_DIR] holding originals copied in by [importSource]. */
        internal const val SOURCES_DIR = "sources"

        /** Quality for intermediate steps; only the send re-encodes at q80/q100. */
        private const val INTERMEDIATE_QUALITY = 95

        /** An emoji has no colour of its own to choose; the glyph carries it. */
        private const val WHITE_ARGB = 0xFFFFFFFF


        /**
         * Ceiling on `cacheDir/edits/`. Eight steps of a 4096 px q95 JPEG is
         * roughly 30 MB per image, so this is about eight fully-edited images
         * held at once — comfortably more than a preview session needs, and two
         * orders of magnitude under the gigabyte an unbounded cache reaches.
         */
        private const val CACHE_BUDGET_BYTES = 256L * 1024 * 1024

        /** How long an edit file survives a process restart; see [sweepStale]. */
        private val MAX_AGE_MILLIS = TimeUnit.HOURS.toMillis(24)
    }
}
