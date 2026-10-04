package com.firestream.chat.data.sticker

import android.graphics.Bitmap
import android.graphics.Canvas
import com.airbnb.lottie.LottieCompositionFactory
import com.airbnb.lottie.LottieDrawable
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import javax.inject.Inject
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * Draws the first frame of a Lottie sticker as a PNG, and is the last check
 * before a Lottie sticker is stored.
 *
 * Grids and previews show stickers through a plain image request, which cannot
 * read an animation. A grid of parsed animations would also cost a composition
 * per cell.
 *
 * `LottieContainer` checks the JSON with its own parser. Here Lottie parses
 * the same bytes and builds and draws the layers, off the main thread, which
 * is everything a screen will later do with the file on the main thread.
 */
class LottieThumbnails @Inject constructor() {

    /**
     * The first frame of the animation [json], at most [EDGE] px on its longer
     * side. Returns `null` for an animation that is not fit to be a sticker:
     * Lottie cannot parse, build or draw it, it holds an image, or Lottie reads
     * a size out of it that is past [StickerFiles.MAX_DIMENSION].
     */
    fun firstFrame(json: ByteArray): ByteArray? = try {
        // No cache key: a sticker is parsed here once, and the cache is for what a screen plays.
        val composition = LottieCompositionFactory.fromJsonInputStreamSync(ByteArrayInputStream(json), null).value
        val width = composition?.bounds?.width() ?: 0
        val height = composition?.bounds?.height() ?: 0
        if (composition == null || composition.images.isNotEmpty() ||
            width !in 1..StickerFiles.MAX_DIMENSION || height !in 1..StickerFiles.MAX_DIMENSION
        ) {
            null
        } else {
            val scale = minOf(1f, EDGE.toFloat() / max(width, height))
            val bitmap = Bitmap.createBitmap(
                (width * scale).roundToInt().coerceAtLeast(1),
                (height * scale).roundToInt().coerceAtLeast(1),
                Bitmap.Config.ARGB_8888,
            )
            LottieDrawable().apply {
                this.composition = composition
                setBounds(0, 0, bitmap.width, bitmap.height)
                frame = composition.startFrame.toInt()
                draw(Canvas(bitmap))
            }
            val out = ByteArrayOutputStream()
            bitmap.compress(Bitmap.CompressFormat.PNG, 100, out)
            bitmap.recycle()
            out.toByteArray()
        }
    } catch (e: Exception) {
        null
    } catch (e: StackOverflowError) {
        // Layers that refer to each other. LottieContainer counts them, and this is the net under it.
        null
    } catch (e: OutOfMemoryError) {
        null
    }

    private companion object {
        const val EDGE = 256
    }
}
