package com.firestream.chat.ui.chat.imageedit

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.util.Locale

/**
 * The arithmetic behind the HD sheet's "about 340 KB".
 *
 * It is an estimate on purpose — measuring exactly costs a second full
 * decode-and-encode per image — so these tests pin the *shape* of the answer
 * (HD is bigger, standard caps the long edge, a PNG's bytes-per-pixel does not
 * run away with the number) rather than exact byte counts.
 */
class ImageSizeEstimatorTest {

    private lateinit var previousLocale: Locale

    /**
     * The MB row is formatted for the reader, so its decimal separator follows
     * the device locale. Pin one here so the assertion is about the number.
     */
    @Before
    fun setUp() {
        previousLocale = Locale.getDefault()
        Locale.setDefault(Locale.US)
    }

    @After
    fun tearDown() = Locale.setDefault(previousLocale)

    /** A 12 MP 4:3 camera photo at ~2.5 MB — the case the sheet is really for. */
    private val cameraPhoto = ImageProbe(width = 4000, height = 3000, sourceBytes = 2_500_000)

    @Test
    fun `standard caps the long edge at 1600, HD keeps the source resolution`() {
        assertEquals(1600 to 1200, ImageSizeEstimator.estimateDimensions(cameraPhoto, hd = false))
        assertEquals(4000 to 3000, ImageSizeEstimator.estimateDimensions(cameraPhoto, hd = true))
    }

    @Test
    fun `an image already under the cap is not upscaled by either row`() {
        val small = ImageProbe(width = 800, height = 600, sourceBytes = 90_000)
        assertEquals(800 to 600, ImageSizeEstimator.estimateDimensions(small, hd = false))
        assertEquals(800 to 600, ImageSizeEstimator.estimateDimensions(small, hd = true))
    }

    @Test
    fun `HD is substantially larger than standard for a full-size photo`() {
        val standard = ImageSizeEstimator.estimateBytes(cameraPhoto, hd = false)
        val hd = ImageSizeEstimator.estimateBytes(cameraPhoto, hd = true)
        assertTrue("HD ($hd) should dwarf standard ($standard)", hd > standard * 5)
    }

    @Test
    fun `a camera photo estimates in the few-hundred-KB range at standard quality`() {
        // Guards the shape of the answer, not the constant: a bug that dropped
        // the downscale would land in megabytes, one that squared it in bytes.
        val standard = ImageSizeEstimator.estimateBytes(cameraPhoto, hd = false)
        assertTrue("got $standard", standard in 150_000..700_000)
    }

    @Test
    fun `a lossless source does not inflate the estimate without bound`() {
        // A PNG screenshot's bytes-per-pixel is many times a JPEG's; clamping is
        // what keeps the sheet from promising a 40 MB send.
        val png = ImageProbe(width = 1170, height = 2532, sourceBytes = 6_000_000)
        val jpeg = ImageProbe(width = 1170, height = 2532, sourceBytes = 900_000)
        val pngHd = ImageSizeEstimator.estimateBytes(png, hd = true)
        val jpegHd = ImageSizeEstimator.estimateBytes(jpeg, hd = true)
        assertTrue("clamped ($pngHd) should stay within ~2x the jpeg ($jpegHd)", pngHd < jpegHd * 3)
    }

    @Test
    fun `an unknown source size still produces a usable estimate`() {
        val unknown = ImageProbe(width = 4000, height = 3000, sourceBytes = 0)
        assertTrue(ImageSizeEstimator.estimateBytes(unknown, hd = false) > 0)
        assertTrue(ImageSizeEstimator.estimateBytes(unknown, hd = true) > 0)
    }

    @Test
    fun `a header we could not read estimates nothing rather than zero bytes`() {
        val broken = ImageProbe(width = 0, height = 0, sourceBytes = 0)
        assertEquals(0L, ImageSizeEstimator.estimateBytes(broken, hd = false))
        assertEquals("", ImageSizeEstimator.formatBytes(ImageSizeEstimator.estimateBytes(broken, hd = false)))
    }

    @Test
    fun `formatBytes switches from KB to one-decimal MB at a megabyte`() {
        assertEquals("340 KB", ImageSizeEstimator.formatBytes(340_000))
        assertEquals("999 KB", ImageSizeEstimator.formatBytes(999_400))
        assertEquals("3.9 MB", ImageSizeEstimator.formatBytes(3_900_000))
        assertEquals("", ImageSizeEstimator.formatBytes(0))
    }

    @Test
    fun `a sub-KB result still reads as 1 KB rather than 0`() {
        assertEquals("1 KB", ImageSizeEstimator.formatBytes(400))
    }
}
