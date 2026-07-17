package com.firestream.chat.data.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * JVM tests for [VideoTranscoder]'s pure scale/rotation logic. The Transformer /
 * MediaMetadataRetriever paths are device-only and intentionally not exercised here.
 */
class VideoTranscoderLogicTest {

    // --- displayDimensions: rotation swap ---

    @Test
    fun `displayDimensions no rotation keeps orientation`() {
        assertEquals(1920 to 1080, displayDimensions(1920, 1080, 0))
    }

    @Test
    fun `displayDimensions 90 swaps width and height`() {
        assertEquals(1080 to 1920, displayDimensions(1920, 1080, 90))
    }

    @Test
    fun `displayDimensions 180 keeps orientation`() {
        assertEquals(1920 to 1080, displayDimensions(1920, 1080, 180))
    }

    @Test
    fun `displayDimensions 270 swaps width and height`() {
        assertEquals(1080 to 1920, displayDimensions(1920, 1080, 270))
    }

    @Test
    fun `displayDimensions normalizes out-of-range rotation`() {
        // 450 % 360 == 90 -> swap
        assertEquals(1080 to 1920, displayDimensions(1920, 1080, 450))
        // -90 normalizes to 270 -> swap
        assertEquals(1080 to 1920, displayDimensions(1920, 1080, -90))
    }

    // --- needsDownscale ---

    @Test
    fun `needsDownscale true when taller than target`() {
        assertTrue(needsDownscale(1080, 720))
    }

    @Test
    fun `needsDownscale false when shorter than target (no upscale)`() {
        assertFalse(needsDownscale(480, 720))
    }

    @Test
    fun `needsDownscale false at exact target`() {
        assertFalse(needsDownscale(720, 720))
    }

    // --- outputDimensions ---

    @Test
    fun `outputDimensions downscales proportionally to target height`() {
        // 1920x1080 -> 720p : 1280x720
        assertEquals(1280 to 720, outputDimensions(1920, 1080, 720))
    }

    @Test
    fun `outputDimensions passthrough when at or below target (no upscale)`() {
        // 480p source at 720 setting is not upscaled
        assertEquals(640 to 480, outputDimensions(640, 480, 720))
        // exact target passthrough
        assertEquals(1280 to 720, outputDimensions(1280, 720, 720))
    }

    @Test
    fun `outputDimensions rounds odd width down to even`() {
        // 1080x1920 portrait -> target 720 : width = 720/1920*1080 = 405 -> even 404
        assertEquals(404 to 720, outputDimensions(1080, 1920, 720))
    }

    @Test
    fun `outputDimensions width is always even`() {
        val (w, h) = outputDimensions(1234, 1000, 480)
        assertEquals(0, w % 2)
        assertEquals(0, h % 2)
    }

    // --- constants sanity ---

    @Test
    fun `limit constants are sane`() {
        assertEquals(180_000L, VideoTranscoder.MAX_VIDEO_DURATION_MS)
        assertEquals(100L * 1024 * 1024, VideoTranscoder.MAX_VIDEO_SOURCE_BYTES)
    }
}
