package com.firestream.chat.ui.chat.imageedit

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The size label under each row of the HD sheet.
 *
 * Kept under test through the move off `ImageSizeEstimator`: the arithmetic that
 * produces the number now lives in `ImageEditRasterizer`, but the *rendering* of
 * it is still this file's, and both boundaries below have been wrong in gallery
 * apps before — a 900-byte thumbnail reading "0 KB", and a 1.04 MB photo reading
 * "1040 KB".
 */
class HdQualitySheetTest {

    @Test
    fun `bytes render as decimal KB below a megabyte`() {
        assertEquals("340 KB", formatBytes(340_000))
        assertEquals("999 KB", formatBytes(999_400))
    }

    @Test
    fun `a megabyte and above renders with one decimal place`() {
        assertEquals("1.0 MB", formatBytes(1_000_000))
        assertEquals("3.9 MB", formatBytes(3_900_000))
    }

    @Test
    fun `a sub-KB result still reads as 1 KB rather than 0`() {
        // Rounding an 800-byte image down to "0 KB" reads as a failure, not as a
        // small file.
        assertEquals("1 KB", formatBytes(800))
        assertEquals("1 KB", formatBytes(1))
    }

    @Test
    fun `a size we could not estimate renders as nothing at all`() {
        // Blank, so the caller drops the line rather than printing a confident
        // number for a header it could not read.
        assertEquals("", formatBytes(0))
        assertEquals("", formatBytes(-1))
    }
}
