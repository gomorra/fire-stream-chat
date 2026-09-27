package com.firestream.chat.domain.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TextPreviewTest {

    @Test
    fun `a short UTF-8 file is shown whole and not truncated`() {
        val bytes = "Grüße\nzweite Zeile\n".toByteArray()
        assertEquals(FilePreview.Text("Grüße\nzweite Zeile", truncated = false), TextPreview.decode(bytes, bytes.size.toLong()))
    }

    @Test
    fun `a file longer than the head is marked truncated`() {
        val head = "a".repeat(100).toByteArray()
        assertTrue(TextPreview.decode(head, fileLength = 10_000)!!.truncated)
    }

    @Test
    fun `a character cut in half by the limit is dropped, not shown as a replacement mark`() {
        val full = "abc€".toByteArray() // € is three bytes
        val head = full.copyOf(full.size - 1)
        assertEquals("abc", TextPreview.decode(head, full.size.toLong())!!.text)
    }

    @Test
    fun `Windows line endings and a byte order mark are normalized away`() {
        val bytes = byteArrayOf(0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte()) + "one\r\ntwo\rthree".toByteArray()
        assertEquals("one\ntwo\nthree", TextPreview.decode(bytes, bytes.size.toLong())!!.text)
    }

    @Test
    fun `UTF-16 with a byte order mark decodes`() {
        val bytes = byteArrayOf(0xFF.toByte(), 0xFE.toByte()) + "hi there".toByteArray(Charsets.UTF_16LE)
        assertEquals("hi there", TextPreview.decode(bytes, bytes.size.toLong())!!.text)
    }

    @Test
    fun `a binary file gives no preview`() {
        val bytes = byteArrayOf(0x50, 0x4B, 0x03, 0x04, 0x00, 0x00, 0x08, 0x00) // a zip header
        assertNull(TextPreview.decode(bytes, 4_096))
    }

    @Test
    fun `an empty or blank file gives no preview`() {
        assertNull(TextPreview.decode(ByteArray(0), 0))
        assertNull(TextPreview.decode("  \n\n ".toByteArray(), 5))
    }

    @Test
    fun `Latin-1 text that is not UTF-8 gives no preview rather than a page of marks`() {
        val bytes = ByteArray(200) { 0xE9.toByte() } // é in Latin-1, invalid alone in UTF-8
        assertNull(TextPreview.decode(bytes, bytes.size.toLong()))
    }
}
