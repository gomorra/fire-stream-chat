package com.firestream.chat.ui.chat.imageedit

import androidx.compose.runtime.Immutable
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.listSaver
import com.firestream.chat.domain.util.RasterOp
import com.firestream.chat.domain.util.Stroke
import com.firestream.chat.domain.util.StrokeGeometry
import com.firestream.chat.domain.util.StrokePoint
import com.firestream.chat.domain.util.StrokeTool
import kotlin.math.roundToInt

/**
 * The draw screen's strokes, and how far along them undo has walked.
 *
 * A cursor rather than a stack, exactly as [AdjustStack] keeps one and for the
 * same reason (`.claude/plans/image-editor.md` §2.7): redo has to be able to go
 * forward again, so undo moves the cursor instead of dropping the stroke.
 * History is **linear** — drawing anything while the cursor is not at the top
 * discards what redo was holding.
 *
 * The unit is one stroke, which is the unit a finger produces: down, move, up.
 * Nothing here is flattened, so undo and redo are free.
 */
@Immutable
internal data class DrawStack(
    val strokes: List<Stroke> = emptyList(),
    val cursor: Int = 0,
) {
    /** The strokes actually in effect — everything up to the cursor. */
    val active: List<Stroke> get() = strokes.take(cursor)

    val canUndo: Boolean get() = cursor > 0
    val canRedo: Boolean get() = cursor < strokes.size

    /** True when nothing has been drawn, which is when Done has nothing to do. */
    val isPristine: Boolean get() = cursor == 0

    /** Appends [stroke], discarding whatever redo was holding. */
    fun push(stroke: Stroke): DrawStack {
        val next = active + stroke
        return DrawStack(next, next.size)
    }

    fun undo(): DrawStack = if (canUndo) copy(cursor = cursor - 1) else this

    fun redo(): DrawStack = if (canRedo) copy(cursor = cursor + 1) else this

    /**
     * The one op Done flattens, or null when there is nothing to flatten.
     *
     * Every stroke in one op rather than one op per stroke: they are painted in
     * two layers with the blur underneath ([StrokeGeometry.layers]), which is a
     * property of the drawing as a whole and cannot be expressed by applying
     * strokes one after another.
     */
    fun toOp(): RasterOp.Strokes? = if (isPristine) null else RasterOp.Strokes(active)

    companion object {
        /**
         * Survives rotation and process death, so turning the phone mid-drawing
         * does not throw the drawing away.
         *
         * Phase 3 shipped a saver that was unit-tested while the screen holding
         * it sat in a plain `remember`, which made it dead code in production
         * and lost the crop on every rotation; this one is exercised through a
         * real `Bundle` by `DrawImageScreenTest`, not only in isolation.
         *
         * **Points are quantised and long strokes are thinned**
         * ([StrokeGeometry.subsampled]). A drawing is the one piece of editor
         * state whose size the user controls — a minute of scribbling is
         * thousands of samples — and the saved-state `Bundle` is bounded. Losing
         * a little fidelity on a rotation is the right trade against a rotation
         * that cannot complete.
         */
        val StackSaver: Saver<DrawStack, Any> = listSaver(
            save = { stack ->
                listOf(stack.cursor.toString()) + stack.strokes.map(::encodeStroke)
            },
            restore = { flat ->
                val strokes = flat.drop(1).mapNotNull(::decodeStroke)
                DrawStack(
                    strokes = strokes,
                    cursor = (flat.firstOrNull()?.toIntOrNull() ?: strokes.size)
                        .coerceIn(0, strokes.size),
                )
            },
        )
    }
}

/**
 * `PEN|4294901760|0.02|1200,3400,1800,3400` — tool, ARGB, width, then the points
 * as ten-thousandths of the image.
 *
 * Quantised rather than written as floats because the text *is* the storage
 * cost: `1200` is four characters where `0.12003234` is ten, and a stroke can
 * carry hundreds of samples. A ten-thousandth of the image is a fraction of a
 * pixel even at the 4096 px working ceiling, so nothing visible is lost.
 */
private fun encodeStroke(stroke: Stroke): String {
    val points = StrokeGeometry.subsampled(stroke.points).joinToString(",") { point ->
        "${quantise(point.x)},${quantise(point.y)}"
    }
    return "${stroke.tool.name}|${stroke.colorArgb}|${stroke.width}|$points"
}

/** Null for anything unparseable — a dropped stroke loses a mark, a throw loses the screen. */
private fun decodeStroke(encoded: String): Stroke? {
    val parts = encoded.split('|')
    if (parts.size != 4) return null
    val tool = StrokeTool.entries.firstOrNull { it.name == parts[0] } ?: return null
    val color = parts[1].toLongOrNull() ?: return null
    val width = parts[2].toFloatOrNull() ?: return null
    val numbers = parts[3].split(',').mapNotNull(String::toIntOrNull)
    if (numbers.isEmpty() || numbers.size % 2 != 0) return null
    val points = numbers.chunked(2) { pair -> StrokePoint(unquantise(pair[0]), unquantise(pair[1])) }
    return Stroke(tool = tool, colorArgb = color, width = width, points = points)
}

/** Ten-thousandths of the image; see [encodeStroke]. */
private const val QUANTUM = 10_000f

private fun quantise(value: Float): Int = (value * QUANTUM).roundToInt()

private fun unquantise(value: Int): Float = value / QUANTUM
