package com.firestream.chat.ui.chat.imageedit

import com.firestream.chat.domain.util.RasterOp
import com.firestream.chat.domain.util.Stroke
import com.firestream.chat.domain.util.StrokeGeometry
import com.firestream.chat.domain.util.StrokePoint
import com.firestream.chat.domain.util.StrokeTool
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The draw screen's history, and what survives a rotation.
 *
 * Linear, one stroke at a time, and the same cursor-not-a-stack shape
 * `PendingMedia` and [AdjustStack] use — see `.claude/plans/image-editor.md`
 * §2.7 for why redo forbids undo from simply dropping the last item.
 */
class DrawStackTest {

    // ── The cursor ────────────────────────────────────────────────────────────

    @Test
    fun `an untouched screen has nothing to undo, redo or flatten`() {
        val stack = DrawStack()

        assertFalse(stack.canUndo)
        assertFalse(stack.canRedo)
        assertTrue(stack.isPristine)
        assertNull(stack.toOp())
    }

    @Test
    fun `undo and redo walk one stroke at a time and stop at each end`() {
        val stack = DrawStack().push(pen(0)).push(pen(1)).push(pen(2))

        assertEquals(3, stack.active.size)
        assertFalse("nothing has been undone yet", stack.canRedo)

        val undone = stack.undo().undo()
        assertEquals(listOf(pen(0)), undone.active)
        assertTrue(undone.canRedo)

        val bottom = undone.undo().undo().undo()
        assertEquals(emptyList<Stroke>(), bottom.active)
        assertFalse("undo stops at the untouched photo", bottom.canUndo)

        val top = bottom.redo().redo().redo().redo()
        assertEquals(3, top.active.size)
        assertFalse("redo stops at the newest stroke", top.canRedo)
    }

    @Test
    fun `a stroke drawn after an undo discards what redo was holding`() {
        val stack = DrawStack().push(pen(0)).push(pen(1)).undo().push(pen(2))

        assertEquals(listOf(pen(0), pen(2)), stack.active)
        assertFalse("the abandoned branch is gone, not parked", stack.canRedo)
    }

    @Test
    fun `the op carries only the strokes the cursor is standing on`() {
        val op = DrawStack().push(pen(0)).push(pen(1)).undo().toOp()

        assertEquals(RasterOp.Strokes(listOf(pen(0))), op)
    }

    @Test
    fun `undoing every stroke leaves nothing to flatten`() {
        // Not an empty drawing painted over the photo — a Done that flattened
        // one would burn a history step and a generation of JPEG quality to
        // change nothing.
        assertNull(DrawStack().push(pen(0)).undo().toOp())
    }

    // ── The saver ─────────────────────────────────────────────────────────────

    @Test
    fun `a drawing round-trips through the saver with its tools, colours and cursor`() {
        val stack = DrawStack()
            .push(Stroke(StrokeTool.PEN, 0xFFE53935, 0.02f, listOf(StrokePoint(0.1f, 0.2f), StrokePoint(0.3f, 0.4f))))
            .push(Stroke(StrokeTool.HIGHLIGHTER, 0xFFFFD600, 0.05f, listOf(StrokePoint(0.5f, 0.5f))))
            .push(Stroke(StrokeTool.BLUR, 0, 0.08f, listOf(StrokePoint(0.9f, 0.1f), StrokePoint(0.95f, 0.15f))))
            .undo()

        val restored = roundTrip(stack)

        assertEquals(stack.strokes.map { it.tool }, restored.strokes.map { it.tool })
        assertEquals(stack.strokes.map { it.colorArgb }, restored.strokes.map { it.colorArgb })
        assertEquals(stack.strokes.map { it.width }, restored.strokes.map { it.width })
        // The cursor, not just the strokes: a saver that restored the drawing
        // but not where undo had walked to would quietly re-apply strokes the
        // user had just taken back.
        assertEquals(2, restored.cursor)
        assertTrue(restored.canRedo)
        stack.strokes.zip(restored.strokes).forEach { (before, after) ->
            before.points.zip(after.points).forEach { (a, b) ->
                assertEquals(a.x, b.x, 1e-4f)
                assertEquals(a.y, b.y, 1e-4f)
            }
        }
    }

    @Test
    fun `a stroke that ran off the photo keeps its out-of-range points`() {
        // The mapper does not clamp, so a finger that left the image stores
        // values outside 0..1 and the flatten clips them against the bitmap.
        val stroke = Stroke(StrokeTool.PEN, 0xFFFFFFFF, 0.02f, listOf(StrokePoint(-0.2f, 0.5f), StrokePoint(1.4f, 0.5f)))

        val restored = roundTrip(DrawStack().push(stroke)).strokes.single()

        assertEquals(-0.2f, restored.points.first().x, 1e-4f)
        assertEquals(1.4f, restored.points.last().x, 1e-4f)
    }

    @Test
    fun `a stroke longer than the saver's budget is thinned rather than dropped`() {
        val marathon = Stroke(
            tool = StrokeTool.BLUR,
            colorArgb = 0,
            width = 0.05f,
            points = (0..4000).map { StrokePoint(it / 4000f, 0.5f) },
        )

        val restored = roundTrip(DrawStack().push(marathon)).strokes.single()

        assertTrue(restored.points.size <= StrokeGeometry.MAX_SAVED_POINTS)
        assertEquals(0f, restored.points.first().x, 1e-4f)
        assertEquals(1f, restored.points.last().x, 1e-4f)
    }

    @Test
    fun `an unparseable stroke is dropped rather than taking the screen with it`() {
        @Suppress("UNCHECKED_CAST")
        val restored = DrawStack.StackSaver.restore(listOf("1", "NONSENSE") as Any)

        assertNotNull(restored)
        assertEquals(emptyList<Stroke>(), restored?.strokes)
        assertEquals(0, restored?.cursor)
    }

    private fun roundTrip(stack: DrawStack): DrawStack {
        val saved = with(DrawStack.StackSaver) { FakeSaverScope.save(stack) }
        return requireNotNull(DrawStack.StackSaver.restore(requireNotNull(saved)))
    }

    /** Every value the saver emits is a String, so nothing needs vetoing. */
    private object FakeSaverScope : androidx.compose.runtime.saveable.SaverScope {
        override fun canBeSaved(value: Any): Boolean = true
    }

    private fun pen(seed: Int) = Stroke(
        tool = StrokeTool.PEN,
        colorArgb = 0xFFFFFFFF,
        width = StrokeGeometry.DEFAULT_WIDTH,
        points = listOf(StrokePoint(seed / 10f, 0.1f), StrokePoint(seed / 10f, 0.9f)),
    )
}
