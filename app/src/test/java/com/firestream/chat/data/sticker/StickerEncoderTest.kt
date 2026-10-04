package com.firestream.chat.data.sticker

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** The size loop. The WebP encoder itself is the platform's and runs on a device only. */
class StickerEncoderTest {

    /** A stand-in encoder whose output is `quality` kilobytes long. */
    private val tried = mutableListOf<Int>()
    private fun kilobytesPerQuality(quality: Int): ByteArray {
        tried += quality
        return ByteArray(quality * 1024)
    }

    @Test
    fun `the first quality that fits is taken and nothing lower is tried`() {
        val bytes = StickerEncoder.firstUnder(60 * 1024, listOf(90, 80, 60, 40), ::kilobytesPerQuality)

        assertEquals(60 * 1024, bytes?.size)
        assertEquals(listOf(90, 80, 60), tried)
    }

    @Test
    fun `a sticker that fits at once is encoded once`() {
        StickerEncoder.firstUnder(StickerEncoder.MAX_BYTES, StickerEncoder.QUALITIES, ::kilobytesPerQuality)

        assertEquals(listOf(90), tried)
    }

    @Test
    fun `a sticker too large at every quality is refused`() {
        assertNull(StickerEncoder.firstUnder(5 * 1024, StickerEncoder.QUALITIES, ::kilobytesPerQuality))
        assertEquals(StickerEncoder.QUALITIES, tried)
    }

    @Test
    fun `an encoder that writes nothing does not count as a fit`() {
        val bytes = StickerEncoder.firstUnder(100, listOf(90, 80)) { quality -> if (quality == 90) ByteArray(0) else ByteArray(7) }

        assertEquals(7, bytes?.size)
    }

    @Test
    fun `the qualities go down, and the limit is a hundred kilobytes`() {
        assertEquals(StickerEncoder.QUALITIES.sortedDescending(), StickerEncoder.QUALITIES)
        assertEquals(102_400, StickerEncoder.MAX_BYTES)
    }
}
