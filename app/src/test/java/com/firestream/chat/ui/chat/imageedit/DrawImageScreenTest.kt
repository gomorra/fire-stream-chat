package com.firestream.chat.ui.chat.imageedit

import android.graphics.Bitmap
import android.net.Uri
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipe
import com.firestream.chat.domain.util.RasterOp
import com.firestream.chat.domain.util.StrokeTool
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
 * What the draw screen hands back, and what it refuses to.
 *
 * Unlike the adjust screen's tests, these do move a pointer across the canvas —
 * `performTouchInput` drives the real pointer-input pipeline, so a swipe here
 * genuinely exercises capture, normalization and the stroke that comes out of
 * it. What it still cannot answer is whether the stroke lands **where the finger
 * was on a real screen** and whether the flattened JPEG redacts what the preview
 * showed as covered; those are hardware questions and the plan makes an
 * on-device pass part of this phase (`.claude/plans/image-editor.md` §3).
 *
 * The arithmetic under the gesture is pinned one layer down, in
 * `StrokeGeometryTest`, and the flatten in `ImageEditRasterizerTest`.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [29], application = android.app.Application::class)
class DrawImageScreenTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    private val source = Uri.parse("file:///edits/source.jpg")
    private val flattened = Uri.parse("file:///edits/flattened.jpg")

    private var rasterizedOps: List<RasterOp>? = null
    private var rasterizedLiveSteps: Set<Uri>? = null
    private var done: Uri? = null
    private var cancelled = 0

    private fun services(
        rasterize: suspend (Uri, List<RasterOp>, Set<Uri>) -> Uri? = { _, ops, live ->
            rasterizedOps = ops
            rasterizedLiveSteps = live
            flattened
        },
    ) = ImageEditServices(
        renderPreview = { _, _, _ -> Bitmap.createBitmap(400, 300, Bitmap.Config.ARGB_8888) },
        pixelate = { Bitmap.createBitmap(48, 36, Bitmap.Config.ARGB_8888) },
        rasterize = rasterize,
    )

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
                DrawImageScreen(
                    source = source,
                    onDone = { done = it },
                    onCancel = { cancelled++ },
                    services = services(rasterize),
                    liveSteps = liveSteps,
                )
            }
        }
    }

    /** One left-to-right swipe across the middle of the canvas. */
    private fun draw(atFractionOfHeight: Float = 0.5f) {
        composeTestRule.onNodeWithContentDescription("Drawing canvas").performTouchInput {
            swipe(
                start = Offset(width * 0.2f, height * atFractionOfHeight),
                end = Offset(width * 0.8f, height * atFractionOfHeight),
                durationMillis = 200,
            )
        }
        composeTestRule.waitForIdle()
    }

    private fun pressDone() {
        composeTestRule.onNodeWithContentDescription("Apply drawing").performClick()
        composeTestRule.waitForIdle()
    }

    private fun flattenedStrokes() =
        (rasterizedOps?.singleOrNull() as? RasterOp.Strokes)?.strokes

    // ── Done ──────────────────────────────────────────────────────────────────

    @Test
    fun `done on an untouched photo cancels instead of flattening a copy of it`() {
        setContent()

        pressDone()

        assertNull(done)
        assertNull(rasterizedOps)
        assertEquals(1, cancelled)
    }

    @Test
    fun `a swipe becomes one stroke, and Done flattens it as a single op`() {
        setContent()

        draw()
        pressDone()

        val strokes = requireNotNull(flattenedStrokes())
        assertEquals(1, strokes.size)
        assertEquals(StrokeTool.PEN, strokes.single().tool)
        assertTrue("a swipe is more than one sample", strokes.single().points.size > 1)
        assertEquals(flattened, done)
    }

    @Test
    fun `a stroke is stored as fractions of the photo, not as canvas pixels`() {
        // The property the whole screen rests on: the same numbers have to mean
        // the same place on a 400 px preview and in a 4096 px file.
        setContent()

        draw()
        pressDone()

        val points = requireNotNull(flattenedStrokes()).single().points
        assertTrue(
            "expected 0..1 across the image, got ${points.first()} .. ${points.last()}",
            points.all { it.x in -0.01f..1.01f && it.y in -0.01f..1.01f },
        )
        assertTrue("the swipe ran left to right", points.last().x > points.first().x)
    }

    @Test
    fun `Done names every batch item's current step so the budget cannot evict one`() {
        val live = setOf(Uri.parse("file:///edits/page3.jpg"))
        setContent(liveSteps = { live })

        draw()
        pressDone()

        assertEquals(live, rasterizedLiveSteps)
    }

    @Test
    fun `a flatten that fails says so rather than closing over the lost drawing`() {
        setContent(rasterize = { _, _, _ -> null })

        draw()
        pressDone()

        assertNull(done)
        assertEquals(0, cancelled)
        composeTestRule.onNodeWithText("Couldn't apply the drawing. Try again.").assertExists()
    }

    // ── The layer eye ─────────────────────────────────────────────────────────

    @Test
    fun `hiding the layer does not change what Done writes`() {
        // §2.7: the eye is a view control. Pressing Done with the layer hidden
        // still flattens every stroke — a control that could silently discard
        // the user's work by being left in the wrong position is not worth the
        // ambiguity it saves.
        setContent()

        draw()
        composeTestRule.onNodeWithContentDescription("Hide the drawing").performClick()
        pressDone()

        assertEquals(1, requireNotNull(flattenedStrokes()).size)
        assertEquals(flattened, done)
    }

    @Test
    fun `the eye toggles back and says which way it is pointing`() {
        setContent()

        composeTestRule.onNodeWithContentDescription("Hide the drawing").performClick()
        composeTestRule.onNodeWithContentDescription("Show the drawing").performClick()
        composeTestRule.onNodeWithContentDescription("Hide the drawing").assertExists()
    }

    // ── History ───────────────────────────────────────────────────────────────

    @Test
    fun `undo and redo are dead at each end of the drawing`() {
        setContent()

        composeTestRule.onNodeWithContentDescription("Undo stroke").assertIsNotEnabled()
        composeTestRule.onNodeWithContentDescription("Redo stroke").assertIsNotEnabled()

        draw()
        composeTestRule.onNodeWithContentDescription("Undo stroke").assertIsEnabled()
        composeTestRule.onNodeWithContentDescription("Redo stroke").assertIsNotEnabled()

        composeTestRule.onNodeWithContentDescription("Undo stroke").performClick()
        composeTestRule.onNodeWithContentDescription("Undo stroke").assertIsNotEnabled()
        composeTestRule.onNodeWithContentDescription("Redo stroke").assertIsEnabled()
    }

    @Test
    fun `undo takes one stroke off, not the whole drawing`() {
        setContent()

        draw(atFractionOfHeight = 0.3f)
        draw(atFractionOfHeight = 0.7f)
        composeTestRule.onNodeWithContentDescription("Undo stroke").performClick()
        pressDone()

        assertEquals(1, requireNotNull(flattenedStrokes()).size)
    }

    @Test
    fun `a stroke drawn after an undo discards what redo was holding`() {
        setContent()

        draw(atFractionOfHeight = 0.3f)
        composeTestRule.onNodeWithContentDescription("Undo stroke").performClick()
        draw(atFractionOfHeight = 0.7f)

        composeTestRule.onNodeWithContentDescription("Redo stroke").assertIsNotEnabled()
        pressDone()
        assertEquals(1, requireNotNull(flattenedStrokes()).size)
    }

    // ── Tools ─────────────────────────────────────────────────────────────────

    @Test
    fun `the tool row decides what the next swipe draws`() {
        setContent()

        composeTestRule.onNodeWithContentDescription("Highlighter").performClick()
        draw(atFractionOfHeight = 0.3f)
        composeTestRule.onNodeWithContentDescription("Blur").performClick()
        draw(atFractionOfHeight = 0.7f)
        pressDone()

        assertEquals(
            listOf(StrokeTool.HIGHLIGHTER, StrokeTool.BLUR),
            requireNotNull(flattenedStrokes()).map { it.tool },
        )
    }

    @Test
    fun `blur offers no colours, because it has none to offer`() {
        setContent()

        composeTestRule.onNodeWithContentDescription("Stroke colours").assertExists()

        composeTestRule.onNodeWithContentDescription("Blur").performClick()
        composeTestRule.onNodeWithContentDescription("Stroke colours").assertDoesNotExist()
        composeTestRule.onNodeWithContentDescription("Red").assertDoesNotExist()
    }

    @Test
    fun `the colour strip changes what the next stroke is painted with`() {
        setContent()

        composeTestRule.onNodeWithContentDescription("Red").performClick()
        draw()
        pressDone()

        assertEquals(0xFFE53935, requireNotNull(flattenedStrokes()).single().colorArgb)
    }

    // ── Rotation ──────────────────────────────────────────────────────────────

    @Test
    fun `the drawing survives a configuration change`() {
        // The Phase 3 lesson, in a test rather than in a review: that phase's
        // op-stack saver was written and unit-tested while the screen holding it
        // sat in a plain `remember`, so turning the phone closed the editor and
        // made the saver dead code in production. `StateRestorationTester` goes
        // through a real `Bundle`, which is the only thing that proves it.
        val restorationTester = StateRestorationTester(composeTestRule)
        restorationTester.setContent {
            MaterialTheme {
                DrawImageScreen(
                    source = source,
                    onDone = { done = it },
                    onCancel = { cancelled++ },
                    services = services(),
                )
            }
        }

        composeTestRule.onNodeWithContentDescription("Highlighter").performClick()
        draw()
        composeTestRule.onNodeWithContentDescription("Undo stroke").assertIsEnabled()

        restorationTester.emulateSavedInstanceStateRestore()
        composeTestRule.waitForIdle()

        composeTestRule.onNodeWithContentDescription("Undo stroke").assertIsEnabled()
        pressDone()

        val strokes = requireNotNull(flattenedStrokes())
        assertEquals(1, strokes.size)
        assertEquals("the chosen tool comes back too", StrokeTool.HIGHLIGHTER, strokes.single().tool)
    }
}
