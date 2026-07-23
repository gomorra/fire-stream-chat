package com.firestream.chat.ui.components

import android.graphics.ImageDecoder
import android.graphics.drawable.BitmapDrawable
import coil.ImageLoader
import coil.decode.DecodeResult
import coil.decode.Decoder
import coil.decode.ImageSource
import coil.fetch.SourceResult
import coil.request.Options
import coil.size.Scale
import coil.size.Size
import coil.size.pxOrElse
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * A Coil [Decoder] that decodes through Android's [ImageDecoder] instead of the
 * default `BitmapFactory` path.
 *
 * Why this exists: the Shared Media grid decodes large, pre-compression original
 * images down into small tiles. Coil's default `BitmapFactory` decoder reaches a
 * small tile via a large power-of-two `inSampleSize` subsample, and a heavily
 * subsampled `BitmapFactory` decode returns a **black bitmap** for certain large
 * / camera-original images (a well-known Android decoder pathology) — no error is
 * raised, so the tile just renders black while the barely-downsampled fullscreen
 * decode of the same image succeeds.
 *
 * [ImageDecoder.setTargetSize] performs a proper high-quality scaled decode (not
 * a power-of-two subsample), which sidesteps the pathology while still producing
 * a small, memory-cheap bitmap. It also honours EXIF orientation automatically.
 *
 * Scoped per-request via [coil.request.ImageRequest.Builder.decoderFactory] so
 * only the grid uses it; avatars, message bubbles, and the fullscreen viewer keep
 * Coil's defaults. Available unconditionally at this app's `minSdk = 29`.
 */
class ScaledImageDecoder(
    private val source: ImageSource,
    private val options: Options,
) : Decoder {

    override suspend fun decode(): DecodeResult {
        // .file() materialises the source to a real file if it isn't one already,
        // which ImageDecoder.createSource(File) requires (API 29+).
        val file = source.file().toFile()
        val decoderSource = ImageDecoder.createSource(file)
        val bitmap = ImageDecoder.decodeBitmap(decoderSource) { decoder, info, _ ->
            // Software bitmap keeps the grid off the hardware-buffer budget and
            // small in memory; we never mutate the result.
            decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
            decoder.isMutableRequired = false
            val (targetWidth, targetHeight) = computeTargetSize(
                srcWidth = info.size.width,
                srcHeight = info.size.height,
                size = options.size,
                scale = options.scale,
            )
            if (targetWidth > 0 && targetHeight > 0) {
                decoder.setTargetSize(targetWidth, targetHeight)
            }
        }
        return DecodeResult(
            drawable = BitmapDrawable(options.context.resources, bitmap),
            isSampled = true,
        )
    }

    class Factory : Decoder.Factory {
        override fun create(
            result: SourceResult,
            options: Options,
            imageLoader: ImageLoader,
        ): Decoder? {
            // ImageDecoder can't decode video; let Coil fall through to its
            // default (coil-video) decoder for a thumbnail-less shared video.
            if (result.mimeType?.startsWith("video/") == true) return null
            return ScaledImageDecoder(result.source, options)
        }
    }
}

/**
 * Resolves the pixel dimensions to decode to, preserving the source aspect ratio
 * and never upscaling. Mirrors Coil's own size-multiplier logic ([Scale.FILL]
 * covers the target box, [Scale.FIT] fits inside it). An [coil.size.Dimension]
 * that is `Undefined` falls back to the matching source dimension.
 *
 * Pure and Android-free so it can be unit-tested; the [ImageDecoder] decode
 * itself is verified on-device.
 */
internal fun computeTargetSize(
    srcWidth: Int,
    srcHeight: Int,
    size: Size,
    scale: Scale,
): Pair<Int, Int> {
    if (srcWidth <= 0 || srcHeight <= 0) return srcWidth to srcHeight

    val dstWidth = size.width.pxOrElse { srcWidth }
    val dstHeight = size.height.pxOrElse { srcHeight }

    val widthFactor = dstWidth / srcWidth.toDouble()
    val heightFactor = dstHeight / srcHeight.toDouble()
    val factor = when (scale) {
        Scale.FILL -> max(widthFactor, heightFactor)
        Scale.FIT -> min(widthFactor, heightFactor)
    }.coerceAtMost(1.0) // never upscale

    val targetWidth = (srcWidth * factor).roundToInt().coerceAtLeast(1)
    val targetHeight = (srcHeight * factor).roundToInt().coerceAtLeast(1)
    return targetWidth to targetHeight
}
