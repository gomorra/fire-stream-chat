package com.firestream.chat.ui.chat.imageedit

import android.content.Context
import android.graphics.BitmapFactory
import android.net.Uri
import android.provider.OpenableColumns
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Header-only facts about a picked image: enough to estimate, not enough to decode. */
internal data class ImageProbe(
    val width: Int,
    val height: Int,
    val sourceBytes: Long,
)

/**
 * Approximate send sizes for the HD sheet.
 *
 * The sheet promises "about", and that is deliberate: measuring exactly means a
 * second full decode-and-encode per image, which is the very thing
 * `MediaProcessingLimiter` exists to prevent (`.claude/plans/image-editor.md`
 * §2.5). So the estimate is arithmetic over the source's pixel count and file
 * size, mirroring what `ImageCompressor` will actually do — cap the long edge at
 * [STANDARD_MAX_DIMENSION] and re-encode at q80, or keep full resolution at
 * q100.
 *
 * Pure Kotlin on purpose: no Android types cross into the estimate itself, so
 * the arithmetic is unit-testable on the JVM. Reading the source's header is
 * [probeImage]'s job, and that is the only part that needs a `Context`.
 */
internal object ImageSizeEstimator {

    /** Long-edge cap `ImageCompressor` applies to a non-HD send. */
    private const val STANDARD_MAX_DIMENSION = 1600

    /**
     * Bytes per pixel assumed when the source's own file size is unknown —
     * roughly a q85 photo, which is what a camera or a gallery pick usually is.
     */
    private const val DEFAULT_BYTES_PER_PIXEL = 0.22f

    /**
     * The source's own bytes-per-pixel is the best available signal, but only
     * inside the range a JPEG photo actually occupies. A PNG screenshot or a
     * near-lossless export sits far above it and would inflate both rows;
     * a heavily-recompressed thumbnail sits below and would flatter them.
     */
    private const val MIN_BYTES_PER_PIXEL = 0.05f
    private const val MAX_BYTES_PER_PIXEL = 0.60f

    /** q80 against a typical source encode — the standard row's discount. */
    private const val STANDARD_QUALITY_FACTOR = 0.85f

    /** q100 against the same source — re-encoding at maximum quality inflates. */
    private const val HD_QUALITY_FACTOR = 1.6f

    /**
     * Estimated bytes on the wire for [probe] sent with [hd] on or off.
     *
     * Returns 0 for a degenerate probe (a header we could not read) so the
     * caller can drop the size label rather than print a confident "0 KB".
     */
    fun estimateBytes(probe: ImageProbe, hd: Boolean): Long {
        if (probe.width <= 0 || probe.height <= 0) return 0
        val sourcePixels = probe.width.toLong() * probe.height.toLong()
        val bytesPerPixel = if (probe.sourceBytes > 0) {
            (probe.sourceBytes.toFloat() / sourcePixels)
                .coerceIn(MIN_BYTES_PER_PIXEL, MAX_BYTES_PER_PIXEL)
        } else {
            DEFAULT_BYTES_PER_PIXEL
        }
        return if (hd) {
            (sourcePixels * bytesPerPixel * HD_QUALITY_FACTOR).toLong()
        } else {
            val longEdge = maxOf(probe.width, probe.height)
            val scale = if (longEdge > STANDARD_MAX_DIMENSION) {
                STANDARD_MAX_DIMENSION.toFloat() / longEdge
            } else {
                1f
            }
            val pixels = sourcePixels * scale * scale
            (pixels * bytesPerPixel * STANDARD_QUALITY_FACTOR).toLong()
        }
    }

    /**
     * Output dimensions the two rows would produce, for the sheet's `W × H`
     * line. HD keeps the source resolution; standard caps the long edge.
     */
    fun estimateDimensions(probe: ImageProbe, hd: Boolean): Pair<Int, Int> {
        if (probe.width <= 0 || probe.height <= 0) return 0 to 0
        if (hd) return probe.width to probe.height
        val longEdge = maxOf(probe.width, probe.height)
        if (longEdge <= STANDARD_MAX_DIMENSION) return probe.width to probe.height
        val scale = STANDARD_MAX_DIMENSION.toFloat() / longEdge
        return (probe.width * scale).toInt().coerceAtLeast(1) to
            (probe.height * scale).toInt().coerceAtLeast(1)
    }

    /**
     * Decimal KB/MB, one decimal place above a megabyte — the shape a gallery
     * app shows, not a binary-prefixed one. Blank for a size we could not
     * estimate.
     */
    fun formatBytes(bytes: Long): String = when {
        bytes <= 0 -> ""
        bytes < 1_000_000 -> "${(bytes / 1_000f).toInt().coerceAtLeast(1)} KB"
        else -> "%.1f MB".format(bytes / 1_000_000f)
    }
}

/**
 * Reads [uri]'s pixel dimensions and file size without decoding it.
 *
 * `inJustDecodeBounds` parses the JPEG header only, so this costs a stream open
 * rather than a bitmap — cheap enough to run when the HD sheet opens, and it
 * takes no `MediaProcessingLimiter` permit because it holds no bitmap. Returns
 * null when the URI cannot be opened at all; the sheet then shows its rows
 * without size labels rather than failing.
 */
internal suspend fun probeImage(context: Context, uri: Uri): ImageProbe? =
    withContext(Dispatchers.IO) {
        try {
            val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            context.contentResolver.openInputStream(uri)?.use {
                BitmapFactory.decodeStream(it, null, options)
            } ?: return@withContext null
            if (options.outWidth <= 0 || options.outHeight <= 0) return@withContext null
            ImageProbe(options.outWidth, options.outHeight, sourceBytes(context, uri))
        } catch (_: Exception) {
            null
        }
    }

/** File size for a `content://` or `file://` URI; 0 when the provider withholds it. */
private fun sourceBytes(context: Context, uri: Uri): Long = try {
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
