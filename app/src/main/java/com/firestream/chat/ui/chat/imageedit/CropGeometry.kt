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

/**
 * Which grip of the crop frame a drag has hold of: a corner, which owns two
 * edges, or the middle of a side, which owns one.
 *
 * [onLeft] and [onTop] say which edge the grip owns on each axis, and are null
 * on the axis a side grip leaves alone — [LEFT] moves the left edge and nothing
 * vertical, so it has no top-or-bottom answer to give.
 */
internal enum class CropHandle(val onLeft: Boolean?, val onTop: Boolean?) {
    TOP_LEFT(onLeft = true, onTop = true),
    TOP_RIGHT(onLeft = false, onTop = true),
    BOTTOM_LEFT(onLeft = true, onTop = false),
    BOTTOM_RIGHT(onLeft = false, onTop = false),
    LEFT(onLeft = true, onTop = null),
    RIGHT(onLeft = false, onTop = null),
    TOP(onLeft = null, onTop = true),
    BOTTOM(onLeft = null, onTop = false),
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
     * enough that the eight 48 dp handles never end up stacked on top of each
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
     * The opposite corner — or, for a side grip, the opposite side — is the
     * anchor and never moves, which is what makes a corner drag feel like a
     * corner drag. Under an aspect constraint the frame has one degree of
     * freedom, so a corner drag is resolved to a width — the larger of what each
     * axis asks for, so the frame follows whichever way the finger is really
     * pulling — and the height follows from the ratio. A side drag has only its
     * own axis to go on; [dragSide] says where the other one goes.
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
        val ratio = normalizedRatio(aspect, imageWidth, imageHeight)
        val onLeft = handle.onLeft
        val onTop = handle.onTop
        return when {
            onLeft != null && onTop != null -> dragCorner(rect, onLeft, onTop, x, y, ratio)
            onLeft != null -> {
                val (horizontal, vertical) = dragSide(
                    along = Span(rect.left, rect.right),
                    across = Span(rect.top, rect.bottom),
                    fromStart = onLeft,
                    point = x,
                    ratio = ratio,
                )
                CropRect(horizontal.start, vertical.start, horizontal.end, vertical.end)
            }
            onTop != null -> {
                val (vertical, horizontal) = dragSide(
                    along = Span(rect.top, rect.bottom),
                    across = Span(rect.left, rect.right),
                    fromStart = onTop,
                    point = y,
                    // The ratio is width over height; down this axis it is the
                    // other way up.
                    ratio = ratio?.let { 1f / it },
                )
                CropRect(horizontal.start, vertical.start, horizontal.end, vertical.end)
            }
            else -> rect
        }
    }

    private fun dragCorner(
        rect: CropRect,
        onLeft: Boolean,
        onTop: Boolean,
        x: Float,
        y: Float,
        ratio: Float?,
    ): CropRect {
        val anchorX = if (onLeft) rect.right else rect.left
        val anchorY = if (onTop) rect.bottom else rect.top
        val availableWidth = if (onLeft) anchorX else 1f - anchorX
        val availableHeight = if (onTop) anchorY else 1f - anchorY
        if (availableWidth <= 0f || availableHeight <= 0f) return rect

        val pointX = x.coerceIn(0f, 1f)
        val pointY = y.coerceIn(0f, 1f)
        // Signed, not absolute: a finger that has crossed the anchor is asking for
        // less than nothing, which the clamps below turn into the minimum. An
        // absolute distance mirrored it instead, growing the frame back out as
        // the finger kept going past the opposite corner.
        var width = if (onLeft) anchorX - pointX else pointX - anchorX
        var height = if (onTop) anchorY - pointY else pointY - anchorY

        if (ratio == null) {
            width = width.coerceIn(minOf(MIN_SIDE, availableWidth), availableWidth)
            height = height.coerceIn(minOf(MIN_SIDE, availableHeight), availableHeight)
        } else {
            val ceiling = minOf(availableWidth, availableHeight * ratio)
            val floor = maxOf(MIN_SIDE, MIN_SIDE * ratio)
            width = maxOf(width, height * ratio).coerceIn(minOf(floor, ceiling), ceiling)
            height = width / ratio
        }

        val left = if (onLeft) anchorX - width else anchorX
        val top = if (onTop) anchorY - height else anchorY
        return CropRect(left, top, left + width, top + height)
    }

    /** One axis of a frame, start to end, in normalized units. */
    private data class Span(val start: Float, val end: Float) {
        val center: Float get() = (start + end) / 2f
    }

    /**
     * A side grip dragged to [point] along its own axis: the opposite side stays
     * put and only [along] changes — unless the aspect is locked.
     *
     * With a [ratio] (along over across, normalized) the other axis has to
     * follow, and there is no finger on that axis to say which way. It grows and
     * shrinks about its own centre, which keeps a subject framed in the middle
     * where the user put it; only when centred growth would leave the photo is
     * it pushed back inside, so a frame resting on one edge still grows away
     * from that edge instead of refusing to grow at all.
     */
    private fun dragSide(
        along: Span,
        across: Span,
        fromStart: Boolean,
        point: Float,
        ratio: Float?,
    ): Pair<Span, Span> {
        val anchor = if (fromStart) along.end else along.start
        val available = if (fromStart) anchor else 1f - anchor
        if (available <= 0f) return along to across

        // Signed, not absolute: a finger that has crossed the anchor is asking
        // for less than nothing, which the clamps below turn into the minimum.
        val clamped = point.coerceIn(0f, 1f)
        var length = if (fromStart) anchor - clamped else clamped - anchor
        val nextAcross = if (ratio == null) {
            length = length.coerceIn(minOf(MIN_SIDE, available), available)
            across
        } else {
            // The other axis can be at most the whole photo, 1, so this one can
            // be at most the ratio of it.
            val ceiling = minOf(available, ratio)
            val floor = maxOf(MIN_SIDE, MIN_SIDE * ratio)
            length = length.coerceIn(minOf(floor, ceiling), ceiling)
            // Capped because length / ratio at the ceiling can land a float's
            // width past 1, and `coerceIn` throws on an inverted range.
            val acrossLength = (length / ratio).coerceAtMost(1f)
            val start = (across.center - acrossLength / 2f).coerceIn(0f, 1f - acrossLength)
            Span(start, start + acrossLength)
        }
        val start = if (fromStart) anchor - length else anchor
        return Span(start, start + length) to nextAcross
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
     * not near a grip — a corner, or the middle of a side.
     *
     * The tolerances are per-axis and in normalized units because that is what
     * a fixed dp radius becomes once the fit rect has scaled it — a 24 dp grab
     * radius is a much larger fraction of a narrow photo than of a wide one, so
     * one shared number would make the corners of a portrait shot harder to
     * grab than the corners of a landscape one.
     *
     * [reachX] and [reachY] are how far *into* the frame a grip still answers,
     * and may be larger than the tolerances, which then only apply outwards and
     * along a side. On a photo as wide as the phone the outer half of every left
     * or right grip lies inside the back-gesture strip, where the system takes
     * the touch before the app sees it, so the dependable way to take a corner is
     * from inside the frame. The inward reach never extends past a third of the
     * frame, so a small frame keeps a middle that moves it rather than one that
     * belongs to whichever grip is nearest — and it is never less than the
     * tolerance, which is what the grips answered to before it existed.
     *
     * Ties go to the nearest grip. A touch inside a small frame can be within
     * reach of several at once, and picking by distance is the only answer that
     * matches which one the user is looking at.
     */
    fun handleAt(
        rect: CropRect,
        x: Float,
        y: Float,
        toleranceX: Float,
        toleranceY: Float,
        reachX: Float = toleranceX,
        reachY: Float = toleranceY,
    ): CropHandle? {
        val innerX = maxOf(toleranceX, minOf(reachX, rect.width / 3f))
        val innerY = maxOf(toleranceY, minOf(reachY, rect.height / 3f))
        var best: CropHandle? = null
        var bestDistance = Float.MAX_VALUE
        for (handle in CropHandle.entries) {
            val grip = gripPoint(rect, handle)
            val offsetX = x - grip.x
            val offsetY = y - grip.y
            if (!withinReach(offsetX, handle.onLeft, toleranceX, innerX)) continue
            if (!withinReach(offsetY, handle.onTop, toleranceY, innerY)) continue
            // Ranked in tolerance units — the same fixed dp on both axes — so
            // "nearest" means nearest on the glass, not in normalized space.
            val dx = offsetX / toleranceX.coerceAtLeast(MIN_TOLERANCE)
            val dy = offsetY / toleranceY.coerceAtLeast(MIN_TOLERANCE)
            val distance = dx * dx + dy * dy
            if (distance < bestDistance) {
                best = handle
                bestDistance = distance
            }
        }
        return best
    }

    /**
     * Where [handle] sits on [rect]: a corner at its corner, a side grip at the
     * middle of its side. The point a drag of that grip starts from.
     */
    fun gripPoint(rect: CropRect, handle: CropHandle): FitPoint = FitPoint(
        x = when (handle.onLeft) {
            true -> rect.left
            false -> rect.right
            null -> rect.centerX
        },
        y = when (handle.onTop) {
            true -> rect.top
            false -> rect.bottom
            null -> rect.centerY
        },
    )

    /**
     * Whether an [offset] from a grip along one axis is close enough to take it.
     *
     * [ownsStart] is which edge the grip owns on this axis — true for left or
     * top — or null when it owns none there, as a side grip does along its own
     * side. Only an offset pointing into the frame gets the [inner] reach.
     */
    private fun withinReach(offset: Float, ownsStart: Boolean?, tolerance: Float, inner: Float): Boolean {
        val inward = when (ownsStart) {
            true -> offset > 0f
            false -> offset < 0f
            null -> false
        }
        return kotlin.math.abs(offset) <= if (inward) inner else tolerance
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
     * Full clearance on an edge would be whatever part of its strip the canvas
     * does not already clear plus a whole [grabRadius], putting a grip's entire
     * target on the app's side of the strip. The margin keeps only
     * [MARGIN_FRACTION] of that. What it still buys is the target's *inner*
     * side: the grab radius reaches past the strip, so a finger aimed at or just
     * inside the bracket lands where the app, not the back gesture, gets it.
     * Pure because Robolectric reports no gesture insets and could never see
     * the half of this that matters on a phone.
     */
    fun reachableInsets(canvas: EdgeInsetsPx, gestures: EdgeInsetsPx, grabRadius: Float): EdgeInsetsPx {
        fun edge(strip: Float, cleared: Float): Float =
            ((strip - cleared).coerceAtLeast(0f) + grabRadius) * MARGIN_FRACTION
        return EdgeInsetsPx(
            left = edge(gestures.left, canvas.left),
            top = edge(gestures.top, canvas.top),
            right = edge(gestures.right, canvas.right),
            bottom = edge(gestures.bottom, canvas.bottom),
        )
    }

    /**
     * How much of full clearance the crop margin keeps.
     *
     * A third, decided 2026-09-11: full clearance shrank a phone-width photo by
     * roughly a quarter while cropping, which was far too much photo to give up
     * for reach. Raising it buys a wider reachable band at the thinnest point —
     * back sensitivity at its highest — at the cost of photo size again.
     */
    const val MARGIN_FRACTION = 1f / 3f

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
