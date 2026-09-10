package com.firestream.chat.ui.chat.imageedit

import androidx.compose.runtime.Immutable
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.listSaver
import com.firestream.chat.domain.util.RasterOp

/** Which adjust tool the bottom panel is showing. */
internal enum class AdjustTool {
    /** No panel — the photo, the tool row, and nothing over the picture. */
    NONE,
    STRAIGHTEN,
    CROP,
    RESIZE,
}

/**
 * The adjust screen's transform stack: the ops the user has built up, and how
 * far along them undo has walked.
 *
 * A cursor rather than a stack, for the same reason `PendingMedia` keeps one
 * (`.claude/plans/image-editor.md` §2.7): redo has to be able to go forward
 * again, so undo moves the cursor instead of dropping the op. History is
 * **linear** — committing anything while the cursor is not at the top discards
 * what redo was holding, because branch management inside a send preview is a
 * UI nobody wants and nobody would find.
 *
 * Nothing here is flattened. Unlike the preview screen's history, whose steps
 * are files on disk, these are still four numbers in a list, so undo and redo
 * are free and [reset] is just an empty stack.
 */
@Immutable
internal data class AdjustStack(
    val ops: List<RasterOp> = emptyList(),
    val cursor: Int = 0,
) {
    /** The ops that are actually in effect — everything up to the cursor. */
    val active: List<RasterOp> get() = ops.take(cursor)

    val canUndo: Boolean get() = cursor > 0
    val canRedo: Boolean get() = cursor < ops.size

    /** True when the photo is untouched, which is when Done has nothing to do. */
    val isPristine: Boolean get() = cursor == 0

    /** Appends [op] as a new step, discarding whatever redo was holding. */
    fun push(op: RasterOp): AdjustStack {
        val next = active + op
        return AdjustStack(next, next.size)
    }

    /**
     * Replaces the top step with [op] when it is the same kind of op, and
     * appends otherwise; a null [op] removes that top step instead.
     *
     * This is what keeps the straighten slider and the resize row from writing
     * a step per tap. Dragging the slider from 0° to 3° to 5° is *one* thing
     * the user did, and it is not even decomposable: 3° then 2° is not 5°,
     * because the second rotation acts on the already-cropped result of the
     * first. So the op is edited in place, and undo steps off the whole
     * straighten — which is the unit anyone would expect back.
     *
     * Re-entering the tool later edits that same step for the same reason, so
     * the slider comes back showing the angle the photo actually has rather
     * than at zero.
     */
    fun collapse(op: RasterOp?, sameKind: (RasterOp) -> Boolean): AdjustStack {
        val base = active
        val trimmed = if (base.lastOrNull()?.let(sameKind) == true) base.dropLast(1) else base
        val next = if (op == null) trimmed else trimmed + op
        return AdjustStack(next, next.size)
    }

    fun undo(): AdjustStack = if (canUndo) copy(cursor = cursor - 1) else this

    fun redo(): AdjustStack = if (canRedo) copy(cursor = cursor + 1) else this

    /**
     * Back to the untouched photo, forgetting the redo tail as well.
     *
     * Reset is the all-at-once escape and is deliberately *not* itself an
     * undoable step: it exists for "start again", and a reset you could undo
     * would leave the user pressing undo to find out which of the two meanings
     * it had this time.
     */
    fun reset(): AdjustStack = AdjustStack()

    /**
     * The trailing [RasterOp.Straighten]'s angle, or `0` — what the slider
     * shows when the straighten tool opens.
     */
    fun trailingAngle(): Float = (active.lastOrNull() as? RasterOp.Straighten)?.degrees ?: 0f

    /** The trailing [RasterOp.Resize]'s long edge, or null for "Original". */
    fun trailingLongEdge(): Int? = (active.lastOrNull() as? RasterOp.Resize)?.longEdge

    companion object {
        /**
         * Survives rotation and process death, so a half-finished crop is not
         * thrown away by turning the phone — the failure the plan's
         * "check it in both orientations" is most likely to find.
         *
         * Encoded as strings rather than made `Parcelable`: [RasterOp] lives in
         * `domain/`, which has no Android types in it at all and is not the
         * place to acquire one for the sake of one screen's saver. The codec is
         * pure and tested, which is the same trade `PendingMedia.ListSaver`
         * makes when it joins a variable-length history into one field.
         */
        val StackSaver: Saver<AdjustStack, Any> = listSaver(
            save = { stack -> listOf(stack.cursor.toString()) + stack.ops.map(::encodeOp) },
            restore = { flat ->
                val ops = flat.drop(1).mapNotNull(::decodeOp)
                AdjustStack(ops, (flat.firstOrNull()?.toIntOrNull() ?: ops.size).coerceIn(0, ops.size))
            },
        )
    }
}

/**
 * The ops the *preview bitmap* is rendered with, which is not the same list as
 * what Done will flatten.
 *
 * Two ops come out, each for a reason that keeps the screen responsive without
 * ever letting it lie about the result:
 *
 * - **The straighten the slider is currently driving.** It is applied live as a
 *   rotation and an upscale over the rendered bitmap instead, so dragging the
 *   slider costs no decode. Leaving it in as well would apply it twice.
 * - **Every resize.** A resize changes how many pixels the *file* has and
 *   nothing about what the screen shows — the preview is scaled to fit either
 *   way. Baking one in would only re-decode the photo smaller and make the
 *   preview softer for no visible gain. Safe because every other op is
 *   scale-invariant: crops are fractions, and a straighten's scale depends only
 *   on the aspect ratio.
 */
internal fun previewOps(active: List<RasterOp>, tool: AdjustTool): List<RasterOp> {
    val withoutLiveStraighten =
        if (tool == AdjustTool.STRAIGHTEN && active.lastOrNull() is RasterOp.Straighten) {
            active.dropLast(1)
        } else {
            active
        }
    return withoutLiveStraighten.filterNot { it is RasterOp.Resize }
}

/** `rotate:90`, `crop:0.1,0.0,0.9,1.0`, … — see [AdjustStack.StackSaver]. */
private fun encodeOp(op: RasterOp): String = when (op) {
    is RasterOp.Rotate -> "rotate:${op.degrees}"
    is RasterOp.Flip -> "flip:${if (op.horizontal) "h" else "v"}"
    is RasterOp.Straighten -> "straighten:${op.degrees}"
    is RasterOp.Crop -> "crop:${op.left},${op.top},${op.right},${op.bottom}"
    is RasterOp.Resize -> "resize:${op.longEdge}"
    // The adjust screen never builds either — a drawing belongs to the draw
    // screen and a placement to the overlay screen, each with its own stack — so
    // both encode to something `decodeOp` refuses,
    // rather than teaching this saver a stroke format it can never be handed.
    is RasterOp.Strokes -> UNSUPPORTED_OP
    is RasterOp.Overlays -> UNSUPPORTED_OP
}

/** What [encodeOp] writes for an op this screen cannot produce; [decodeOp] drops it. */
private const val UNSUPPORTED_OP = "?"

/** Null for anything unparseable — a dropped op loses a step, a throw loses the screen. */
private fun decodeOp(encoded: String): RasterOp? {
    val kind = encoded.substringBefore(':', missingDelimiterValue = "")
    val value = encoded.substringAfter(':', missingDelimiterValue = "")
    return when (kind) {
        "rotate" -> value.toIntOrNull()?.let(RasterOp::Rotate)
        "flip" -> when (value) {
            "h" -> RasterOp.Flip(horizontal = true)
            "v" -> RasterOp.Flip(horizontal = false)
            else -> null
        }

        "straighten" -> value.toFloatOrNull()?.let(RasterOp::Straighten)
        "crop" -> value.split(',').mapNotNull(String::toFloatOrNull)
            .takeIf { it.size == 4 }
            ?.let { RasterOp.Crop(it[0], it[1], it[2], it[3]) }

        "resize" -> value.toIntOrNull()?.let(RasterOp::Resize)
        else -> null
    }
}
