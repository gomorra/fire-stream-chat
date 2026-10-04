package com.firestream.chat.data.sticker

import android.graphics.Bitmap
import java.io.ByteArrayOutputStream
import javax.inject.Inject

/**
 * Turns a made sticker's bitmap into the WebP file that is stored and sent:
 * lossy with transparency, at the highest quality that stays under [MAX_BYTES].
 */
class StickerEncoder @Inject constructor() {

    /** [bitmap] as a WebP of at most [MAX_BYTES], or `null` when even the lowest quality is larger. */
    fun encode(bitmap: Bitmap): ByteArray? = firstUnder(MAX_BYTES, QUALITIES) { quality ->
        ByteArrayOutputStream().also { bitmap.compress(Bitmap.CompressFormat.WEBP_LOSSY, quality, it) }.toByteArray()
    }

    companion object {
        /** WhatsApp's limit for a static sticker, which keeps a made sticker usable there too. */
        const val MAX_BYTES = 100 * 1024

        /** Tried in this order. */
        val QUALITIES = listOf(90, 80, 70, 60, 50, 40, 30, 20, 10)

        /**
         * The first result of [compress], over [qualities] in order, that is not
         * empty and at most [maxBytes] long. `null` when none is. Nothing is
         * compressed after the first one that fits.
         */
        internal fun firstUnder(maxBytes: Int, qualities: List<Int>, compress: (quality: Int) -> ByteArray): ByteArray? {
            for (quality in qualities) {
                val bytes = compress(quality)
                if (bytes.isNotEmpty() && bytes.size <= maxBytes) return bytes
            }
            return null
        }
    }
}
