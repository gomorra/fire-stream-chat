package com.firestream.chat.ui.chat

import android.graphics.Bitmap
import android.net.Uri
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.hasStateDescription
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.doubleClick
import com.firestream.chat.domain.util.RasterOp
import com.firestream.chat.ui.chat.imageedit.ImageEditServices
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
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
 * A zoom on the send preview is the crop that gets sent.
 *
 * The photo is a real 400 × 100 JPEG on disk, loaded through Coil as the screen
 * loads it, because the crop is normalized to the photo's *displayed* size and
 * a fake that never reported one would leave nothing to zoom into. A double-tap
 * is the zoom: 3x about the centre, which on a photo four times wider than tall
 * is the middle third of its width and all of its height, whatever the box.
 *
 * The flatten is verified at the seam every editor step goes through — the
 * `rasterize` service and what `onSend` is handed — rather than by decoding
 * anything: the arithmetic behind the frame has its own JVM test, and this one
 * is about *when* the zoom becomes pixels, and that nothing is sent without it.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [29], application = android.app.Application::class)
class ImagePreviewScreenZoomTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    @get:Rule
    val folder = TemporaryFolder()

    private val flattened = Uri.parse("file:///edits/zoom-step.jpg")

    private lateinit var pick: Uri
    private var rasterizedSource: Uri? = null
    private var rasterizedOps: List<RasterOp>? = null
    private var rasterizeCalls = 0
    private var rasterizeFails = false
    private var sent: List<PendingMedia>? = null

    private fun photo(): PendingMedia {
        val file = File(folder.root, "wide.jpg")
        file.outputStream().use { out ->
            Bitmap.createBitmap(400, 100, Bitmap.Config.ARGB_8888).compress(Bitmap.CompressFormat.JPEG, 90, out)
        }
        pick = Uri.fromFile(file)
        return PendingMedia(originalUri = pick, mimeType = "image/jpeg")
    }

    private fun setContent(restoration: StateRestorationTester? = null) {
        val items = listOf(photo())
        val content: @Composable () -> Unit = {
            MaterialTheme {
                ImagePreviewScreen(
                    items = items,
                    recentEmojis = emptyList(),
                    defaultIsHd = false,
                    onEmojiUsed = {},
                    onSend = { sent = it },
                    onDownload = {},
                    onDismiss = {},
                    edit = ImageEditServices(
                        rasterize = { source, ops, _ ->
                            rasterizeCalls++
                            rasterizedSource = source
                            rasterizedOps = ops
                            if (rasterizeFails) null else flattened
                        },
                    ),
                )
            }
        }
        if (restoration != null) restoration.setContent(content) else composeTestRule.setContent(content)
    }

    /**
     * Double-taps the page until it reports itself zoomed.
     *
     * The first tap may land before Coil has decoded the photo; a zoom made
     * then is put back to 1x when the size arrives, exactly as on a device, so
     * the loop keeps tapping. The first tap *after* the size arrives is 3x, and
     * the page reports it on the very next idle, so no tap can stack to 6x.
     */
    private fun zoomIn() {
        val deadline = System.currentTimeMillis() + 15_000
        while (true) {
            composeTestRule.onNodeWithContentDescription("Image preview").performTouchInput { doubleClick() }
            composeTestRule.waitForIdle()
            if (composeTestRule.onAllNodes(hasStateDescription(ZOOMED)).fetchSemanticsNodes().isNotEmpty()) return
            check(System.currentTimeMillis() < deadline) { "the photo never loaded, so nothing could be zoomed" }
            Thread.sleep(50)
        }
    }

    private fun send() {
        composeTestRule.onNodeWithContentDescription("Send").performClick()
        composeTestRule.waitForIdle()
    }

    private fun assertMiddleThird(op: RasterOp?) {
        val crop = op as? RasterOp.Crop ?: error("expected a crop, got $op")
        assertEquals(1f / 3f, crop.left, 0.02f)
        assertEquals(2f / 3f, crop.right, 0.02f)
        assertEquals(0f, crop.top, 0.02f)
        assertEquals(1f, crop.bottom, 0.02f)
    }

    @Test
    fun `an unzoomed photo is sent as it is, with no flatten at all`() {
        setContent()

        send()

        assertEquals(0, rasterizeCalls)
        assertEquals(pick, sent?.single()?.uri)
    }

    @Test
    fun `the zoom on screen is the crop that is sent`() {
        setContent()
        zoomIn()

        send()

        assertEquals(pick, rasterizedSource)
        assertMiddleThird(rasterizedOps?.single())
        assertEquals(flattened, sent?.single()?.uri)
    }

    @Test
    fun `opening an editor on a zoomed photo lands the crop first, as a step undo can walk back`() {
        setContent()
        zoomIn()

        composeTestRule.onNodeWithContentDescription("Draw").performClick()
        composeTestRule.waitForIdle()

        // The editor opened on the crop: it was flattened before the screen
        // changed, and cancelling the editor comes back to a page with history.
        assertMiddleThird(rasterizedOps?.single())
        composeTestRule.onNodeWithContentDescription("Cancel drawing").performClick()
        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithContentDescription("Undo edit").assertIsEnabled()
        // And the zoom itself is spent — the cropped step is shown whole, at 1x,
        // which is the same picture the zoom was showing.
        assertTrue(composeTestRule.onAllNodes(hasStateDescription(ZOOMED)).fetchSemanticsNodes().isEmpty())
    }

    @Test
    fun `a flatten that fails keeps the batch on screen and says so`() {
        rasterizeFails = true
        setContent()
        zoomIn()

        send()

        assertNull(sent)
        composeTestRule.onNodeWithText("Couldn't apply the crop. Try again.").assertIsDisplayed()
    }

    @Test
    fun `the zoom survives a rotation`() {
        // The frame is what gets sent, so a recreation that forgot it would send
        // the whole photo after the user had framed a face.
        val restoration = StateRestorationTester(composeTestRule)
        setContent(restoration)
        zoomIn()

        restoration.emulateSavedInstanceStateRestore()
        composeTestRule.waitForIdle()
        send()

        assertMiddleThird(rasterizedOps?.single())
        assertEquals(flattened, sent?.single()?.uri)
    }

    private companion object {
        const val ZOOMED = "Zoomed in, sent as a crop"
    }
}
