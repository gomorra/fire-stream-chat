package com.firestream.chat.ui.chat.imageedit

import com.firestream.chat.domain.util.RasterOp
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The adjust screen's undo axis, and the two rules that make it behave like one
 * thing the user did rather than like a log of every event that reached it.
 */
class AdjustStackTest {

    private val rotate = RasterOp.Rotate(90)
    private val flip = RasterOp.Flip(horizontal = true)
    private val crop = RasterOp.Crop(0.1f, 0.1f, 0.9f, 0.9f)

    @Test
    fun `a fresh stack is pristine and can neither undo nor redo`() {
        val stack = AdjustStack()

        assertTrue(stack.isPristine)
        assertFalse(stack.canUndo)
        assertFalse(stack.canRedo)
        assertEquals(emptyList<RasterOp>(), stack.active)
    }

    @Test
    fun `undo leaves the op in place so redo can walk forward again`() {
        val stack = AdjustStack().push(rotate).push(flip).undo()

        assertEquals(listOf(rotate), stack.active)
        assertEquals("the op is still there, only the cursor moved", 2, stack.ops.size)
        assertTrue(stack.canRedo)
        assertEquals(listOf(rotate, flip), stack.redo().active)
    }

    @Test
    fun `undo and redo stop at their own ends rather than running off them`() {
        val stack = AdjustStack().push(rotate)

        assertEquals(stack, stack.redo())
        assertEquals(AdjustStack(listOf(rotate), 0), stack.undo())
        assertEquals(stack.undo(), stack.undo().undo())
    }

    @Test
    fun `pushing after an undo discards what redo was holding`() {
        // Linear history: branch management inside a send preview is a UI nobody
        // wants and nobody would find.
        val stack = AdjustStack().push(rotate).push(flip).undo().push(crop)

        assertEquals(listOf(rotate, crop), stack.ops)
        assertFalse(stack.canRedo)
    }

    // ── Collapsing ────────────────────────────────────────────────────────────

    @Test
    fun `a slider that moves three times still leaves one step to undo`() {
        val stack = AdjustStack()
            .collapse(RasterOp.Straighten(2f)) { it is RasterOp.Straighten }
            .collapse(RasterOp.Straighten(4f)) { it is RasterOp.Straighten }
            .collapse(RasterOp.Straighten(5.5f)) { it is RasterOp.Straighten }

        assertEquals(listOf(RasterOp.Straighten(5.5f)), stack.ops)
        assertEquals(5.5f, stack.trailingAngle(), 0.001f)
    }

    @Test
    fun `collapsing back to zero removes the step instead of writing a no-op`() {
        val stack = AdjustStack()
            .push(rotate)
            .collapse(RasterOp.Straighten(3f)) { it is RasterOp.Straighten }
            .collapse(null) { it is RasterOp.Straighten }

        assertEquals(listOf(rotate), stack.ops)
        assertEquals(0f, stack.trailingAngle(), 0.001f)
    }

    @Test
    fun `collapsing only replaces a trailing op of the same kind`() {
        // A straighten that follows a crop is a genuinely new step: the crop is
        // between them and rotating the cropped result is not the same operation.
        val stack = AdjustStack()
            .collapse(RasterOp.Straighten(3f)) { it is RasterOp.Straighten }
            .push(crop)
            .collapse(RasterOp.Straighten(1f)) { it is RasterOp.Straighten }

        assertEquals(listOf(RasterOp.Straighten(3f), crop, RasterOp.Straighten(1f)), stack.ops)
    }

    @Test
    fun `resize presets collapse the same way and Original clears the step`() {
        val resized = AdjustStack()
            .collapse(RasterOp.Resize(1600)) { it is RasterOp.Resize }
            .collapse(RasterOp.Resize(1080)) { it is RasterOp.Resize }

        assertEquals(1080, resized.trailingLongEdge())
        assertEquals(listOf(RasterOp.Resize(1080)), resized.ops)

        val cleared = resized.collapse(null) { it is RasterOp.Resize }
        assertEquals(null, cleared.trailingLongEdge())
        assertTrue(cleared.isPristine)
    }

    @Test
    fun `collapsing after an undo also discards the redo tail`() {
        val stack = AdjustStack().push(rotate).push(flip).undo()
            .collapse(RasterOp.Straighten(2f)) { it is RasterOp.Straighten }

        assertEquals(listOf(rotate, RasterOp.Straighten(2f)), stack.ops)
        assertFalse(stack.canRedo)
    }

    @Test
    fun `reset clears the redo tail as well, so it cannot be undone back into view`() {
        val stack = AdjustStack().push(rotate).push(flip).reset()

        assertTrue(stack.isPristine)
        assertFalse(stack.canUndo)
        assertFalse(stack.canRedo)
        assertEquals(emptyList<RasterOp>(), stack.ops)
    }

    // ── What the preview bitmap is rendered with ──────────────────────────────

    @Test
    fun `the straighten under the slider is withheld so it is not applied twice`() {
        val active = listOf(rotate, RasterOp.Straighten(4f))

        assertEquals(listOf(rotate), previewOps(active, AdjustTool.STRAIGHTEN))
        assertEquals(active, previewOps(active, AdjustTool.CROP))
    }

    @Test
    fun `only a trailing straighten is withheld`() {
        // One buried behind a crop is baked in — the crop above it was measured
        // against the straightened image, so removing it would move the frame.
        val active = listOf(RasterOp.Straighten(4f), crop)

        assertEquals(active, previewOps(active, AdjustTool.STRAIGHTEN))
    }

    @Test
    fun `resizes never reach the preview, whatever tool is open`() {
        // A resize changes the file's pixel count and nothing the screen shows;
        // baking one in would only re-decode the photo smaller.
        val active = listOf(RasterOp.Resize(720), crop, RasterOp.Resize(1080))

        assertEquals(listOf(crop), previewOps(active, AdjustTool.NONE))
        assertEquals(listOf(crop), previewOps(active, AdjustTool.RESIZE))
    }

    // ── Surviving a rotation ──────────────────────────────────────────────────

    @Test
    fun `the saver round-trips every op kind and the cursor with them`() {
        // Turning the phone mid-crop must not throw the crop away — and a saver
        // that kept the ops but dropped the cursor would silently re-apply the
        // steps the user had just undone.
        val stack = AdjustStack(
            ops = listOf(
                RasterOp.Rotate(270),
                RasterOp.Flip(horizontal = false),
                RasterOp.Straighten(-3.5f),
                RasterOp.Crop(0.05f, 0.1f, 0.95f, 0.9f),
                RasterOp.Resize(1600),
            ),
            cursor = 3,
        )

        val restored = restore(stack)

        assertEquals(stack.ops, restored.ops)
        assertEquals(3, restored.cursor)
    }

    @Test
    fun `a cursor beyond the restored ops is clamped rather than trusted`() {
        val restored = AdjustStack.StackSaver.run {
            restore(listOf("9", "rotate:90")) as AdjustStack
        }

        assertEquals(1, restored.cursor)
    }

    @Test
    fun `an unparseable entry costs one step, not the whole screen`() {
        val restored = AdjustStack.StackSaver.run {
            restore(listOf("2", "rotate:90", "nonsense", "flip:h")) as AdjustStack
        }

        assertEquals(listOf(RasterOp.Rotate(90), RasterOp.Flip(horizontal = true)), restored.ops)
    }

    private fun restore(stack: AdjustStack): AdjustStack {
        val saved = with(AdjustStack.StackSaver) {
            FakeSaverScope.save(stack)
        }
        @Suppress("UNCHECKED_CAST")
        return AdjustStack.StackSaver.restore(requireNotNull(saved)) as AdjustStack
    }

    private object FakeSaverScope : androidx.compose.runtime.saveable.SaverScope {
        override fun canBeSaved(value: Any): Boolean = true
    }
}
