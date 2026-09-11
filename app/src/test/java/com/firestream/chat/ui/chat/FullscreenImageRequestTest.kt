package com.firestream.chat.ui.chat

import android.content.Context
import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import coil.memory.MemoryCache
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

/**
 * The hand-off from a fullscreen viewer to the send preview draws the same
 * bitmap on both sides: the viewer files what it shows under a key it can
 * name, and the preview names that key as its placeholder, so its first frame
 * is the photo rather than black while the edit-cache copy decodes.
 *
 * Regression for the "pops away and comes back" the first hardware pass of
 * Phase 6 found: the preview's model is a *different file* from the viewer's
 * (the copy), so under Coil's default keys nothing was cached for it.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29], application = android.app.Application::class)
class FullscreenImageRequestTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private val context: Context get() = ApplicationProvider.getApplicationContext()

    @Test
    fun `the viewer shows a readable local file over the remote url`() {
        val local = tmp.newFile("m1.jpg")

        assertEquals(local, fullscreenImageModel("https://example.com/p.jpg", local.path))
    }

    @Test
    fun `the viewer falls back to the url when the file is missing, and to nothing when both are`() {
        assertEquals("https://example.com/p.jpg", fullscreenImageModel("https://example.com/p.jpg", "/nowhere.jpg"))
        assertNull(fullscreenImageModel("  ", "/nowhere.jpg"))
    }

    @Test
    fun `the viewer files its bitmap under the key the preview will ask for`() {
        val local = tmp.newFile("m1.jpg")

        val request = fullscreenImageRequest(context, local)

        assertEquals(MemoryCache.Key(fullscreenImageCacheKey(local)), request.memoryCacheKey)
    }

    @Test
    fun `the preview draws a viewer-sourced photo from the viewer's bitmap first`() {
        val key = fullscreenImageCacheKey(File("/pictures/m1.jpg"))
        val item = PendingMedia(
            originalUri = Uri.parse("file:///edits/sources/source_1.jpg"),
            mimeType = "image/jpeg",
            originalMemoryCacheKey = key,
        )

        val request = previewImageRequest(context, item)

        assertEquals(item.uri, request.data)
        assertEquals(MemoryCache.Key(key), request.placeholderMemoryCacheKey)
    }

    @Test
    fun `an edited step never shows the untouched original underneath it`() {
        val item = PendingMedia(
            originalUri = Uri.parse("file:///edits/sources/source_1.jpg"),
            mimeType = "image/jpeg",
            editHistory = listOf("file:///edits/step1.jpg"),
            editCursor = 1,
            originalMemoryCacheKey = fullscreenImageCacheKey(File("/pictures/m1.jpg")),
        )

        assertNull(previewImageRequest(context, item).placeholderMemoryCacheKey)
    }

    @Test
    fun `a gallery pick has nothing cached to start from`() {
        val item = PendingMedia(originalUri = Uri.parse("content://pick/1"), mimeType = "image/jpeg")

        assertNull(previewImageRequest(context, item).placeholderMemoryCacheKey)
    }
}
