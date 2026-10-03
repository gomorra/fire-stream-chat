package com.firestream.chat.test

import java.io.ByteArrayOutputStream
import java.nio.charset.Charset
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * Hand-built WebP containers for the sticker tests. Nothing here is decodable as
 * an image: the library reads the container, never the pixels, and so do these.
 */
object WebpFixtures {

    /** One RIFF chunk: tag, little-endian size, payload, and the padding byte an odd size needs. */
    fun chunk(tag: String, payload: ByteArray): ByteArray =
        tag.toByteArray(Charsets.US_ASCII) + le32(payload.size) + payload + ByteArray(payload.size and 1)

    /** A whole file around [chunks]. */
    fun riff(vararg chunks: ByteArray): ByteArray {
        val body = "WEBP".toByteArray(Charsets.US_ASCII) + chunks.fold(ByteArray(0)) { acc, chunk -> acc + chunk }
        return "RIFF".toByteArray(Charsets.US_ASCII) + le32(body.size) + body
    }

    /** A lossless bitstream header for [width] × [height], followed by [filler]. */
    fun vp8l(width: Int, height: Int, filler: ByteArray = ByteArray(0)): ByteArray {
        val bits = (width - 1) or ((height - 1) shl 14)
        return chunk("VP8L", byteArrayOf(0x2F) + le32(bits) + filler)
    }

    /** A lossy keyframe header for [width] × [height]. */
    fun vp8(width: Int, height: Int): ByteArray =
        chunk("VP8 ", byteArrayOf(0, 0, 0, 0x9D.toByte(), 0x01, 0x2A) + le16(width) + le16(height))

    fun vp8x(width: Int, height: Int, animated: Boolean = false): ByteArray =
        chunk("VP8X", byteArrayOf(if (animated) 0x02 else 0x00, 0, 0, 0) + le24(width - 1) + le24(height - 1))

    /** The EXIF payload WhatsApp writes: a little-endian TIFF with the one tag `0x5741` pointing at [json]. */
    fun waExif(json: String): ByteArray {
        val text = json.toByteArray(Charsets.UTF_8)
        return byteArrayOf(0x49, 0x49, 0x2A, 0x00, 0x08, 0x00, 0x00, 0x00, 0x01, 0x00, 0x41, 0x57, 0x07, 0x00) +
            le32(text.size) + le32(0x16) + text
    }

    fun waJson(packId: String?, packName: String?, publisher: String? = null, emojis: List<String> = emptyList()): String =
        buildList {
            packId?.let { add("\"sticker-pack-id\":\"$it\"") }
            packName?.let { add("\"sticker-pack-name\":\"$it\"") }
            publisher?.let { add("\"sticker-pack-publisher\":\"$it\"") }
            add("\"emojis\":[${emojis.joinToString(",") { "\"$it\"" }}]")
        }.joinToString(",", "{", "}")

    /**
     * A 512 × 512 sticker. [seed] makes the bytes, and so the id, differ.
     * [exifJson] adds WhatsApp metadata. [fillerBytes] pads the file to a size.
     */
    fun sticker(seed: Int = 0, exifJson: String? = null, animated: Boolean = false, fillerBytes: Int = 0): ByteArray {
        val filler = le32(seed) + ByteArray(fillerBytes)
        val chunks = buildList {
            add(vp8x(512, 512, animated))
            add(vp8l(512, 512, filler))
            exifJson?.let { add(chunk("EXIF", waExif(it))) }
        }
        return riff(*chunks.toTypedArray())
    }

    /** A zip of [entries], name to content, in that order. */
    fun zip(vararg entries: Pair<String, ByteArray>): ByteArray = zip(Charsets.UTF_8, *entries)

    /** A zip whose entry names are written in [nameCharset], as an old Windows tool would. */
    fun zip(nameCharset: Charset, vararg entries: Pair<String, ByteArray>): ByteArray {
        val out = ByteArrayOutputStream()
        ZipOutputStream(out, nameCharset).use { zip ->
            entries.forEach { (name, content) ->
                zip.putNextEntry(ZipEntry(name))
                zip.write(content)
                zip.closeEntry()
            }
        }
        return out.toByteArray()
    }

    fun le16(value: Int): ByteArray = byteArrayOf(value.toByte(), (value shr 8).toByte())

    fun le24(value: Int): ByteArray = le16(value) + byteArrayOf((value shr 16).toByte())

    fun le32(value: Int): ByteArray = le24(value) + byteArrayOf((value shr 24).toByte())
}
