package com.firestream.chat.data.util

import android.content.Context
import android.graphics.Bitmap
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Handler
import android.os.Looper
import androidx.media3.common.Effect
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.audio.AudioProcessor
import androidx.media3.effect.Presentation
import androidx.media3.transformer.Composition
import androidx.media3.transformer.EditedMediaItem
import androidx.media3.transformer.Effects
import androidx.media3.transformer.ExportException
import androidx.media3.transformer.ExportResult
import androidx.media3.transformer.Transformer
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import java.io.File
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

data class VideoMetadata(
    val width: Int,
    val height: Int,
    val durationMs: Long,
    val rotationDegrees: Int,
    val sizeBytes: Long
)

data class VideoResult(
    val file: File,
    val width: Int,
    val height: Int,
    val durationSec: Int,
    val mimeType: String = "video/mp4"
)

data class ThumbResult(
    val file: File,
    val width: Int,
    val height: Int
)

/**
 * Transcodes and inspects videos before upload. Mirrors [ImageCompressor]'s @Singleton /
 * @Inject-constructor shape (Hilt discovers it via the constructor, no module binding needed)
 * and its temp-file/error-handling conventions.
 *
 * ## Media3 Transformer looper + cancellation contract
 * [Transformer] must be **built and started on a thread with a prepared [Looper]**, and its
 * listener callbacks (`onCompleted` / `onError`) are delivered on that same looper. We therefore
 * do the build + start inside `withContext(Dispatchers.Main)` and wrap the whole thing in a
 * [suspendCancellableCoroutine]:
 * - `onCompleted` → `resume` with the transcoded result,
 * - `onError` → `resumeWithException`,
 * - `invokeOnCancellation` → posts `transformer.cancel()` back to the **main looper** (cancel must
 *   run on the same thread that started the export) and deletes the partial output file.
 *
 * When the caller's coroutine scope is cancelled (e.g. the user leaves the chat mid-send), the
 * export is aborted and the temp file cleaned up — identical lifecycle to the image path.
 *
 * ## Testing
 * The Transformer/MediaMetadataRetriever paths are **device-only** and intentionally untested by
 * the JVM suite (Robolectric does not model the codec pipeline). The scale/rotation math lives in
 * the pure top-level functions [displayDimensions], [needsDownscale] and [outputDimensions], which
 * carry no `android.*` types and are covered by `VideoTranscoderLogicTest`.
 */
@Singleton
class VideoTranscoder @Inject constructor(
    @ApplicationContext private val context: Context
) {
    companion object {
        /** Reject source videos longer than 3 minutes. */
        const val MAX_VIDEO_DURATION_MS = 180_000L

        /** Reject source videos larger than 100 MB. */
        const val MAX_VIDEO_SOURCE_BYTES = 100L * 1024 * 1024

        private const val THUMB_MAX_EDGE = 1280
        private const val THUMB_JPEG_QUALITY = 80
    }

    /**
     * Reads width/height/duration/rotation via [MediaMetadataRetriever] and the byte size via the
     * content resolver (falling back to [File] length for `file://` URIs). The retriever is always
     * released.
     */
    fun readMetadata(uri: Uri): VideoMetadata {
        val retriever = MediaMetadataRetriever()
        try {
            retriever.setDataSource(context, uri)
            val width = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)
                ?.toIntOrNull() ?: 0
            val height = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)
                ?.toIntOrNull() ?: 0
            val durationMs = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)
                ?.toLongOrNull() ?: 0L
            val rotation = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION)
                ?.toIntOrNull() ?: 0
            val sizeBytes = readSizeBytes(uri)
            return VideoMetadata(width, height, durationMs, rotation, sizeBytes)
        } finally {
            retriever.release()
        }
    }

    private fun readSizeBytes(uri: Uri): Long {
        context.contentResolver.openAssetFileDescriptor(uri, "r")?.use { afd ->
            val len = afd.length
            if (len >= 0) return len
        }
        // Fall back to the raw file length for file:// URIs.
        return uri.path?.let { File(it).takeIf { f -> f.exists() }?.length() } ?: 0L
    }

    /**
     * Transcodes [uri] to H.264/AAC MP4 in `cacheDir/transcoded/<uuid>.mp4`. A
     * [Presentation.createForHeight] video effect is applied only when the rotation-adjusted
     * display height exceeds [targetHeight] (never upscale); compatible tracks are otherwise passed
     * through. See the class KDoc for the looper/cancellation contract.
     */
    suspend fun transcode(uri: Uri, targetHeight: Int): VideoResult {
        val metadata = withContext(Dispatchers.IO) { readMetadata(uri) }
        val (displayWidth, displayHeight) =
            displayDimensions(metadata.width, metadata.height, metadata.rotationDegrees)
        val durationSec = ((metadata.durationMs + 500) / 1000).toInt()

        val outputDir = File(context.cacheDir, "transcoded").also { it.mkdirs() }
        val outputFile = File(outputDir, "${UUID.randomUUID()}.mp4")

        val videoEffects: List<Effect> =
            if (needsDownscale(displayHeight, targetHeight)) {
                listOf(Presentation.createForHeight(targetHeight))
            } else {
                emptyList()
            }

        return withContext(Dispatchers.Main) {
            suspendCancellableCoroutine { cont ->
                val transformer = Transformer.Builder(context)
                    .setVideoMimeType(MimeTypes.VIDEO_H264)
                    .setAudioMimeType(MimeTypes.AUDIO_AAC)
                    .addListener(object : Transformer.Listener {
                        override fun onCompleted(composition: Composition, exportResult: ExportResult) {
                            val (outWidth, outHeight) =
                                outputDimensions(displayWidth, displayHeight, targetHeight)
                            cont.resume(VideoResult(outputFile, outWidth, outHeight, durationSec))
                        }

                        override fun onError(
                            composition: Composition,
                            exportResult: ExportResult,
                            exportException: ExportException
                        ) {
                            outputFile.delete()
                            cont.resumeWithException(exportException)
                        }
                    })
                    .build()

                val editedMediaItem = EditedMediaItem.Builder(MediaItem.fromUri(uri))
                    .setEffects(Effects(emptyList<AudioProcessor>(), videoEffects))
                    .build()

                transformer.start(editedMediaItem, outputFile.absolutePath)

                cont.invokeOnCancellation {
                    // cancel() must run on the looper that started the export.
                    Handler(Looper.getMainLooper()).post {
                        transformer.cancel()
                        outputFile.delete()
                    }
                }
            }
        }
    }

    /**
     * Extracts a representative frame near t=0–1s, scaled so its long edge is ≤ 1280 px (no
     * upscale), and writes it as a JPEG (quality 80) to `cacheDir/transcoded/thumb_<uuid>.jpg`.
     * Bitmaps are recycled; the retriever is always released.
     */
    fun extractThumbnail(uri: Uri): ThumbResult {
        val retriever = MediaMetadataRetriever()
        var frame: Bitmap? = null
        var scaled: Bitmap? = null
        try {
            retriever.setDataSource(context, uri)
            frame = retriever.getFrameAtTime(0L, MediaMetadataRetriever.OPTION_CLOSEST_SYNC)
                ?: throw IllegalArgumentException("Cannot extract video frame: $uri")

            val longestEdge = maxOf(frame.width, frame.height)
            val scale = if (longestEdge > THUMB_MAX_EDGE) {
                THUMB_MAX_EDGE.toFloat() / longestEdge
            } else {
                1f
            }
            val targetWidth = (frame.width * scale).toInt().coerceAtLeast(1)
            val targetHeight = (frame.height * scale).toInt().coerceAtLeast(1)

            scaled = if (targetWidth != frame.width || targetHeight != frame.height) {
                Bitmap.createScaledBitmap(frame, targetWidth, targetHeight, true)
            } else {
                frame
            }

            val outputDir = File(context.cacheDir, "transcoded").also { it.mkdirs() }
            val outputFile = File(outputDir, "thumb_${UUID.randomUUID()}.jpg")
            outputFile.outputStream().use { out ->
                scaled.compress(Bitmap.CompressFormat.JPEG, THUMB_JPEG_QUALITY, out)
            }
            return ThumbResult(outputFile, scaled.width, scaled.height)
        } finally {
            if (scaled != null && scaled !== frame) scaled.recycle()
            frame?.recycle()
            retriever.release()
        }
    }
}

/**
 * Display dimensions after applying container rotation: width/height are swapped for 90°/270°.
 * Pure (no `android.*`) so it is JVM-testable.
 */
fun displayDimensions(width: Int, height: Int, rotationDegrees: Int): Pair<Int, Int> {
    val normalized = ((rotationDegrees % 360) + 360) % 360
    return if (normalized == 90 || normalized == 270) {
        height to width
    } else {
        width to height
    }
}

/** True when the rotation-adjusted display height exceeds the target (i.e. downscaling is needed). */
fun needsDownscale(displayHeight: Int, targetHeight: Int): Boolean =
    displayHeight > targetHeight

/**
 * Output dimensions for the transcode: when downscaling, scale proportionally to [targetHeight] and
 * round both edges to even numbers (encoders require even dimensions); otherwise passthrough the
 * display dimensions unchanged. Pure (no `android.*`) so it is JVM-testable.
 */
fun outputDimensions(displayWidth: Int, displayHeight: Int, targetHeight: Int): Pair<Int, Int> {
    if (!needsDownscale(displayHeight, targetHeight)) {
        return displayWidth to displayHeight
    }
    val scale = targetHeight.toDouble() / displayHeight.toDouble()
    val scaledWidth = Math.round(displayWidth * scale).toInt()
    return toEven(scaledWidth) to toEven(targetHeight)
}

/** Rounds down to the nearest even integer, with a floor of 2. */
private fun toEven(value: Int): Int {
    val even = value - (value % 2)
    return if (even < 2) 2 else even
}
