package com.firestream.chat.domain.util

/**
 * What a WebP file says about itself in its container.
 *
 * [exif] is the raw payload of the `EXIF` chunk, or `null` when the file has none.
 * Not a data class: an array has no structural equality to offer one.
 */
class WebpInfo(
    val width: Int,
    val height: Int,
    val isAnimated: Boolean,
    val exif: ByteArray?,
)

/**
 * Reads a WebP file's RIFF container without decoding a pixel.
 *
 * The input is untrusted: a file from another app or out of an archive. Every
 * size is read as an unsigned 32-bit value and checked against the bytes that
 * are there before anything is indexed, and a file whose chunks run past its end
 * is refused rather than read as far as it goes.
 */
object WebpContainer {

    private const val RIFF_HEADER = 12
    private const val CHUNK_HEADER = 8
    private const val VP8X_PAYLOAD = 10
    private const val VP8X_ANIMATION_FLAG = 0x02

    /**
     * The container facts of [bytes], or `null` when it is not a whole WebP file:
     * no RIFF/WEBP header, a declared size the bytes do not cover, no image data,
     * or dimensions that cannot be read.
     */
    fun parse(bytes: ByteArray): WebpInfo? {
        if (bytes.size < RIFF_HEADER || !bytes.hasTag(0, "RIFF") || !bytes.hasTag(8, "WEBP")) return null
        val riffEnd = 8L + bytes.u32(4)
        if (riffEnd > bytes.size) return null

        var canvas: Pair<Int, Int>? = null
        var frame: Pair<Int, Int>? = null
        var isAnimated = false
        var hasImageData = false
        var exif: ByteArray? = null

        var pos = RIFF_HEADER.toLong()
        while (pos + CHUNK_HEADER <= riffEnd) {
            val header = pos.toInt()
            val size = bytes.u32(header + 4)
            val start = pos + CHUNK_HEADER
            val end = start + size
            if (end > riffEnd) return null
            val payload = start.toInt()
            when {
                bytes.hasTag(header, "VP8X") -> {
                    if (size < VP8X_PAYLOAD) return null
                    isAnimated = bytes[payload].toInt() and VP8X_ANIMATION_FLAG != 0
                    canvas = (bytes.u24(payload + 4) + 1) to (bytes.u24(payload + 7) + 1)
                }
                bytes.hasTag(header, "VP8 ") -> {
                    hasImageData = true
                    if (frame == null) frame = lossyDimensions(bytes, payload, size) ?: return null
                }
                bytes.hasTag(header, "VP8L") -> {
                    hasImageData = true
                    if (frame == null) frame = losslessDimensions(bytes, payload, size) ?: return null
                }
                bytes.hasTag(header, "ANMF") -> hasImageData = true
                bytes.hasTag(header, "EXIF") -> if (exif == null) exif = bytes.copyOfRange(payload, end.toInt())
            }
            // A chunk with an odd size is followed by one padding byte.
            pos = end + (size and 1L)
        }

        if (!hasImageData) return null
        // A frame larger than its canvas is not a valid file, and would make the canvas a lie about the decode size.
        if (canvas != null && frame != null && (frame.first > canvas.first || frame.second > canvas.second)) return null
        val (width, height) = canvas ?: frame ?: return null
        if (width < 1 || height < 1) return null
        return WebpInfo(width, height, isAnimated, exif)
    }

    /** A lossy keyframe: three tag bytes, the start code `9D 01 2A`, then 14-bit width and height. */
    private fun lossyDimensions(bytes: ByteArray, payload: Int, size: Long): Pair<Int, Int>? {
        if (size < 10) return null
        val startCode = bytes.u8(payload + 3) == 0x9D && bytes.u8(payload + 4) == 0x01 && bytes.u8(payload + 5) == 0x2A
        if (!startCode) return null
        return (bytes.u16(payload + 6) and 0x3FFF) to (bytes.u16(payload + 8) and 0x3FFF)
    }

    /** A lossless stream: the signature `2F`, then width − 1 and height − 1 in 14 bits each. */
    private fun losslessDimensions(bytes: ByteArray, payload: Int, size: Long): Pair<Int, Int>? {
        if (size < 5 || bytes.u8(payload) != 0x2F) return null
        val bits = bytes.u32(payload + 1)
        return ((bits and 0x3FFF).toInt() + 1) to (((bits shr 14) and 0x3FFF).toInt() + 1)
    }

    private fun ByteArray.hasTag(offset: Int, tag: String): Boolean =
        tag.indices.all { this[offset + it] == tag[it].code.toByte() }

    private fun ByteArray.u8(offset: Int): Int = this[offset].toInt() and 0xFF

    private fun ByteArray.u16(offset: Int): Int = u8(offset) or (u8(offset + 1) shl 8)

    private fun ByteArray.u24(offset: Int): Int = u16(offset) or (u8(offset + 2) shl 16)

    private fun ByteArray.u32(offset: Int): Long = u24(offset).toLong() or (u8(offset + 3).toLong() shl 24)
}
