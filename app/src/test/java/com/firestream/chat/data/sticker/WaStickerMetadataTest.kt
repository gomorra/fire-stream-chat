package com.firestream.chat.data.sticker

import com.firestream.chat.test.WebpFixtures.le32
import com.firestream.chat.test.WebpFixtures.waExif
import com.firestream.chat.test.WebpFixtures.waJson
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** The WhatsApp pack fields out of a raw EXIF chunk: present, partly present, absent and broken. */
class WaStickerMetadataTest {

    @Test
    fun `the pack fields and emojis are read from the WhatsApp tag`() {
        val exif = waExif(waJson("com.pack.cats", "Cats", "Ana", listOf("😺", "❤️")))

        val metadata = WaStickerMetadata.parse(exif)

        assertEquals(WaStickerMetadata("com.pack.cats", "Cats", "Ana", listOf("😺", "❤️")), metadata)
    }

    @Test
    fun `no chunk is no metadata`() {
        assertNull(WaStickerMetadata.parse(null))
        assertNull(WaStickerMetadata.parse(ByteArray(0)))
    }

    @Test
    fun `an EXIF chunk without the WhatsApp tag is no metadata`() {
        val orientationOnly = byteArrayOf(
            0x49, 0x49, 0x2A, 0x00, 0x08, 0x00, 0x00, 0x00, 0x01, 0x00,
            0x12, 0x01, 0x03, 0x00, 0x01, 0x00, 0x00, 0x00, 0x01, 0x00, 0x00, 0x00,
        )

        assertNull(WaStickerMetadata.parse(orientationOnly))
    }

    @Test
    fun `fields that are missing stay null`() {
        assertEquals(
            WaStickerMetadata(null, null, null, listOf("🔥")),
            WaStickerMetadata.parse(waExif(waJson(null, null, emojis = listOf("🔥")))),
        )
        assertEquals(
            WaStickerMetadata("id", null, null, emptyList()),
            WaStickerMetadata.parse(waExif("""{"sticker-pack-id":"id"}""")),
        )
    }

    @Test
    fun `an object with nothing in it is no metadata`() {
        assertNull(WaStickerMetadata.parse(waExif("""{"emojis":[]}""")))
        assertNull(WaStickerMetadata.parse(waExif("""{"sticker-pack-name":"   "}""")))
    }

    @Test
    fun `fields of the wrong type are ignored`() {
        val exif = waExif("""{"sticker-pack-id":7,"sticker-pack-name":null,"sticker-pack-publisher":["x"],"emojis":["🙂",3,null,""]}""")

        assertEquals(WaStickerMetadata(null, null, null, listOf("🙂")), WaStickerMetadata.parse(exif))
    }

    @Test
    fun `an Exif prefix in front of the TIFF header is skipped`() {
        val exif = "Exif".toByteArray() + byteArrayOf(0, 0) + waExif(waJson("id", "Name"))

        assertEquals("Name", WaStickerMetadata.parse(exif)?.packName)
    }

    @Test
    fun `a big-endian TIFF is read`() {
        val json = waJson("id", "Big").toByteArray()
        val exif = byteArrayOf(0x4D, 0x4D, 0x00, 0x2A, 0x00, 0x00, 0x00, 0x08, 0x00, 0x01, 0x57, 0x41, 0x00, 0x07) +
            le32(json.size).reversedArray() + le32(0x16).reversedArray() + json

        assertEquals("Big", WaStickerMetadata.parse(exif)?.packName)
    }

    @Test
    fun `a writer with a broken TIFF header still yields the JSON object`() {
        val exif = byteArrayOf(1, 2, 3, 4, 5, 6, 7, 8, 9) + waJson("id", "Loose").toByteArray()

        assertEquals("Loose", WaStickerMetadata.parse(exif)?.packName)
    }

    @Test
    fun `an offset or a length past the chunk is not followed`() {
        val exif = waExif(waJson("id", "Name"))
        val longCount = exif.copyOf().also { le32(1_000_000).copyInto(it, 14) }
        val farOffset = exif.copyOf().also { le32(-1).copyInto(it, 18) }

        // Neither throws; the braces in the chunk are still found.
        assertEquals("Name", WaStickerMetadata.parse(longCount)?.packName)
        assertEquals("Name", WaStickerMetadata.parse(farOffset)?.packName)
    }

    @Test
    fun `every truncation of a chunk is read without throwing`() {
        val exif = waExif(waJson("id", "Name", "Pub", listOf("😀")))

        for (length in 0 until exif.size) WaStickerMetadata.parse(exif.copyOf(length))
    }

    @Test
    fun `text is trimmed, stripped of control characters and capped`() {
        val long = "x".repeat(500)
        val exif = waExif("""{"sticker-pack-id":"  a\u0000b\n ","sticker-pack-name":"$long"}""")

        val metadata = WaStickerMetadata.parse(exif)!!

        assertEquals("ab", metadata.packId)
        assertEquals(128, metadata.packName!!.length)
    }

    @Test
    fun `emojis are de-duplicated and capped at eight`() {
        val many = (1..20).map { "e$it" } + "e1"

        val metadata = WaStickerMetadata.parse(waExif(waJson(null, null, emojis = many)))!!

        assertEquals((1..8).map { "e$it" }, metadata.emojis)
    }
}
