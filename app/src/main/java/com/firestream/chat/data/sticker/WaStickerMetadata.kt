package com.firestream.chat.data.sticker

import org.json.JSONObject

/**
 * The pack a WhatsApp sticker says it belongs to, and the emojis it is tagged with.
 *
 * WhatsApp writes these as a JSON object into the file's EXIF chunk, under the
 * private TIFF tag `0x5741`. Any field may be missing, and so may the whole chunk.
 */
data class WaStickerMetadata(
    val packId: String?,
    val packName: String?,
    val publisher: String?,
    val emojis: List<String>,
) {
    companion object {
        private const val TAG_WHATSAPP = 0x5741
        private const val IFD_ENTRY = 12
        private const val MAX_JSON_BYTES = 64 * 1024
        private const val MAX_EMOJIS = 8
        private const val MAX_EMOJI_CHARS = 32
        private val EXIF_PREFIX = byteArrayOf('E'.code.toByte(), 'x'.code.toByte(), 'i'.code.toByte(), 'f'.code.toByte(), 0, 0)

        /**
         * The metadata in a WebP's raw EXIF chunk, or `null` when [exif] is absent
         * or holds none. The chunk is untrusted: offsets are checked against its
         * size, the text is capped, and nothing here throws.
         */
        fun parse(exif: ByteArray?): WaStickerMetadata? {
            if (exif == null) return null
            val tiff = if (exif.startsWith(EXIF_PREFIX)) exif.copyOfRange(EXIF_PREFIX.size, exif.size) else exif
            val root = taggedJson(tiff)?.let(::parseObject) ?: bracedJson(tiff)?.let(::parseObject) ?: return null
            val emojis = root.optJSONArray("emojis")?.let { array ->
                (0 until array.length())
                    .mapNotNull { (array.opt(it) as? String)?.trim() }
                    .filter { it.isNotEmpty() && it.length <= MAX_EMOJI_CHARS }
                    .distinct()
                    .take(MAX_EMOJIS)
            }.orEmpty()
            val metadata = WaStickerMetadata(
                packId = root.text("sticker-pack-id"),
                packName = root.text("sticker-pack-name"),
                publisher = root.text("sticker-pack-publisher"),
                emojis = emojis,
            )
            return metadata.takeUnless { it.packId == null && it.packName == null && it.publisher == null && emojis.isEmpty() }
        }

        /** The payload of the WhatsApp tag, found by walking the first TIFF directory. */
        private fun taggedJson(tiff: ByteArray): String? {
            if (tiff.size < 8) return null
            val littleEndian = when {
                tiff[0] == 'I'.code.toByte() && tiff[1] == 'I'.code.toByte() -> true
                tiff[0] == 'M'.code.toByte() && tiff[1] == 'M'.code.toByte() -> false
                else -> return null
            }
            val directory = tiff.u32(4, littleEndian)
            if (directory + 2 > tiff.size) return null
            val entries = tiff.u16(directory.toInt(), littleEndian)
            for (index in 0 until entries) {
                val entry = directory + 2 + index.toLong() * IFD_ENTRY
                if (entry + IFD_ENTRY > tiff.size) return null
                if (tiff.u16(entry.toInt(), littleEndian) != TAG_WHATSAPP) continue
                val count = tiff.u32(entry.toInt() + 4, littleEndian)
                val offset = tiff.u32(entry.toInt() + 8, littleEndian)
                if (count > MAX_JSON_BYTES || offset + count > tiff.size) return null
                return String(tiff, offset.toInt(), count.toInt(), Charsets.UTF_8)
            }
            return null
        }

        /** The outermost `{…}` in the chunk, for writers that get the TIFF header wrong. */
        private fun bracedJson(bytes: ByteArray): String? {
            val open = bytes.indexOf('{'.code.toByte())
            val close = bytes.lastIndexOf('}'.code.toByte())
            if (open < 0 || close <= open || close - open > MAX_JSON_BYTES) return null
            return String(bytes, open, close - open + 1, Charsets.UTF_8)
        }

        /** A string field, cleaned, and `null` when it is missing, not a string or empty. */
        private fun JSONObject.text(name: String): String? = (opt(name) as? String)?.let(::cleanStickerText)

        private fun parseObject(json: String): JSONObject? = runCatching { JSONObject(json) }.getOrNull()

        private fun ByteArray.startsWith(prefix: ByteArray): Boolean =
            size >= prefix.size && prefix.indices.all { this[it] == prefix[it] }

        private fun ByteArray.u8(offset: Int): Int = this[offset].toInt() and 0xFF

        private fun ByteArray.u16(offset: Int, littleEndian: Boolean): Int =
            if (littleEndian) u8(offset) or (u8(offset + 1) shl 8) else (u8(offset) shl 8) or u8(offset + 1)

        private fun ByteArray.u32(offset: Int, littleEndian: Boolean): Long =
            if (littleEndian) {
                u16(offset, true).toLong() or (u16(offset + 2, true).toLong() shl 16)
            } else {
                (u16(offset, false).toLong() shl 16) or u16(offset + 2, false).toLong()
            }
    }
}
