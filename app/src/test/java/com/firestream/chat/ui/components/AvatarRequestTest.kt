package com.firestream.chat.ui.components

import android.app.Application
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [31], application = Application::class)
class AvatarRequestTest {

    private val context get() = RuntimeEnvironment.getApplication()

    @Test
    fun `avatar request decodes through ScaledImageDecoder`() {
        // Avatars are full camera originals shown at 40-96dp. Coil's default
        // BitmapFactory decode reaches that size by a heavy power-of-two
        // subsample, which returns a black bitmap for these images.
        val request = buildAvatarRequest(context, localAvatarPath = null, avatarUrl = URL)!!

        assertTrue(request.decoderFactory is ScaledImageDecoder.Factory)
    }

    @Test
    fun `avatar request keeps the stable avatar cache key`() {
        val request = buildAvatarRequest(context, localAvatarPath = null, avatarUrl = URL)!!

        assertEquals(URL, request.memoryCacheKey?.key)
        assertEquals(URL, request.diskCacheKey)
    }

    @Test
    fun `no request without an image`() {
        assertNull(buildAvatarRequest(context, localAvatarPath = null, avatarUrl = null))
    }

    private companion object {
        const val URL = "https://cdn.example.com/avatar.jpg?token=abc"
    }
}
