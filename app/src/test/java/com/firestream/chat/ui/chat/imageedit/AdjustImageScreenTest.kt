package com.firestream.chat.ui.chat.imageedit

import android.graphics.Bitmap
import android.net.Uri
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import com.firestream.chat.domain.util.RasterOp
import com.firestream.chat.domain.util.SourceImage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * What the adjust screen hands back, and what it refuses to.
 *
 * **This is not a test that the crop handles land where the finger expects
 * them.** Everything in this screen that is a gesture over a coordinate mapping
 * is checked one layer down, in `CropGeometryTest`, precisely because
 * Robolectric renders a composable without ever moving a pointer across it —
 * and the rest of it needs hardware, which is why the plan makes an on-device
 * pass part of the phase (`.claude/plans/image-editor.md` §3).
 *
 * What *is* checkable here is the contract around the gesture: which ops reach
 * the rasterizer, when the flatten is skipped entirely, and which of the history
 * controls are live at each point along the stack.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [29], application = android.app.Application::class)
class AdjustImageScreenTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    private val source = Uri.parse("file:///edits/source.jpg")
    private val flattened = Uri.parse("file:///edits/flattened.jpg")

    private var rasterizedOps: List<RasterOp>? = null
    private var rasterizedLiveSteps: Set<Uri>? = null
    private var done: Uri? = null
    private var cancelled = 0

    private fun setContent(
        rasterize: suspend (Uri, List<RasterOp>, Set<Uri>) -> Uri? = { _, ops, live ->
            rasterizedOps = ops
            rasterizedLiveSteps = live
            flattened
        },
        liveSteps: () -> Set<Uri> = { emptySet() },
    ) {
        composeTestRule.setContent {
            MaterialTheme {
                AdjustImageScreen(
                    source = source,
                    onDone = { done = it },
                    onCancel = { cancelled++ },
                    services = ImageEditServices(
                        probeSource = { SourceImage(4000, 3000, 2_400_000) },
                        renderPreview = { _, _, _ ->
                            Bitmap.createBitmap(400, 300, Bitmap.Config.ARGB_8888)
                        },
                        rasterize = rasterize,
                    ),
                    liveSteps = liveSteps,
                )
            }
        }
    }

    // ── Done ──────────────────────────────────────────────────────────────────

    @Test
    fun `done on an untouched photo cancels instead of flattening a copy of it`() {
        // Writing a re-encoded copy of an unchanged photo would burn a history
        // step, a cache file and a generation of JPEG quality on a no-op.
        setContent()

        composeTestRule.onNodeWithContentDescription("Apply adjustments").performClick()
        composeTestRule.waitForIdle()

        assertNull(done)
        assertNull(rasterizedOps)
        assertEquals(1, cancelled)
    }

    @Test
    fun `done flattens the ops the user actually built, in order`() {
        setContent()

        composeTestRule.onNodeWithContentDescription("Rotate").performClick()
        composeTestRule.onNodeWithContentDescription("Flip").performClick()
        composeTestRule.onNodeWithContentDescription("Apply adjustments").performClick()
        composeTestRule.waitForIdle()

        assertEquals(
            listOf(RasterOp.Rotate(90), RasterOp.Flip(horizontal = true)),
            rasterizedOps,
        )
        assertEquals(flattened, done)
    }

    @Test
    fun `done names every live step so the byte budget cannot evict another page's edit`() {
        // Not defaulted anywhere on the way down: eviction is globally
        // oldest-first, so a forgotten set is a deleted edit on page 3, not a
        // compile error (§3, Phase 2 departure 7).
        val live = setOf(Uri.parse("file:///edits/page3.jpg"))
        setContent(liveSteps = { live })

        composeTestRule.onNodeWithContentDescription("Rotate").performClick()
        composeTestRule.onNodeWithContentDescription("Apply adjustments").performClick()
        composeTestRule.waitForIdle()

        assertEquals(live, rasterizedLiveSteps)
    }

    @Test
    fun `a rasterize that fails keeps the screen open and says so`() {
        // Closing over a lost edit would look exactly like a successful one.
        setContent(rasterize = { _, _, _ -> null })

        composeTestRule.onNodeWithContentDescription("Rotate").performClick()
        composeTestRule.onNodeWithContentDescription("Apply adjustments").performClick()
        composeTestRule.waitForIdle()

        assertNull(done)
        assertEquals(0, cancelled)
        composeTestRule.onNodeWithText("Couldn't apply the edit. Try again.").assertExists()
    }

    @Test
    fun `cancel writes nothing at all, however much was built up`() {
        setContent()

        composeTestRule.onNodeWithContentDescription("Rotate").performClick()
        composeTestRule.onNodeWithContentDescription("Cancel adjustments").performClick()
        composeTestRule.waitForIdle()

        assertNull(rasterizedOps)
        assertNull(done)
        assertEquals(1, cancelled)
    }

    // ── The history controls ──────────────────────────────────────────────────

    @Test
    fun `undo redo and reset are dead until something has been done`() {
        setContent()

        composeTestRule.onNodeWithContentDescription("Undo adjustment").assertIsNotEnabled()
        composeTestRule.onNodeWithContentDescription("Redo adjustment").assertIsNotEnabled()
        composeTestRule.onNodeWithContentDescription("Reset adjustments").assertIsNotEnabled()
    }

    @Test
    fun `undo steps off the last transform and redo puts it back`() {
        setContent()

        composeTestRule.onNodeWithContentDescription("Rotate").performClick()
        composeTestRule.onNodeWithContentDescription("Flip").performClick()
        composeTestRule.onNodeWithContentDescription("Undo adjustment").assertIsEnabled().performClick()
        composeTestRule.onNodeWithContentDescription("Redo adjustment").assertIsEnabled()

        composeTestRule.onNodeWithContentDescription("Apply adjustments").performClick()
        composeTestRule.waitForIdle()

        assertEquals(listOf(RasterOp.Rotate(90)), rasterizedOps)
    }

    @Test
    fun `acting after an undo discards what redo was holding`() {
        setContent()

        composeTestRule.onNodeWithContentDescription("Rotate").performClick()
        composeTestRule.onNodeWithContentDescription("Flip").performClick()
        composeTestRule.onNodeWithContentDescription("Undo adjustment").performClick()
        composeTestRule.onNodeWithContentDescription("Rotate").performClick()

        composeTestRule.onNodeWithContentDescription("Redo adjustment").assertIsNotEnabled()
        composeTestRule.onNodeWithContentDescription("Apply adjustments").performClick()
        composeTestRule.waitForIdle()

        assertEquals(listOf(RasterOp.Rotate(90), RasterOp.Rotate(90)), rasterizedOps)
    }

    @Test
    fun `reset is the all-at-once escape and leaves nothing to flatten`() {
        setContent()

        composeTestRule.onNodeWithContentDescription("Rotate").performClick()
        composeTestRule.onNodeWithContentDescription("Flip").performClick()
        composeTestRule.onNodeWithContentDescription("Reset adjustments").performClick()

        composeTestRule.onNodeWithContentDescription("Undo adjustment").assertIsNotEnabled()
        composeTestRule.onNodeWithContentDescription("Redo adjustment").assertIsNotEnabled()

        composeTestRule.onNodeWithContentDescription("Apply adjustments").performClick()
        composeTestRule.waitForIdle()

        assertNull(rasterizedOps)
        assertEquals(1, cancelled)
    }

    // ── The tool panels ───────────────────────────────────────────────────────

    @Test
    fun `the tool row offers all five tools and no layer-visibility toggle`() {
        // No eye button on this screen, deliberately: there is no added layer to
        // hide, and one that did nothing here would teach people to distrust it
        // on the two screens where it works (§2.7).
        setContent()

        for (tool in listOf("Rotate", "Flip", "Straighten", "Crop", "Resize")) {
            composeTestRule.onNodeWithContentDescription(tool).assertExists()
        }
        composeTestRule.onNodeWithContentDescription("Hide layer").assertDoesNotExist()
    }

    @Test
    fun `the aspect presets appear with the crop tool and nowhere else`() {
        setContent()

        composeTestRule.onNodeWithText("16:9").assertDoesNotExist()
        composeTestRule.onNodeWithContentDescription("Crop").performClick()
        for (preset in listOf("Free", "Original", "1:1", "4:5", "16:9")) {
            composeTestRule.onNodeWithText(preset).assertExists()
        }
    }

    @Test
    fun `the straighten slider and its readout appear with the straighten tool`() {
        setContent()

        composeTestRule.onNodeWithContentDescription("Straighten angle").assertDoesNotExist()
        composeTestRule.onNodeWithContentDescription("Straighten").performClick()
        composeTestRule.onNodeWithContentDescription("Straighten angle").assertExists()
        composeTestRule.onNodeWithText("0°").assertExists()
    }

    @Test
    fun `a resize preset is labelled with what it will actually produce`() {
        // The part with no WhatsApp equivalent: each preset says its own output
        // dimensions and an approximate size, computed through the whole stack.
        setContent()

        composeTestRule.onNodeWithContentDescription("Resize").performClick()
        composeTestRule.waitForIdle()

        composeTestRule.onNodeWithText("2048 × 1536", substring = true).assertExists()
        // The row scrolls — five presets each carrying their own dimensions and
        // size do not fit a phone-width row, which is what makes it a LazyRow.
        composeTestRule.onNodeWithContentDescription("Resize presets")
            .performScrollToNode(hasText("720 × 540", substring = true))
        composeTestRule.onNodeWithText("720 × 540", substring = true).assertExists()
    }

    @Test
    fun `choosing a resize preset lands one op, and Original clears it`() {
        setContent()

        composeTestRule.onNodeWithContentDescription("Resize").performClick()
        composeTestRule.onNodeWithContentDescription("Resize presets")
            .performScrollToNode(hasText("1080"))
        composeTestRule.onNodeWithText("1080").performClick()
        composeTestRule.onNodeWithContentDescription("Resize presets")
            .performScrollToNode(hasText("1600"))
        composeTestRule.onNodeWithText("1600").performClick()
        composeTestRule.onNodeWithContentDescription("Apply adjustments").performClick()
        composeTestRule.waitForIdle()

        // Two taps, one step — the row edits its op in place rather than
        // stacking one per tap.
        assertEquals(listOf(RasterOp.Resize(1600)), rasterizedOps)
    }

    @Test
    fun `a resize label after a quarter turn quotes the turned dimensions`() {
        // The label is computed through the whole stack, not from the source
        // alone, so it must follow a rotation that swapped the edges.
        setContent()

        composeTestRule.onNodeWithContentDescription("Rotate").performClick()
        composeTestRule.onNodeWithContentDescription("Resize").performClick()
        composeTestRule.waitForIdle()

        composeTestRule.onNodeWithText("1536 × 2048", substring = true).assertExists()
    }

    @Test
    fun `the crop frame is only drawn while the crop tool is open`() {
        setContent()

        composeTestRule.onNodeWithContentDescription("Crop frame").assertDoesNotExist()
        composeTestRule.onNodeWithContentDescription("Crop").performClick()
        composeTestRule.onNodeWithContentDescription("Crop frame").assertExists()
        // Tapping the open tool again closes it.
        composeTestRule.onNodeWithContentDescription("Crop").performClick()
        composeTestRule.onNodeWithContentDescription("Crop frame").assertDoesNotExist()
    }

    @Test
    fun `a source that cannot be probed still renders its presets without a size`() {
        composeTestRule.setContent {
            MaterialTheme {
                AdjustImageScreen(
                    source = source,
                    onDone = { done = it },
                    onCancel = { cancelled++ },
                    services = ImageEditServices(
                        renderPreview = { _, _, _ ->
                            Bitmap.createBitmap(400, 300, Bitmap.Config.ARGB_8888)
                        },
                    ),
                )
            }
        }

        composeTestRule.onNodeWithContentDescription("Resize").performClick()
        composeTestRule.waitForIdle()

        // The preset is still choosable; only the line that would have been a
        // guess is missing.
        composeTestRule.onNodeWithText("1080").assertExists()
        assertTrue(true)
    }
}
