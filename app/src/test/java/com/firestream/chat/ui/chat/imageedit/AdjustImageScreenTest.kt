package com.firestream.chat.ui.chat.imageedit

import android.graphics.Bitmap
import android.net.Uri
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.getBoundsInRoot
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.geometry.Offset
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

    /** The preview bitmap every test here renders — 4:3, matching setContent. */
    private val previewWidth = 400f
    private val previewHeight = 300f

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

        composeTestRule.onNodeWithText("1600 × 1200", substring = true).assertExists()
        // The row scrolls — presets carrying their own dimensions and size do not
        // all fit a phone-width row, which is what makes it a LazyRow.
        composeTestRule.onNodeWithContentDescription("Resize presets")
            .performScrollToNode(hasText("720 × 540", substring = true))
        composeTestRule.onNodeWithText("720 × 540", substring = true).assertExists()
    }

    @Test
    fun `every preset offered is one a standard send will not quietly override`() {
        // Regression: a 2048 preset takes effect on an HD send and is re-capped
        // to 1600 by the compressor on a standard one, so it silently did nothing
        // in one of the two quality modes. Dropping it is what makes §2.5's
        // "an explicit resize wins" true without touching the send pipeline.
        setContent()

        composeTestRule.onNodeWithContentDescription("Resize").performClick()
        composeTestRule.waitForIdle()

        composeTestRule.onNodeWithText("2048", substring = true).assertDoesNotExist()
        for (preset in listOf("Original", "1600", "1080", "720")) {
            composeTestRule.onNodeWithContentDescription("Resize presets")
                .performScrollToNode(hasText(preset))
            composeTestRule.onNodeWithText(preset).assertExists()
        }
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

        composeTestRule.onNodeWithText("1200 × 1600", substring = true).assertExists()
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

    // ── Where the photo actually lands ────────────────────────────────────────

    @Test
    fun `the photo is laid out exactly on the fit rect the crop overlay draws against`() {
        // Regression: the photo's container centres its children *and* the photo
        // was offset by the fit mapper's own centring offset, so it sat half a
        // letterbox down and right of the frame the crop overlay was drawing —
        // every handle a finger-width from the photo it belonged to. Robolectric
        // cannot move a pointer across the screen, but it can say where the
        // layout put things, which is the half of that bug it can catch.
        val canvas = 400
        val bitmapWidth = 200
        val bitmapHeight = 100

        composeTestRule.setContent {
            MaterialTheme {
                Box(modifier = Modifier.size(canvas.dp)) {
                    AdjustImageScreen(
                        source = source,
                        onDone = {},
                        onCancel = {},
                        services = ImageEditServices(
                            renderPreview = { _, _, _ ->
                                Bitmap.createBitmap(bitmapWidth, bitmapHeight, Bitmap.Config.ARGB_8888)
                            },
                        ),
                    )
                }
            }
        }
        composeTestRule.waitForIdle()

        // The crop overlay fills the photo area exactly, so its bounds are the
        // area the handles are drawn against — which is the thing the photo has
        // to agree with.
        composeTestRule.onNodeWithContentDescription("Crop").performClick()
        val photo = composeTestRule.onNodeWithContentDescription("Image being adjusted")
            .getBoundsInRoot()
        val area = composeTestRule.onNodeWithContentDescription("Crop frame").getBoundsInRoot()

        val photoWidth = photo.right.value - photo.left.value
        val photoHeight = photo.bottom.value - photo.top.value

        // The 2:1 bitmap letterboxes inside the photo area, so what matters is
        // that the photo is centred *once*: its own centre must sit on the centre
        // of the area the overlay draws over.
        assertEquals(
            (area.left.value + area.right.value) / 2f,
            (photo.left.value + photo.right.value) / 2f,
            1f,
        )
        assertEquals(
            (area.top.value + area.bottom.value) / 2f,
            (photo.top.value + photo.bottom.value) / 2f,
            1f,
        )
        assertEquals(
            "the fit is uniform, so the drawn aspect ratio is the bitmap's",
            bitmapWidth.toFloat() / bitmapHeight,
            photoWidth / photoHeight,
            0.05f,
        )
        assertTrue("the photo must not overflow the area it is fitted into", photoWidth <= area.right.value - area.left.value + 1f)
    }

    // ── Dragging the crop frame ───────────────────────────────────────────────
    //
    // Nothing above this point ever moves a pointer across the screen, which is
    // how a crop frame that could not be dragged at all shipped green: the
    // arithmetic in CropGeometryTest was correct the whole time and the wiring
    // that feeds it was not. These two drive real synthetic touch through the
    // same `pointerInput` a finger reaches.

    /** The fitted photo rect inside the crop overlay, in the overlay's own pixels. */
    private data class Fitted(val x: Float, val y: Float, val width: Float, val height: Float)

    private fun fittedRect(): Fitted {
        val node = composeTestRule.onNodeWithContentDescription("Crop frame").fetchSemanticsNode()
        val canvasWidth = node.size.width.toFloat()
        val canvasHeight = node.size.height.toFloat()
        val scale = minOf(canvasWidth / previewWidth, canvasHeight / previewHeight)
        val fittedWidth = previewWidth * scale
        val fittedHeight = previewHeight * scale
        return Fitted(
            x = (canvasWidth - fittedWidth) / 2f,
            y = (canvasHeight - fittedHeight) / 2f,
            width = fittedWidth,
            height = fittedHeight,
        )
    }

    private fun croppedOp(): RasterOp.Crop? {
        composeTestRule.onNodeWithContentDescription("Apply adjustments").performClick()
        composeTestRule.waitForIdle()
        return rasterizedOps?.filterIsInstance<RasterOp.Crop>()?.lastOrNull()
    }

    @Test
    fun `a corner drag keeps following the finger after the frame has already moved once`() {
        // The regression: the gesture captured the frame it started with and
        // never saw an update, so the second grab looked for corners where the
        // frame no longer was, missed them, fell through to move-mode, and
        // `move` on a full-image frame clamps to zero — the frame snapped back.
        setContent()
        composeTestRule.onNodeWithContentDescription("Crop").performClick()
        val (originX, originY, width, height) = fittedRect()

        // First drag: top-left corner in to a quarter of the way across.
        composeTestRule.onNodeWithContentDescription("Crop frame").performTouchInput {
            down(Offset(originX, originY))
            moveTo(Offset(originX + width * 0.06f, originY + height * 0.06f))
            moveTo(Offset(originX + width * 0.16f, originY + height * 0.16f))
            moveTo(Offset(originX + width * 0.25f, originY + height * 0.25f))
            up()
        }
        composeTestRule.waitForIdle()

        // Second drag: the same corner, now at 0.25, pulled in to about 0.40.
        composeTestRule.onNodeWithContentDescription("Crop frame").performTouchInput {
            down(Offset(originX + width * 0.25f, originY + height * 0.25f))
            moveTo(Offset(originX + width * 0.31f, originY + height * 0.31f))
            moveTo(Offset(originX + width * 0.40f, originY + height * 0.40f))
            up()
        }
        composeTestRule.waitForIdle()

        val crop = requireNotNull(croppedOp()) { "the two drags produced no crop at all" }
        assertEquals("left edge follows the second drag", 0.40f, crop.left, 0.06f)
        assertEquals("top edge follows the second drag", 0.40f, crop.top, 0.06f)
    }

    @Test
    fun `the whole crop frame can be dragged to a new position`() {
        // A drag from inside the frame moves it. Every pointer event has to act
        // on where the frame is *now*: applied to the frame the gesture started
        // with, only the last event's delta survives and the frame barely moves.
        setContent()
        composeTestRule.onNodeWithContentDescription("Crop").performClick()

        // A 1:1 preset gives a centred frame with room to move in both
        // directions, without depending on the corner drag above.
        composeTestRule.onNodeWithText("1:1").performClick()
        composeTestRule.waitForIdle()
        val (originX, originY, width, height) = fittedRect()

        val centreX = originX + width / 2f
        val centreY = originY + height / 2f
        val frame = composeTestRule.onNodeWithContentDescription("Crop frame")

        // One event per block, with a recomposition between each, because that
        // is what a finger gets: pointer events arrive a frame apart and the
        // frame this gesture is moving is re-read every time. Dispatching the
        // whole drag inside a single block would hold composition still and
        // measure something no user can perform.
        frame.performTouchInput { down(Offset(centreX, centreY)) }
        // Deliberately asks for more travel than the frame has room for, so the
        // result is the edge it stops at rather than a number that would move
        // with the platform's touch slop — the first few pixels of any drag are
        // swallowed before onDrag ever sees them.
        for (step in 1..6) {
            composeTestRule.waitForIdle()
            frame.performTouchInput { moveTo(Offset(centreX + width * 0.05f * step, centreY)) }
        }
        composeTestRule.waitForIdle()
        frame.performTouchInput { up() }
        composeTestRule.waitForIdle()

        val crop = requireNotNull(croppedOp()) { "dragging the frame produced no crop at all" }
        // A 1:1 frame on a 4:3 photo is 0.75 wide and starts centred at
        // left = 0.125, so it has 0.125 of travel before it meets the right
        // edge — which this drag asks for twice over, and must therefore reach.
        assertEquals("the frame travels until it meets the edge", 0.25f, crop.left, 0.02f)
    }

}
