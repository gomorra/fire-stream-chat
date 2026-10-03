package com.firestream.chat.domain.util

import com.firestream.chat.test.WebpFixtures.chunk
import com.firestream.chat.test.WebpFixtures.le32
import com.firestream.chat.test.WebpFixtures.riff
import com.firestream.chat.test.WebpFixtures.sticker
import com.firestream.chat.test.WebpFixtures.vp8
import com.firestream.chat.test.WebpFixtures.vp8l
import com.firestream.chat.test.WebpFixtures.vp8x
import com.firestream.chat.test.WebpFixtures.waExif
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

/** The container walk over hand-built files: what it reads, and what it refuses without throwing. */
class WebpContainerTest {

    @Test
    fun `a lossless file gives its dimensions and is not animated`() {
        val info = WebpContainer.parse(riff(vp8l(512, 384)))!!

        assertEquals(512, info.width)
        assertEquals(384, info.height)
        assertFalse(info.isAnimated)
        assertNull(info.exif)
    }

    @Test
    fun `a lossy file gives its dimensions`() {
        val info = WebpContainer.parse(riff(vp8(320, 240)))!!

        assertEquals(320, info.width)
        assertEquals(240, info.height)
    }

    @Test
    fun `an extended file takes the canvas size, the animation flag and the raw EXIF chunk`() {
        val exif = waExif("""{"emojis":[]}""")

        val info = WebpContainer.parse(riff(vp8x(512, 512, animated = true), chunk("ANMF", ByteArray(24)), chunk("EXIF", exif)))!!

        assertEquals(512, info.width)
        assertEquals(512, info.height)
        assertTrue(info.isAnimated)
        assertArrayEquals(exif, info.exif)
    }

    @Test
    fun `the canvas wins over the first frame's own size`() {
        val info = WebpContainer.parse(riff(vp8x(512, 512), vp8l(100, 50)))!!

        assertEquals(512, info.width)
        assertEquals(512, info.height)
    }

    @Test
    fun `a frame larger than its canvas is refused, so a small canvas cannot hide a huge decode`() {
        assertNull(WebpContainer.parse(riff(vp8x(512, 512), vp8l(16_000, 512))))
        assertNull(WebpContainer.parse(riff(vp8x(512, 512), vp8l(512, 16_000))))
    }

    @Test
    fun `a chunk of odd size is followed by a padding byte`() {
        val odd = chunk("EXIF", ByteArray(7) { 1 })

        val info = WebpContainer.parse(riff(vp8x(64, 64), odd, vp8l(64, 64)))

        assertNotNull("the bitstream chunk after the padded one must be found", info)
        assertEquals(7, info!!.exif!!.size)
    }

    @Test
    fun `bytes after the declared end are ignored`() {
        assertNotNull(WebpContainer.parse(riff(vp8l(8, 8)) + ByteArray(40) { 0x7F }))
    }

    @Test
    fun `a file that is not RIFF or not WEBP is refused`() {
        assertNull(WebpContainer.parse(ByteArray(0)))
        assertNull(WebpContainer.parse("GIF89a plus some more bytes".toByteArray()))
        val wave = riff(vp8l(8, 8)).also { "WAVE".toByteArray().copyInto(it, 8) }
        assertNull(WebpContainer.parse(wave))
    }

    @Test
    fun `a file without image data is refused`() {
        assertNull(WebpContainer.parse(riff(vp8x(512, 512))))
        assertNull(WebpContainer.parse(riff(chunk("EXIF", ByteArray(8)))))
    }

    @Test
    fun `a bitstream with a wrong signature is refused`() {
        assertNull(WebpContainer.parse(riff(chunk("VP8L", byteArrayOf(0x00, 1, 2, 3, 4)))))
        assertNull(WebpContainer.parse(riff(chunk("VP8 ", ByteArray(10)))))
        assertNull(WebpContainer.parse(riff(chunk("VP8X", ByteArray(4)), vp8l(8, 8))))
    }

    @Test
    fun `every truncation of a valid file is refused`() {
        val whole = sticker(exifJson = """{"sticker-pack-id":"p","emojis":["😀"]}""")

        for (length in 0 until whole.size) {
            assertNull("a file cut to $length bytes", WebpContainer.parse(whole.copyOf(length)))
        }
    }

    @Test
    fun `a chunk that claims more bytes than the file has is refused`() {
        val file = riff(vp8l(8, 8), chunk("EXIF", ByteArray(4)))
        val exifSizeOffset = file.size - 8
        le32(-1).copyInto(file, exifSizeOffset)

        assertNull(WebpContainer.parse(file))
    }

    @Test
    fun `corrupted bytes never throw`() {
        val whole = sticker(exifJson = """{"sticker-pack-id":"p","emojis":["😀"]}""", animated = true)
        val random = Random(20261003)

        repeat(5_000) {
            val corrupted = whole.copyOf()
            repeat(1 + random.nextInt(4)) { corrupted[random.nextInt(corrupted.size)] = random.nextInt().toByte() }
            WebpContainer.parse(corrupted)
        }
    }
}
