package com.firestream.chat.ui.chat.imageedit

import androidx.compose.runtime.Immutable
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.listSaver
import com.firestream.chat.domain.util.RasterOp

/**
 * The crop frame, in fractions of the image it is drawn over.
 *
 * Normalized rather than in pixels for the same reason overlay geometry is
 * (`.claude/plans/image-editor.md` §2.3): the frame means "the middle 60% of
 * this photo", not "212 px into the canvas I happened to be laid out in", so it
 * survives a device rotation and a screen-size change without drifting, and it
 * converts to a [RasterOp.Crop] with no arithmetic at all.
 */
@Immutable
internal data class CropRect(
    val left: Float,
    val top: Float,
    val right: Float,
    val bottom: Float,
) {
    val width: Float get() = right - left
    val height: Float get() = bottom - top
    val centerX: Float get() = (left + right) / 2f
    val centerY: Float get() = (top + bottom) / 2f

    /** True when this frame keeps the whole image, i.e. there is nothing to crop. */
    val isFull: Boolean
        get() = left <= TOUCHING && top <= TOUCHING &&
            right >= 1f - TOUCHING && bottom >= 1f - TOUCHING

    /** The op that flattens this frame, or null when it would keep everything. */
    fun toOp(): RasterOp.Crop? = if (isFull) null else RasterOp.Crop(left, top, right, bottom)

    companion object {
        val Full = CropRect(0f, 0f, 1f, 1f)

        /**
         * Survives a rotation, because the frame is normalized to the image and
         * therefore means the same thing in either orientation — a saver that
         * dropped it would throw away a half-finished crop for turning the
         * phone, which is the failure the plan's "check it in both
         * orientations" is most likely to find.
         */
        val Saver: Saver<CropRect, Any> = listSaver(
            save = { listOf(it.left, it.top, it.right, it.bottom) },
            restore = { CropRect(it[0], it[1], it[2], it[3]) },
        )

        /**
         * Below this, a frame edge is on the image edge as far as anyone can
         * tell. A drag lands on sub-pixel floats, so an exact `== 0f` test
         * would keep offering to crop a frame the user has dragged back to the
         * corner and make Done write a byte-identical copy of the photo.
         */
        private const val TOUCHING = 0.001f
    }
}

/**
 * A distance in from each physical edge, in pixels — left and right, never
 * start and end, because the screen edges a system gesture strip hugs do not
 * swap sides in a right-to-left layout.
 */
@Immutable
internal data class EdgeInsetsPx(
    val left: Float,
    val top: Float,
    val right: Float,
    val bottom: Float,
) {
    /** Every edge multiplied by [fraction] — how the crop margin animates in and out. */
    fun scaled(fraction: Float): EdgeInsetsPx =
        EdgeInsetsPx(left * fraction, top * fraction, right * fraction, bottom * fraction)

    companion object {
        val Zero = EdgeInsetsPx(0f, 0f, 0f, 0f)
    }
}

/** Which corner of the crop frame a drag has hold of. */
internal enum class CropHandle(val onLeft: Boolean, val onTop: Boolean) {
    TOP_LEFT(onLeft = true, onTop = true),
    TOP_RIGHT(onLeft = false, onTop = true),
    BOTTOM_LEFT(onLeft = true, onTop = false),
    BOTTOM_RIGHT(onLeft = false, onTop = false),
}

/**
 * The aspect presets on the crop row.
 *
 * [fixedPixelRatio] is the ratio the *output file* must have, which is not the
 * ratio the frame has on screen: the frame is normalized to the image, so a 1:1
 * crop of a 4000 × 3000 photo is a frame three-quarters as wide as it is tall.
 * [CropGeometry.normalizedRatio] is the conversion, and forgetting it is the
 * bug this type exists to make hard.
 *
 * It is null for the two presets that have no *fixed* ratio, and they are null
 * for opposite reasons — [FREE] has no constraint at all, while [ORIGINAL]'s
 * constraint is the image's own ratio and so cannot be a constant.
 * [CropGeometry.pixelAspect] is the one place that distinction is resolved;
 * read that rather than this field.
 */
internal enum class CropAspect(val label: String, val fixedPixelRatio: Float?) {
    FREE("Free", null),
    ORIGINAL("Original", null),
    SQUARE("1:1", 1f),
    PORTRAIT("4:5", 4f / 5f),
    WIDE("16:9", 16f / 9f),
}

/**
 * Where a crop frame goes when a corner is dragged, an aspect is chosen, or the
 * frame is pushed around — all of it pure arithmetic over normalized floats.
 *
 * Separated from the screen for the reason the plan gives the whole editor
 * (§3): everything here is a gesture over a coordinate mapping, and that is
 * exactly the class of bug a Robolectric test passes through. Keeping the
 * arithmetic out of the composable means a JVM test can pin the cases a finger
 * on glass would take an afternoon to reach — dragging a corner past its
 * opposite, past the edge of the photo, or into an aspect ratio that no longer
 * fits where the drag is pulling it.
 */
internal object CropGeometry {

    /**
     * The smallest frame a drag may leave, as a fraction of each edge.
     *
     * Small enough that cropping a face out of a group photo still works, large
     * enough that the four 48 dp handles never end up stacked on top of each
     * other with no way to tell which one the finger has.
     */
    const val MIN_SIDE = 0.05f

    /**
     * The output ratio [aspect] asks for, or null when the corners are free.
     *
     * The one place [CropAspect.FREE] and [CropAspect.ORIGINAL] stop looking
     * alike: both carry a null ratio, but Original's is null only because it
     * cannot be a constant, and it is resolved against the image here.
     */
    fun pixelAspect(aspect: CropAspect, imageWidth: Int, imageHeight: Int): Float? = when {
        aspect == CropAspect.ORIGINAL && imageWidth > 0 && imageHeight > 0 ->
            imageWidth.toFloat() / imageHeight
        else -> aspect.fixedPixelRatio
    }

    /**
     * [aspect] as a width-to-height ratio of the *normalized frame*, or null
     * when the corners are free.
     *
     * A frame of `nw × nh` covers `nw·W × nh·H` real pixels, so an output ratio
     * of `t` needs `nw / nh = t · H / W`.
     */
    fun normalizedRatio(aspect: CropAspect, imageWidth: Int, imageHeight: Int): Float? {
        if (imageWidth <= 0 || imageHeight <= 0) return null
        val pixels = pixelAspect(aspect, imageWidth, imageHeight) ?: return null
        val ratio = pixels * imageHeight / imageWidth
        return if (ratio.isFinite() && ratio > 0f) ratio else null
    }

    /**
     * The largest centred frame with [aspect]'s ratio — where choosing a preset
     * puts the frame.
     *
     * Choosing a preset resets rather than fitting the ratio inside whatever
     * frame was there before, which would shrink the crop a little more on
     * every tap and give the row a memory nobody asked it to have.
     */
    fun centered(aspect: CropAspect, imageWidth: Int, imageHeight: Int): CropRect {
        val ratio = normalizedRatio(aspect, imageWidth, imageHeight) ?: return CropRect.Full
        val width = if (ratio >= 1f) 1f else ratio
        val height = width / ratio
        return centeredOn(0.5f, 0.5f, width, height)
    }

    /**
     * The frame after [handle] is dragged to ([x], [y]) in normalized space.
     *
     * The opposite corner is the anchor and never moves, which is what makes a
     * corner drag feel like a corner drag. Under an aspect constraint the frame
     * has one degree of freedom, so the drag is resolved to a width — the
     * larger of what each axis asks for, so the frame follows whichever way the
     * finger is really pulling — and the height follows from the ratio.
     *
     * Everything is then clamped twice: to the image, so the frame cannot leave
     * the photo, and to [MIN_SIDE], so it cannot collapse to nothing. When
     * those two fight, the image wins and the frame is merely as small as it
     * can be — a frame that reaches outside the photo would crop black.
     */
    fun drag(
        rect: CropRect,
        handle: CropHandle,
        x: Float,
        y: Float,
        aspect: CropAspect,
        imageWidth: Int,
        imageHeight: Int,
    ): CropRect {
        val anchorX = if (handle.onLeft) rect.right else rect.left
        val anchorY = if (handle.onTop) rect.bottom else rect.top
        val availableWidth = if (handle.onLeft) anchorX else 1f - anchorX
        val availableHeight = if (handle.onTop) anchorY else 1f - anchorY
        if (availableWidth <= 0f || availableHeight <= 0f) return rect

        val pointX = x.coerceIn(0f, 1f)
        val pointY = y.coerceIn(0f, 1f)
        var width = kotlin.math.abs(pointX - anchorX)
        var height = kotlin.math.abs(pointY - anchorY)

        val ratio = normalizedRatio(aspect, imageWidth, imageHeight)
        if (ratio == null) {
            width = width.coerceIn(minOf(MIN_SIDE, availableWidth), availableWidth)
            height = height.coerceIn(minOf(MIN_SIDE, availableHeight), availableHeight)
        } else {
            val ceiling = minOf(availableWidth, availableHeight * ratio)
            val floor = maxOf(MIN_SIDE, MIN_SIDE * ratio)
            width = maxOf(width, height * ratio).coerceIn(minOf(floor, ceiling), ceiling)
            height = width / ratio
        }

        val left = if (handle.onLeft) anchorX - width else anchorX
        val top = if (handle.onTop) anchorY - height else anchorY
        return CropRect(left, top, left + width, top + height)
    }

    /**
     * The frame moved by ([dx], [dy]), stopped at the edges of the image rather
     * than sliding off them — a frame is dragged around to reframe a subject,
     * and letting it leave the photo would mean cropping black.
     */
    fun move(rect: CropRect, dx: Float, dy: Float): CropRect {
        val clampedX = dx.coerceIn(-rect.left, 1f - rect.right)
        val clampedY = dy.coerceIn(-rect.top, 1f - rect.bottom)
        return CropRect(
            left = rect.left + clampedX,
            top = rect.top + clampedY,
            right = rect.right + clampedX,
            bottom = rect.bottom + clampedY,
        )
    }

    /**
     * The handle a touch at ([x], [y]) has hold of, or null when the touch is
     * not near a corner.
     *
     * The tolerances are per-axis and in normalized units because that is what
     * a fixed dp radius becomes once the fit rect has scaled it — a 24 dp grab
     * radius is a much larger fraction of a narrow photo than of a wide one, so
     * one shared number would make the corners of a portrait shot harder to
     * grab than the corners of a landscape one.
     *
     * Ties go to the nearest corner. A touch inside a small frame can be within
     * tolerance of all four at once, and picking by distance is the only answer
     * that matches which one the user is looking at.
     */
    fun handleAt(
        rect: CropRect,
        x: Float,
        y: Float,
        toleranceX: Float,
        toleranceY: Float,
    ): CropHandle? {
        var best: CropHandle? = null
        var bestDistance = Float.MAX_VALUE
        for (handle in CropHandle.entries) {
            val cornerX = if (handle.onLeft) rect.left else rect.right
            val cornerY = if (handle.onTop) rect.top else rect.bottom
            val dx = (x - cornerX) / toleranceX.coerceAtLeast(MIN_TOLERANCE)
            val dy = (y - cornerY) / toleranceY.coerceAtLeast(MIN_TOLERANCE)
            val distance = dx * dx + dy * dy
            if (distance <= 1f && distance < bestDistance) {
                best = handle
                bestDistance = distance
            }
        }
        return best
    }

    /**
     * How far inside its canvas the photo is fitted while the crop tool is
     * open, so that the whole grab target around every corner can be touched.
     *
     * [canvas] is how far each edge of the canvas already sits from the window
     * edge (the system bars plus the editor's own bars); [gestures] is how far
     * the system gesture strips reach in from those same window edges. A target
     * that reaches into a strip is not a target: the system takes a touch that
     * lands there — back on the sides, home at the bottom — before the app sees
     * it. A mouse in the emulator never triggers a gesture, which is how corners
     * on the edge of the glass looked fine until a finger tried them.
     *
     * So each edge first clears whatever part of its strip the canvas does not
     * already clear, then adds a whole [grabRadius], so a corner on the photo's
     * edge has its entire target inside the canvas rather than half of it past
     * the edge of the screen. Pure because Robolectric reports no gesture insets
     * and could never see the half of this that matters on a phone.
     */
    fun reachableInsets(canvas: EdgeInsetsPx, gestures: EdgeInsetsPx, grabRadius: Float): EdgeInsetsPx =
        EdgeInsetsPx(
            left = (gestures.left - canvas.left).coerceAtLeast(0f) + grabRadius,
            top = (gestures.top - canvas.top).coerceAtLeast(0f) + grabRadius,
            right = (gestures.right - canvas.right).coerceAtLeast(0f) + grabRadius,
            bottom = (gestures.bottom - canvas.bottom).coerceAtLeast(0f) + grabRadius,
        )

    /** True when ([x], [y]) falls inside the frame — the grab area for a move. */
    fun contains(rect: CropRect, x: Float, y: Float): Boolean =
        x >= rect.left && x <= rect.right && y >= rect.top && y <= rect.bottom

    private fun centeredOn(centerX: Float, centerY: Float, width: Float, height: Float): CropRect =
        CropRect(
            left = centerX - width / 2f,
            top = centerY - height / 2f,
            right = centerX + width / 2f,
            bottom = centerY + height / 2f,
        )

    /** Guards against a zero tolerance turning the distance test into a divide by zero. */
    private const val MIN_TOLERANCE = 0.0001f
}
