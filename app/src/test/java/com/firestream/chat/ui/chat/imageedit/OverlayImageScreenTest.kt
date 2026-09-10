package com.firestream.chat.ui.chat.imageedit

import android.graphics.Bitmap
import android.net.Uri
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import com.firestream.chat.domain.util.OverlayContent
import com.firestream.chat.domain.util.RasterOp
import com.firestream.chat.domain.util.ShapeKind
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
 * What the overlay screen hands back, and what it refuses to.
 *
 * The arithmetic under every gesture is pinned one layer down, in
 * `OverlayGeometryTest`, and the history in `OverlayStackTest` — which is
 * deliberate, because a Robolectric test composes this screen without a finger
 * ever crossing it and could not tell a handle in the right place from one a
 * finger-width off. What it *can* settle is the wiring: that placing something
 * reaches the flatten, that the layer eye does not change what is written, that
 * delete is undoable, and that a rotation does not throw the placements away.
 *
 * Two things only hardware can answer and the plan makes part of this phase: whether
 * a 26 dp handle with a 48 dp reach is grabbable without occluding what it sits
 * on, and whether the four-segment island plus its search and delete buttons
 * still fit a 390 dp row. Both are logged under `docs/BACKLOG.md`
 * §*Pending on-device verification*.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [29], application = android.app.Application::class)
class OverlayImageScreenTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    private val source = Uri.parse("file:///edits/source.jpg")
    private val flattened = Uri.parse("file:///edits/flattened.jpg")

    private var rasterizedOps: List<RasterOp>? = null
    private var rasterizedLiveSteps: Set<Uri>? = null
    private var done: Uri? = null
    private var cancelled = 0

    private fun services() = ImageEditServices(
        renderPreview = { _, _, _ -> Bitmap.createBitmap(400, 300, Bitmap.Config.ARGB_8888) },
        rasterize = { _, ops, live ->
            rasterizedOps = ops
            rasterizedLiveSteps = live
            flattened
        },
    )

    private fun setContent(liveSteps: () -> Set<Uri> = { emptySet() }) {
        composeTestRule.setContent {
            MaterialTheme {
                OverlayImageScreen(
                    source = source,
                    onDone = { done = it },
                    onCancel = { cancelled++ },
                    services = services(),
                    liveSteps = liveSteps,
                )
            }
        }
    }

    private fun tab(name: String) {
        composeTestRule.onNodeWithContentDescription(name).performClick()
        composeTestRule.waitForIdle()
    }

    private fun tap(description: String) {
        composeTestRule.onNodeWithContentDescription(description).performClick()
        composeTestRule.waitForIdle()
    }

    private fun placeSticker() {
        tab("Stickers")
        tap("Heart")
    }

    private fun pressDone() = tap("Apply overlays")

    private fun placedOverlays() =
        (rasterizedOps?.singleOrNull() as? RasterOp.Overlays)?.overlays

    // ── Done ─────────────────────────────────────────────────────────────────

    @Test
    fun `done on a photo nothing was placed on cancels instead of flattening a copy`() {
        setContent()

        pressDone()

        assertNull(done)
        assertNull(rasterizedOps)
        assertEquals(1, cancelled)
    }

    @Test
    fun `a placed sticker reaches the flatten as one Overlays op`() {
        setContent()

        placeSticker()
        pressDone()

        val overlays = requireNotNull(placedOverlays())
        assertEquals(1, overlays.size)
        assertEquals(OverlayContent.Sticker("heart"), overlays.single().content)
        assertEquals(flattened, done)
    }

    @Test
    fun `Done names every live step, so flattening one page cannot evict another`() {
        val other = Uri.parse("file:///edits/page-two.jpg")
        setContent(liveSteps = { setOf(other) })

        placeSticker()
        pressDone()

        assertEquals(setOf(other), rasterizedLiveSteps)
    }

    // ── The four tabs ────────────────────────────────────────────────────────

    @Test
    fun `the editor host declares four segments and switches between them`() {
        setContent()

        // Icon-only except the active one, which keeps its label — so the
        // switch is checked by what each tab shows, not by the island.
        composeTestRule.onNodeWithContentDescription("Stickers").assertExists()
        composeTestRule.onNodeWithContentDescription("Text").assertExists()
        composeTestRule.onNodeWithContentDescription("Shapes").assertExists()

        tab("Stickers")
        composeTestRule.onNodeWithContentDescription("Heart").assertExists()

        tab("Shapes")
        composeTestRule.onNodeWithContentDescription("Rectangle").assertExists()
        composeTestRule.onNodeWithContentDescription("Heart").assertDoesNotExist()
    }

    @Test
    fun `a shape is placed in the colour and fill the tab is set to`() {
        setContent()

        tab("Shapes")
        tap("Filled")
        tap("Red")
        tap("Arrow")
        pressDone()

        val shape = requireNotNull(placedOverlays()).single().content as OverlayContent.Shape
        assertEquals(ShapeKind.ARROW, shape.kind)
        assertTrue(shape.filled)
        assertEquals(0xFFE53935, shape.colorArgb)
    }

    @Test
    fun `a text run is placed only once, on Add, and not on every keystroke`() {
        setContent()

        tab("Text")
        composeTestRule.onNodeWithContentDescription("Overlay text").performTextInput("meet here")
        composeTestRule.waitForIdle()
        tap("Place text")
        pressDone()

        val overlays = requireNotNull(placedOverlays())
        assertEquals(1, overlays.size)
        assertEquals("meet here", (overlays.single().content as OverlayContent.Text).text)
    }

    @Test
    fun `Add is dead until there is something to place`() {
        setContent()

        tab("Text")

        composeTestRule.onNodeWithContentDescription("Place text").assertIsNotEnabled()
        composeTestRule.onNodeWithContentDescription("Overlay text").performTextInput("hi")
        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithContentDescription("Place text").assertIsEnabled()
    }

    // ── Selection and delete ─────────────────────────────────────────────────

    @Test
    fun `delete is hidden until something is selected, and placing selects it`() {
        setContent()

        composeTestRule.onNodeWithContentDescription("Delete selected").assertDoesNotExist()

        placeSticker()

        // Newly placed is newly selected, so the handles and the trash are on
        // the thing you just added without a second tap to find it.
        composeTestRule.onNodeWithContentDescription("Delete selected").assertExists()
    }

    @Test
    fun `deleting the selection leaves nothing to flatten, and undo brings it back`() {
        setContent()

        placeSticker()
        tap("Delete selected")

        pressDone()
        assertNull("an empty photo has nothing to write", rasterizedOps)
        assertEquals(1, cancelled)

        tap("Undo placement")
        pressDone()
        assertEquals(1, requireNotNull(placedOverlays()).size)
    }

    @Test
    fun `undo and redo disable themselves at each end of the history`() {
        setContent()

        composeTestRule.onNodeWithContentDescription("Undo placement").assertIsNotEnabled()
        composeTestRule.onNodeWithContentDescription("Redo placement").assertIsNotEnabled()

        placeSticker()
        composeTestRule.onNodeWithContentDescription("Undo placement").assertIsEnabled()
        composeTestRule.onNodeWithContentDescription("Redo placement").assertIsNotEnabled()

        tap("Undo placement")
        composeTestRule.onNodeWithContentDescription("Undo placement").assertIsNotEnabled()
        composeTestRule.onNodeWithContentDescription("Redo placement").assertIsEnabled()
    }

    // ── The layer eye is a view control ──────────────────────────────────────

    @Test
    fun `hiding the layer does not change what Done writes`() {
        setContent()

        placeSticker()
        tap("Hide what you placed")
        pressDone()

        // §2.7: the eye hides, it never discards. Done flattens either way.
        assertEquals(1, requireNotNull(placedOverlays()).size)
        assertEquals(flattened, done)
    }

    // ── Rotation ─────────────────────────────────────────────────────────────

    @Test
    fun `placements and the cursor survive a rotation`() {
        val restorer = StateRestorationTester(composeTestRule)
        restorer.setContent {
            MaterialTheme {
                OverlayImageScreen(
                    source = source,
                    onDone = { done = it },
                    onCancel = { cancelled++ },
                    services = services(),
                )
            }
        }

        placeSticker()
        tab("Shapes")
        tap("Ellipse")
        tap("Undo placement")

        restorer.emulateSavedInstanceStateRestore()
        composeTestRule.waitForIdle()

        // One object left and a redo still waiting — a saver that dropped the
        // cursor would silently re-apply the placement that was just undone.
        composeTestRule.onNodeWithContentDescription("Redo placement").assertIsEnabled()
        pressDone()
        val overlays = requireNotNull(placedOverlays())
        assertEquals(1, overlays.size)
        assertEquals(OverlayContent.Sticker("heart"), overlays.single().content)
    }
}
