package com.firestream.chat.ui.chat

import android.graphics.Bitmap
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeLeft
import androidx.compose.ui.test.swipeRight
import com.firestream.chat.ui.chat.imageedit.CropAspect
import com.firestream.chat.ui.chat.imageedit.CropRect
import com.firestream.chat.ui.chat.imageedit.PendingCrop
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

/**
 * The crop each page of the fullscreen gallery hands Edit.
 *
 * Two real JPEGs of different shapes, 400 × 100 and 300 × 200, loaded through
 * Coil as the viewer loads them, because a shape from the crop pill is cut only
 * once the photo's decoded size is known. The 1:1 square of the first is its
 * middle quarter across; of the second, its middle two thirds. Coil may decode
 * at a smaller size, so the tests compare frames and size ratios, never pixels.
 *
 * The second page is preloaded beside the first, so its size arrives while it
 * is off screen. That size has to survive the swipe that brings it on screen;
 * its zoom and shape must not.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [31], application = android.app.Application::class)
class FullscreenImagePagerCropTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    @get:Rule
    val folder = TemporaryFolder()

    private var page = -1
    private var edited: Pair<FullscreenMediaItem, PendingCrop>? = null

    private fun photo(name: String, width: Int, height: Int): FullscreenMediaItem {
        val file = File(folder.root, "$name.jpg")
        file.outputStream().use { out ->
            Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888).compress(Bitmap.CompressFormat.JPEG, 90, out)
        }
        return FullscreenMediaItem(imageUrl = null, localUri = file.path, messageId = name)
    }

    private fun setContent() {
        val items = listOf(photo("wide", 400, 100), photo("landscape", 300, 200))
        composeTestRule.setContent {
            MaterialTheme {
                FullscreenImagePager(
                    items = items,
                    initialIndex = 0,
                    onDismiss = {},
                    onPageChanged = { page = it },
                    onEdit = { item, crop -> edited = item to crop },
                )
            }
        }
        awaitBothPhotos()
    }

    /** Waits until both pages have decoded, the off-screen one included. */
    private fun awaitBothPhotos() {
        val deadline = System.currentTimeMillis() + 15_000
        while (composeTestRule.onAllNodesWithContentDescription(IMAGE).fetchSemanticsNodes().size < 2) {
            check(System.currentTimeMillis() < deadline) { "the photos never loaded" }
            Thread.sleep(50)
            composeTestRule.waitForIdle()
        }
    }

    private fun swipeTo(expected: Int, swipe: () -> Unit) {
        swipe()
        composeTestRule.waitForIdle()
        assertEquals("the page on screen", expected, page)
    }

    private fun swipeToSecond() = swipeTo(1) { composeTestRule.onRoot().performTouchInput { swipeLeft() } }

    private fun swipeToFirst() = swipeTo(0) { composeTestRule.onRoot().performTouchInput { swipeRight() } }

    /** Taps the crop pill round until it reads [label]. */
    private fun chooseShape(label: String) {
        repeat(CropAspect.entries.size) {
            if (composeTestRule.onAllNodesWithText(label).fetchSemanticsNodes().isNotEmpty()) return
            composeTestRule.onNodeWithContentDescription("Crop shape").performClick()
            composeTestRule.waitForIdle()
        }
        error("the crop pill never reached $label")
    }

    private fun edit(): Pair<FullscreenMediaItem, PendingCrop> {
        composeTestRule.onNodeWithContentDescription("Edit").performClick()
        composeTestRule.waitForIdle()
        return edited ?: error("edit was never handed a crop")
    }

    private fun assertSized(crop: PendingCrop) {
        assertTrue("decoded size ${crop.imageWidth} × ${crop.imageHeight}", crop.imageWidth > 0 && crop.imageHeight > 0)
    }

    private fun assertRatio(expected: Float, crop: PendingCrop) {
        assertSized(crop)
        assertEquals("the photo's shape", expected, crop.imageWidth.toFloat() / crop.imageHeight, 0.02f)
    }

    private fun assertFrame(left: Float, top: Float, right: Float, bottom: Float, actual: CropRect) {
        assertEquals("left", left, actual.left, 0.02f)
        assertEquals("top", top, actual.top, 0.02f)
        assertEquals("right", right, actual.right, 0.02f)
        assertEquals("bottom", bottom, actual.bottom, 0.02f)
    }

    @Test
    fun `a shape picked on the first page is cut from that photo`() {
        setContent()

        chooseShape("1:1")
        val (item, crop) = edit()

        assertEquals("wide", item.messageId)
        assertSized(crop)
        assertFrame(0.375f, 0f, 0.625f, 1f, crop.frame)
    }

    @Test
    fun `a shape picked on a page swiped to before any gesture is cut from that photo`() {
        setContent()
        swipeToSecond()

        chooseShape("1:1")
        val (item, crop) = edit()

        assertEquals("landscape", item.messageId)
        assertSized(crop)
        assertFrame(1f / 6f, 0f, 5f / 6f, 1f, crop.frame)
    }

    @Test
    fun `a page swiped away and back starts over at Free and 1x`() {
        setContent()
        chooseShape("1:1")
        swipeToSecond()

        swipeToFirst()

        composeTestRule.onNodeWithText("Free").assertExists()
        val (item, crop) = edit()
        assertEquals("wide", item.messageId)
        assertEquals(CropAspect.FREE, crop.aspect)
        assertEquals(CropRect.Full, crop.viewport)
        assertRatio(4f, crop)
    }

    @Test
    fun `edit on a page swiped to carries nothing from the page it left`() {
        // The first page is left on 16:9. The page swiped to is handed its own
        // photo's size, the whole photo and no shape. A zoomed page cannot be
        // swiped away, so the shape is all a page could carry.
        setContent()
        chooseShape("16:9")
        swipeToSecond()

        val (item, crop) = edit()

        assertEquals("landscape", item.messageId)
        assertEquals(CropAspect.FREE, crop.aspect)
        assertEquals(CropRect.Full, crop.viewport)
        assertRatio(1.5f, crop)
    }

    private companion object {
        const val IMAGE = "Full screen image"
    }
}
