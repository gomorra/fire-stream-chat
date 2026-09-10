package com.firestream.chat.ui.chat.imageedit

import androidx.compose.runtime.Immutable
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.listSaver
import com.firestream.chat.domain.util.ImageOverlay
import com.firestream.chat.domain.util.OverlayContent
import com.firestream.chat.domain.util.OverlayGeometry
import com.firestream.chat.domain.util.RasterOp
import com.firestream.chat.domain.util.ShapeKind

/**
 * The overlay screen's placed objects, and how far along them undo has walked.
 *
 * ### Why this keeps whole states and not a list of placements
 *
 * [DrawStack] and [AdjustStack] can be a list with a cursor because their unit
 * of work only ever *appends*: a stroke or a transform is added at the end, so
 * "everything up to the cursor" is a complete description of what is in effect.
 * This screen has an operation those two do not — **delete** — and a delete in
 * the middle breaks that description completely: after placing A and B and
 * deleting A, no prefix of `[A, B]` is `[B]`.
 *
 * So a step here is the whole set of objects rather than one more object, and
 * undo/redo move between snapshots. It costs a few dozen small immutable
 * objects, which is nothing beside a screen already holding a decoded bitmap,
 * and it buys the thing a user unambiguously expects: deleting something and
 * undoing brings it back.
 *
 * ### What is a step and what is not
 *
 * **Placements and deletions are steps; moving, scaling and rotating are not**
 * (`.claude/plans/image-editor.md` §3 Phase 5). The unit people expect back is
 * "that emoji I just added", not "the last two millimetres I dragged it" — so a
 * drag edits the current step in place ([adjust]) while a placement commits a
 * new one ([place]). History is **linear**, like both other stacks: placing
 * anything while the cursor is not at the top discards what redo was holding.
 */
@Immutable
internal data class OverlayStack(
    /** Every state the photo's overlays have been in, oldest first; the first is empty. */
    val steps: List<List<ImageOverlay>> = listOf(emptyList()),
    val cursor: Int = 0,
) {
    /** The objects actually on the photo right now. */
    val overlays: List<ImageOverlay> get() = steps.getOrElse(cursor) { emptyList() }

    val canUndo: Boolean get() = cursor > 0
    val canRedo: Boolean get() = cursor < steps.lastIndex

    /** True when nothing is on the photo, which is when Done has nothing to do. */
    val isPristine: Boolean get() = overlays.isEmpty()

    /** True when one more object would be one too many — see [OverlayGeometry.MAX_OVERLAYS]. */
    val isFull: Boolean get() = overlays.size >= OverlayGeometry.MAX_OVERLAYS

    /**
     * Adds [overlay] on top, discarding whatever redo was holding.
     *
     * Returns `this` unchanged at the cap rather than silently dropping the
     * oldest object: the screen turns that into a line the user can read, and a
     * placement that vanished something else would be worse than one that did
     * not happen.
     */
    fun place(overlay: ImageOverlay): OverlayStack =
        if (isFull) this else commit(overlays + overlay)

    /** Removes the object at [index] — a step, so undo brings it back. */
    fun delete(index: Int): OverlayStack {
        if (index !in overlays.indices) return this
        return commit(overlays.filterIndexed { position, _ -> position != index })
    }

    /**
     * Edits the object at [index] **without** making a history step — what a
     * drag, a scale or a rotate does.
     *
     * The tail redo was holding survives, because nothing new has happened: the
     * user is still adjusting the thing they last placed.
     */
    fun adjust(index: Int, transform: (ImageOverlay) -> ImageOverlay): OverlayStack {
        if (index !in overlays.indices) return this
        val next = overlays.mapIndexed { position, overlay ->
            if (position == index) transform(overlay) else overlay
        }
        return copy(steps = steps.toMutableList().also { it[cursor] = next })
    }

    fun undo(): OverlayStack = if (canUndo) copy(cursor = cursor - 1) else this

    fun redo(): OverlayStack = if (canRedo) copy(cursor = cursor + 1) else this

    /**
     * The one op Done flattens, or null when there is nothing on the photo.
     *
     * Every object in one op rather than one op per object: they are painted in
     * list order into a single pass over the bitmap, and applying them one after
     * another would decode and re-encode once per sticker.
     */
    fun toOp(): RasterOp.Overlays? = if (isPristine) null else RasterOp.Overlays(overlays)

    /** Appends a state, truncating the tail and dropping the oldest past [MAX_STEPS]. */
    private fun commit(next: List<ImageOverlay>): OverlayStack {
        val kept = steps.take(cursor + 1) + listOf(next)
        val overflow = (kept.size - MAX_STEPS).coerceAtLeast(0)
        return OverlayStack(steps = kept.drop(overflow), cursor = kept.lastIndex - overflow)
    }

    companion object {
        /**
         * How many states undo can walk back through.
         *
         * A bound on what `rememberSaveable` carries through a `Bundle`, for the
         * reason [com.firestream.chat.domain.util.StrokeGeometry.MAX_SAVED_POINTS]
         * is one — each step holds every object, so depth costs more here than
         * it does on the draw screen. Twenty-four placements back is far past
         * what anyone undoes on a photo they are about to send, and losing depth
         * beyond it costs nothing that is still on screen.
         */
        const val MAX_STEPS = 24

        /**
         * Survives rotation and process death, so turning the phone mid-placement
         * does not throw the placements away.
         *
         * Exercised through a real `Bundle` by `OverlayImageScreenTest`, not only
         * in isolation: Phase 3 shipped a saver that was unit-tested while the
         * screen holding it sat in a plain `remember`, which made it dead code in
         * production and lost the crop on every rotation.
         */
        val StackSaver: Saver<OverlayStack, Any> = listSaver(
            save = { stack ->
                listOf(stack.cursor.toString()) +
                    stack.steps.map { step -> step.joinToString(STEP_SEPARATOR, transform = ::encodeOverlay) }
            },
            restore = { flat ->
                val steps = flat.drop(1).map { encoded ->
                    if (encoded.isEmpty()) {
                        emptyList()
                    } else {
                        encoded.split(STEP_SEPARATOR).mapNotNull(::decodeOverlay)
                    }
                }
                if (steps.isEmpty()) {
                    OverlayStack()
                } else {
                    OverlayStack(
                        steps = steps,
                        cursor = (flat.firstOrNull()?.toIntOrNull() ?: steps.lastIndex)
                            .coerceIn(0, steps.lastIndex),
                    )
                }
            },
        )
    }
}

/**
 * `EMOJI|0.5|0.5|1.0|0.0|🎉` — kind, centre, scale, rotation, then whatever the
 * kind needs.
 *
 * A text run's own text is the **last** field and is allowed to contain the
 * separator, because a user typing `a|b` is not a corrupt save. Newlines are
 * stripped instead: they are the one character the step separator needs, and a
 * text overlay is a single line anyway.
 */
private fun encodeOverlay(overlay: ImageOverlay): String {
    val head = with(overlay) { "$centerX|$centerY|$scale|$rotationDegrees" }
    return when (val content = overlay.content) {
        is OverlayContent.Emoji -> "EMOJI|$head|${content.emoji.oneLine()}"
        is OverlayContent.Sticker -> "STICKER|$head|${content.stickerId}"
        is OverlayContent.Shape ->
            "SHAPE|$head|${content.colorArgb}|${content.filled}|${content.kind.name}"

        is OverlayContent.Text ->
            "TEXT|$head|${content.colorArgb}|${content.filled}|${content.text.oneLine()}"
    }
}

/** Null for anything unparseable — a dropped object loses one placement, a throw loses the screen. */
private fun decodeOverlay(encoded: String): ImageOverlay? {
    val parts = encoded.split('|', limit = TEXT_FIELD_COUNT)
    if (parts.size < STICKER_FIELD_COUNT) return null
    val centerX = parts[1].toFloatOrNull() ?: return null
    val centerY = parts[2].toFloatOrNull() ?: return null
    val scale = parts[3].toFloatOrNull() ?: return null
    val rotation = parts[4].toFloatOrNull() ?: return null

    val content = when (parts[0]) {
        "EMOJI" -> OverlayContent.Emoji(parts[5])
        "STICKER" -> OverlayContent.Sticker(parts[5])
        "SHAPE" -> {
            if (parts.size < SHAPE_FIELD_COUNT) return null
            val kind = ShapeKind.entries.firstOrNull { it.name == parts[7] } ?: return null
            OverlayContent.Shape(
                kind = kind,
                colorArgb = parts[5].toLongOrNull() ?: return null,
                filled = parts[6].toBooleanStrictOrNull() ?: return null,
            )
        }

        "TEXT" -> {
            if (parts.size < TEXT_FIELD_COUNT) return null
            OverlayContent.Text(
                text = parts[7],
                colorArgb = parts[5].toLongOrNull() ?: return null,
                filled = parts[6].toBooleanStrictOrNull() ?: return null,
            )
        }

        else -> return null
    }
    return ImageOverlay(
        content = content,
        centerX = centerX,
        centerY = centerY,
        scale = scale,
        rotationDegrees = rotation,
    )
}

/** Newlines are the step separator, so nothing a user typed may carry one. */
private fun String.oneLine(): String = replace('\n', ' ').replace('\r', ' ')

private const val STEP_SEPARATOR = "\n"

/** `EMOJI|cx|cy|scale|rot|value` — the shortest encoding, and the field floor for all of them. */
private const val STICKER_FIELD_COUNT = 6

/** `SHAPE|…|colour|filled|kind`. */
private const val SHAPE_FIELD_COUNT = 8

/** `TEXT|…|colour|filled|text` — the limit `split` stops at, so the text keeps its own separators. */
private const val TEXT_FIELD_COUNT = 8
