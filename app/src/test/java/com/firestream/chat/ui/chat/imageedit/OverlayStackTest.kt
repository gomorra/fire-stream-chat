package com.firestream.chat.ui.chat.imageedit

import androidx.compose.runtime.saveable.SaverScope
import com.firestream.chat.domain.util.ImageOverlay
import com.firestream.chat.domain.util.OverlayContent
import com.firestream.chat.domain.util.OverlayGeometry
import com.firestream.chat.domain.util.RasterOp
import com.firestream.chat.domain.util.ShapeKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * What the overlay screen's history does, on the JVM.
 *
 * The rule worth pinning here is the one that made this a snapshot stack rather
 * than a list with a cursor: **delete is a step**. A prefix cursor cannot
 * express "A and B were placed, then A was deleted", so undo after a delete
 * would have been impossible — and nobody expects that.
 */
class OverlayStackTest {

    private val emoji = ImageOverlay(OverlayContent.Emoji("🎉"), 0.5f, 0.5f)
    private val sticker = ImageOverlay(OverlayContent.Sticker("heart"), 0.2f, 0.8f)

    // ── Placing ──────────────────────────────────────────────────────────────

    @Test
    fun `a fresh stack has nothing to flatten and nothing to undo`() {
        val stack = OverlayStack()

        assertTrue(stack.isPristine)
        assertFalse(stack.canUndo)
        assertFalse(stack.canRedo)
        assertNull(stack.toOp())
    }

    @Test
    fun `placing appends on top and becomes a step`() {
        val stack = OverlayStack().place(emoji).place(sticker)

        assertEquals(listOf(emoji, sticker), stack.overlays)
        assertTrue(stack.canUndo)
        assertEquals(RasterOp.Overlays(listOf(emoji, sticker)), stack.toOp())
    }

    @Test
    fun `undo walks back one placement at a time and redo walks forward`() {
        val stack = OverlayStack().place(emoji).place(sticker)

        val undone = stack.undo()
        assertEquals(listOf(emoji), undone.overlays)

        val again = undone.undo()
        assertEquals(emptyList<ImageOverlay>(), again.overlays)
        assertFalse(again.canUndo)

        assertEquals(listOf(emoji, sticker), again.redo().redo().overlays)
    }

    @Test
    fun `placing after an undo discards what redo was holding`() {
        val stack = OverlayStack().place(emoji).place(sticker).undo().place(sticker)

        assertFalse("the abandoned branch must not be reachable", stack.canRedo)
        assertEquals(listOf(emoji, sticker), stack.overlays)
    }

    // ── Deleting — the reason this is a snapshot stack ───────────────────────

    @Test
    fun `deleting is a step, so undo brings the object back`() {
        val stack = OverlayStack().place(emoji).place(sticker).delete(0)

        assertEquals(listOf(sticker), stack.overlays)
        // The thing a list-with-a-cursor could not do: no prefix of [A, B] is [B].
        assertEquals(listOf(emoji, sticker), stack.undo().overlays)
    }

    @Test
    fun `deleting everything leaves nothing to flatten`() {
        val stack = OverlayStack().place(emoji).delete(0)

        assertTrue(stack.isPristine)
        assertNull(stack.toOp())
    }

    @Test
    fun `an index nothing is at leaves the stack alone`() {
        val stack = OverlayStack().place(emoji)

        assertEquals(stack, stack.delete(7))
        assertEquals(stack, stack.adjust(7) { it.copy(scale = 3f) })
    }

    // ── Adjusting — deliberately not a step ─────────────────────────────────

    @Test
    fun `moving and scaling collapse into the placement rather than becoming steps`() {
        val stack = OverlayStack().place(emoji)
            .adjust(0) { it.copy(centerX = 0.9f) }
            .adjust(0) { it.copy(scale = 2f) }

        // One placement, one undo: the unit a user expects back is the emoji,
        // not the last two millimetres they dragged it.
        assertEquals(0.9f, stack.overlays.single().centerX, 0.001f)
        assertEquals(2f, stack.overlays.single().scale, 0.001f)
        assertTrue(stack.canUndo)
        assertTrue(stack.undo().isPristine)
    }

    @Test
    fun `adjusting does not discard a redo, because nothing new has happened`() {
        val stack = OverlayStack().place(emoji).place(sticker).undo().adjust(0) { it.copy(scale = 2f) }

        assertTrue("the user is still adjusting, not replacing", stack.canRedo)
    }

    // ── Bounds ───────────────────────────────────────────────────────────────

    @Test
    fun `the cap refuses one more rather than silently dropping an older one`() {
        var stack = OverlayStack()
        repeat(OverlayGeometry.MAX_OVERLAYS) { stack = stack.place(emoji) }

        assertTrue(stack.isFull)
        assertEquals(stack, stack.place(sticker))
    }

    @Test
    fun `history depth is bounded and the cursor stays on the newest step`() {
        var stack = OverlayStack()
        repeat(OverlayStack.MAX_STEPS + 10) { stack = stack.place(emoji) }

        assertTrue(stack.steps.size <= OverlayStack.MAX_STEPS)
        assertEquals(stack.steps.lastIndex, stack.cursor)
        assertFalse(stack.canRedo)
    }

    // ── The saver ────────────────────────────────────────────────────────────

    @Test
    fun `every kind of overlay round-trips through the saver`() {
        val stack = OverlayStack()
            .place(ImageOverlay(OverlayContent.Emoji("🎉"), 0.1f, 0.2f, 1.5f, 30f))
            .place(ImageOverlay(OverlayContent.Sticker("heart"), 0.3f, 0.4f))
            .place(ImageOverlay(OverlayContent.Text("hi there", 0xFFE53935, filled = false), 0.5f, 0.6f))
            .place(ImageOverlay(OverlayContent.Shape(ShapeKind.ARROW, 0xFF1E88E5, filled = true), 0.7f, 0.8f))

        assertEquals(stack, roundTrip(stack))
    }

    @Test
    fun `the cursor survives, so a rotation does not re-apply what was undone`() {
        val stack = OverlayStack().place(emoji).place(sticker).undo()

        val restored = roundTrip(stack)

        assertEquals(listOf(emoji), restored.overlays)
        assertTrue(restored.canRedo)
    }

    @Test
    fun `a text run keeps its own separators and loses only its newlines`() {
        val awkward = ImageOverlay(OverlayContent.Text("a|b\nc", 0xFFFFFFFF, filled = true), 0.5f, 0.5f)

        val restored = roundTrip(OverlayStack().place(awkward))

        // The pipe is the field separator and the newline is the step separator;
        // only the one that would corrupt the save is taken away.
        assertEquals("a|b c", (restored.overlays.single().content as OverlayContent.Text).text)
    }

    @Suppress("UNCHECKED_CAST")
    private fun roundTrip(stack: OverlayStack): OverlayStack {
        val scope = SaverScope { true }
        val saved = with(OverlayStack.StackSaver) { scope.save(stack) }
        return OverlayStack.StackSaver.restore(saved!!)!!
    }
}
