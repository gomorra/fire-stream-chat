package com.firestream.chat.ui.chat

import android.graphics.Bitmap
import android.net.Uri
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.performClick
import com.firestream.chat.domain.util.RasterOp
import com.firestream.chat.ui.chat.imageedit.ImageEditServices
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * The join between the adjust screen and the preview's edit history — the one
 * line Phase 2 left for Phase 3 to write, and the one it is easiest to write
 * only half of.
 *
 * Landing an edit has two halves: the new step goes onto the item, and the
 * files the landing orphaned go to `ImageEditRasterizer.discard`. Undo can no
 * longer free the file it steps off, so that discard is the *only* moment an
 * edit file ever becomes deletable; a Done that skips it leaks disk for 24 h
 * until the app-start sweep collects it.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [29], application = android.app.Application::class)
class ImagePreviewScreenAdjustTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    private val pick = Uri.parse("content://pick/1")
    private val flattened = Uri.parse("file:///edits/new-step.jpg")

    private var rasterizedSource: Uri? = null
    private var rasterizedLiveSteps: Set<Uri>? = null
    private var discarded = mutableListOf<String>()
    private var sent: List<PendingMedia>? = null
    private var rasterizedOps: List<RasterOp>? = null

    private fun item(steps: Int = 0, cursor: Int = steps) = PendingMedia(
        originalUri = pick,
        mimeType = "image/jpeg",
        editHistory = (1..steps).map { "file:///edits/step$it.jpg" },
        editCursor = cursor,
    )

    private fun setContent(vararg items: PendingMedia) {
        composeTestRule.setContent {
            MaterialTheme {
                ImagePreviewScreen(
                    items = items.toList(),
                    recentEmojis = emptyList(),
                    defaultIsHd = false,
                    onEmojiUsed = {},
                    onSend = { sent = it },
                    onDownload = {},
                    onDismiss = {},
                    edit = ImageEditServices(
                        discardEditSteps = { discarded += it },
                        renderPreview = { _, _, _ ->
                            Bitmap.createBitmap(80, 60, Bitmap.Config.ARGB_8888)
                        },
                        rasterize = { source, _, live ->
                            rasterizedSource = source
                            rasterizedLiveSteps = live
                            flattened
                        },
                    ),
                )
            }
        }
    }

    /** Opens the editor, applies one transform, and presses Done. */
    private fun adjustOnce() {
        composeTestRule.onNodeWithContentDescription("Adjust").performClick()
        composeTestRule.onNodeWithContentDescription("Rotate").performClick()
        composeTestRule.onNodeWithContentDescription("Apply adjustments").performClick()
        composeTestRule.waitForIdle()
    }

    @Test
    fun `the adjust entry point is live now that there is a screen behind it`() {
        // Phase 1 shipped this dimmed and unclickable; lighting it up is what
        // makes every other control on this rail reachable.
        setContent(item())

        composeTestRule.onNodeWithContentDescription("Adjust").assertIsEnabled()
    }

    @Test
    fun `a video page still offers no editor at all`() {
        setContent(PendingMedia(Uri.parse("content://pick/vid"), "video/mp4"))

        composeTestRule.onNodeWithContentDescription("Adjust").assertDoesNotExist()
    }

    @Test
    fun `done lands the step so the send carries the edited file`() {
        setContent(item())

        adjustOnce()

        assertEquals(pick, rasterizedSource)
        composeTestRule.onNodeWithContentDescription("Send").performClick()
        composeTestRule.waitForIdle()
        assertEquals(flattened.toString(), sent?.single()?.uri?.toString())
    }

    @Test
    fun `the history pill appears only once the first edit has landed`() {
        setContent(item())

        // A fresh pick looks untouched — the controls are hidden, not greyed.
        composeTestRule.onNodeWithContentDescription("Undo edit").assertDoesNotExist()

        adjustOnce()

        composeTestRule.onNodeWithContentDescription("Undo edit").assertIsEnabled()
        composeTestRule.onNodeWithContentDescription("Show original image").assertIsEnabled()
    }

    @Test
    fun `landing an edit mid-history discards exactly the tail it orphaned`() {
        // The abandoned steps are unreachable the moment the new one lands, and
        // this is their only chance to be collected before the next app start.
        setContent(item(steps = 3, cursor = 1))

        adjustOnce()

        assertEquals(
            listOf("file:///edits/step2.jpg", "file:///edits/step3.jpg"),
            discarded,
        )
        composeTestRule.onNodeWithContentDescription("Send").performClick()
        composeTestRule.waitForIdle()
        assertEquals(
            listOf("file:///edits/step1.jpg", flattened.toString()),
            sent?.single()?.editHistory,
        )
    }

    @Test
    fun `landing an edit at the top of the history discards nothing`() {
        setContent(item(steps = 2))

        adjustOnce()

        assertTrue("an ordinary edit orphans nothing", discarded.isEmpty())
    }

    @Test
    fun `the ninth step pushes the oldest off and hands that file over too`() {
        setContent(item(steps = PendingMedia.MAX_EDIT_STEPS))

        adjustOnce()

        assertEquals(listOf("file:///edits/step1.jpg"), discarded)
        composeTestRule.onNodeWithContentDescription("Send").performClick()
        composeTestRule.waitForIdle()
        assertEquals(PendingMedia.MAX_EDIT_STEPS, sent?.single()?.editHistory?.size)
    }

    @Test
    fun `flattening one page names every other page's current step as live`() {
        // Eviction is globally oldest-first, so page 2's current step is old in
        // global terms while the user edits page 1 — and deleting it would
        // destroy an edit that page can still show.
        val other = PendingMedia(
            originalUri = Uri.parse("content://pick/2"),
            mimeType = "image/jpeg",
            editHistory = listOf("file:///edits/other-step.jpg"),
            editCursor = 1,
        )
        setContent(item(), other)

        adjustOnce()

        assertEquals(
            setOf(pick, Uri.parse("file:///edits/other-step.jpg")),
            rasterizedLiveSteps,
        )
    }

    @Test
    fun `undo after an edit walks back to the untouched pick`() {
        setContent(item())

        adjustOnce()
        composeTestRule.onNodeWithContentDescription("Undo edit").performClick()
        composeTestRule.onNodeWithContentDescription("Send").performClick()
        composeTestRule.waitForIdle()

        assertEquals(pick.toString(), sent?.single()?.uri?.toString())
        assertTrue("undo must not delete the file it steps off", discarded.isEmpty())
    }

    @Test
    fun `cancelling the editor leaves the item exactly as it was`() {
        setContent(item())

        composeTestRule.onNodeWithContentDescription("Adjust").performClick()
        composeTestRule.onNodeWithContentDescription("Rotate").performClick()
        composeTestRule.onNodeWithContentDescription("Cancel adjustments").performClick()
        composeTestRule.waitForIdle()

        composeTestRule.onNodeWithContentDescription("Undo edit").assertDoesNotExist()
        composeTestRule.onNodeWithContentDescription("Send").performClick()
        composeTestRule.waitForIdle()
        assertEquals(pick.toString(), sent?.single()?.uri?.toString())
    }

    // ── Turning the phone ─────────────────────────────────────────────────────

    @Test
    fun `the editor and its unflattened transforms survive a state restore`() {
        // Regression: the editor was held in a plain `remember`, so turning the
        // phone closed it and discarded the op stack — which also made
        // AdjustStack.StackSaver dead code in production despite its KDoc
        // promising exactly this. §3 asks for both orientations.
        val restorationTester = StateRestorationTester(composeTestRule)
        restorationTester.setContent {
            MaterialTheme {
                ImagePreviewScreen(
                    items = listOf(item()),
                    recentEmojis = emptyList(),
                    defaultIsHd = false,
                    onEmojiUsed = {},
                    onSend = { sent = it },
                    onDownload = {},
                    onDismiss = {},
                    edit = ImageEditServices(
                        discardEditSteps = { discarded += it },
                        renderPreview = { _, _, _ ->
                            Bitmap.createBitmap(80, 60, Bitmap.Config.ARGB_8888)
                        },
                        rasterize = { _, ops, _ ->
                            rasterizedOps = ops
                            flattened
                        },
                    ),
                )
            }
        }

        composeTestRule.onNodeWithContentDescription("Adjust").performClick()
        composeTestRule.onNodeWithContentDescription("Rotate").performClick()
        composeTestRule.onNodeWithContentDescription("Flip").performClick()

        restorationTester.emulateSavedInstanceStateRestore()

        // Still in the editor, and still holding both transforms.
        composeTestRule.onNodeWithContentDescription("Apply adjustments").assertExists()
        composeTestRule.onNodeWithContentDescription("Undo adjustment").assertIsEnabled()
        composeTestRule.onNodeWithContentDescription("Apply adjustments").performClick()
        composeTestRule.waitForIdle()

        assertEquals(
            listOf(RasterOp.Rotate(90), RasterOp.Flip(horizontal = true)),
            rasterizedOps,
        )
    }
}
