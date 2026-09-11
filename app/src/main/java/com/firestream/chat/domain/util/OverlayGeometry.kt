package com.firestream.chat.domain.util

import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.roundToInt
import kotlin.math.sin

/** A point in whichever space the surrounding call is working in. */
data class OverlayPoint(val x: Float, val y: Float)

/** The two directly-draggable handles on a selected overlay. */
enum class OverlayHandle {
    /** Bottom-right: uniform scale, with a `1.4×` readout. */
    SCALE,

    /** Top-right: rotation, snapped, with a `−8°` readout. */
    ROTATE,
}

/**
 * The overlay screen's arithmetic: how big a placed object is, where its
 * handles sit, what a drag on one of them means, and where rotation snaps.
 *
 * ### Why this is a separate, Android-free file
 *
 * The same reason [StrokeGeometry] is one. Everything a [RasterOp.Overlays]
 * means is computed **twice** — once by Compose to show the user what they are
 * placing, once by `android.graphics` to write the file — and the two must not
 * be able to disagree. A sticker that sat over a face on screen and landed a
 * centimetre off in the JPEG is the same class of failure as a blur that missed,
 * and the fix is the same: one copy of the numbers, as pure functions a JVM test
 * can pin down (`.claude/plans/image-editor.md` §2.3).
 *
 * It sits in `domain/` because both renderers need it and `ArchitectureTest`
 * forbids `data → ui`: a helper in `ui/chat/imageedit/` would be unreachable
 * from `ImageEditRasterizer`, and one in `data/util/` would cost the editor a
 * second `UI_ALLOWED_DATA_IMPORTS` entry. Pure floats belong in neither layer.
 *
 * ### Everything here is a fraction, not a pixel
 *
 * Sizes come out of [sizePx] as a fraction of the image's **long edge**, so a
 * placement made on the editor's 1600 px preview is the same placement in the
 * 4096 px flatten. The handle and hit-test helpers work in whatever single
 * space the caller hands them — screen pixels on the editor, image pixels in
 * the rasterizer — because a rotation and a containment test are the same
 * arithmetic in both.
 */
object OverlayGeometry {

    /**
     * How much of the image's long edge a freshly placed object occupies, before
     * the user scales it.
     *
     * A fraction rather than a dp value, for the reason
     * [StrokeGeometry.MIN_WIDTH] is one: the object is stored against the
     * *image*, not against the screen it was placed on. 0.22 is an emoji large
     * enough to read as deliberate on a phone-sized photo and small enough that
     * the first thing anyone does is not shrink it.
     */
    const val BASE_SIZE = 0.22f

    /**
     * How far the scale handle may take an object, as a multiplier on
     * [BASE_SIZE].
     *
     * The floor keeps an object from being scaled below its own 48 dp hit rect,
     * which would make it unselectable and therefore undeletable except through
     * undo. The ceiling is a little over four times the long edge — enough to
     * cover a whole photo with one sticker, which is a real thing people do.
     */
    const val MIN_SCALE = 0.25f
    const val MAX_SCALE = 4.5f

    /**
     * Rotation snaps to multiples of this.
     *
     * 15° is fine enough that no angle feels unreachable and coarse enough that
     * a deliberate tilt holds still — and it is what makes a dedicated rotate
     * handle worth having at all. A combined scale-and-rotate corner cannot
     * snap, because every rotation it produces is also a resize
     * (`.claude/plans/image-editor.md` §3 Phase 5).
     */
    const val ROTATION_SNAP_DEGREES = 15f

    /** How close to a multiple of [ROTATION_SNAP_DEGREES] a drag must come to be taken there. */
    const val SNAP_TOLERANCE_DEGREES = 4f

    /**
     * The cardinals — 0 / 90 / 180 / 270 — snap from further out than the other
     * multiples of 15°.
     *
     * They are the angles a *shape* is usually wanted at: a rectangle drawn
     * round something is either deliberately tilted or exactly square, and
     * "exactly square" is the one a hand cannot hit. Every cardinal is already a
     * multiple of 15, so this is not a second rule — it is the same rule with a
     * wider mouth on four of its stops.
     */
    const val CARDINAL_SNAP_TOLERANCE_DEGREES = 8f

    /** A shape's outline width, as a fraction of the object's own size. */
    const val SHAPE_STROKE_RATIO = 0.10f

    /** An outlined text run's stroke width, as a fraction of its font size. */
    const val TEXT_OUTLINE_RATIO = 0.055f

    /**
     * A rounded rectangle's corner radius, as a fraction of the object's size.
     *
     * Here rather than in either renderer for the reason everything else in this
     * file is: a corner radius that differed between the preview and the flatten
     * would be a shape that changed when it was written.
     */
    const val ROUNDED_SHAPE_RADIUS_RATIO = 0.18f

    /** An arrow's head length, as a fraction of the object's size. */
    const val ARROW_HEAD_RATIO = 0.55f

    /**
     * How far short of the tip the arrow's shaft stops, as a fraction of the
     * head — enough that the shaft does not show through a translucent head.
     */
    const val ARROW_SHAFT_TRIM_RATIO = 0.5f

    /** How far each barb spreads from the shaft, as a fraction of the head. */
    const val ARROW_BARB_RATIO = 0.6f

    /**
     * The most objects one photo may carry.
     *
     * Not a performance limit — a hundred emoji flatten fine — but a bound on
     * what `rememberSaveable` has to carry through a `Bundle`, which is the same
     * reason [StrokeGeometry.MAX_SAVED_POINTS] exists. It is far past what
     * anyone places on a photo they are about to send.
     */
    const val MAX_OVERLAYS = 40

    /**
     * The size an overlay is drawn at, in the pixels of a space whose long edge
     * is [imageLongEdgePx].
     *
     * What "size" *means* is the content's business and each kind reads it the
     * way that makes it scale honestly: an emoji and a text run take it as a
     * font size, a sticker and a shape as their drawn height with the width
     * following from their own proportions. One number, so the scale handle has
     * one thing to multiply whatever is selected.
     */
    fun sizePx(scale: Float, imageLongEdgePx: Float): Float =
        BASE_SIZE * scale.coerceIn(MIN_SCALE, MAX_SCALE) * imageLongEdgePx

    /**
     * How wide a [ShapeKind] is relative to its height.
     *
     * Fixed per kind rather than draggable, because the scale handle is uniform:
     * a rectangle comes out 3:2, which is the proportion of the thing people
     * draw a box around. A [ShapeKind.LINE] and a [ShapeKind.ARROW] have no
     * height to speak of and are drawn across the full width at their own
     * stroke weight.
     */
    fun aspectFor(kind: ShapeKind): Float = when (kind) {
        ShapeKind.RECTANGLE, ShapeKind.ROUNDED_RECTANGLE, ShapeKind.ELLIPSE -> 1.5f
        ShapeKind.LINE, ShapeKind.ARROW -> 2.4f
    }

    /**
     * Whether a shape is drawn as an outline rather than filled.
     *
     * Not simply `!filled`: a line and an arrow have no interior, so the fill
     * toggle cannot mean anything for them and they are always stroked. That
     * exception is *here* rather than in each renderer for the reason every
     * other number in this file is — a rule written twice is a rule that can
     * drift, and the next shape kind is exactly when it would.
     */
    fun isOutlined(shape: OverlayContent.Shape): Boolean =
        !shape.filled || shape.kind == ShapeKind.LINE || shape.kind == ShapeKind.ARROW

    /** Degrees folded into `(-180, 180]`, which is the range a readout can show signed. */
    fun normalizeAngle(degrees: Float): Float {
        var angle = degrees % 360f
        if (angle <= -180f) angle += 360f
        if (angle > 180f) angle -= 360f
        return angle
    }

    /**
     * [degrees] taken to the nearest stop it is close enough to, or left alone.
     *
     * Snapping *only near a stop* rather than always rounding is what keeps
     * every angle reachable: at 22° the nearest multiple of 15 is 7° away, so
     * the drag stays where the finger put it.
     */
    fun snapRotation(degrees: Float): Float {
        val angle = normalizeAngle(degrees)
        val nearest = (angle / ROTATION_SNAP_DEGREES).roundToInt() * ROTATION_SNAP_DEGREES
        val tolerance = if (nearest % 90f == 0f) {
            CARDINAL_SNAP_TOLERANCE_DEGREES
        } else {
            SNAP_TOLERANCE_DEGREES
        }
        return if (abs(angle - nearest) <= tolerance) normalizeAngle(nearest) else angle
    }

    /**
     * [point] expressed in the object's own upright frame: the origin at the
     * object's centre, and the object's rotation undone.
     *
     * Everything that asks "is this on the object" or "where does its corner
     * go" is one of these two directions, which is why they are the only two
     * primitives here.
     */
    fun toLocal(
        point: OverlayPoint,
        centerX: Float,
        centerY: Float,
        rotationDegrees: Float,
    ): OverlayPoint {
        val radians = Math.toRadians(-rotationDegrees.toDouble())
        val cosine = cos(radians).toFloat()
        val sine = sin(radians).toFloat()
        val dx = point.x - centerX
        val dy = point.y - centerY
        return OverlayPoint(dx * cosine - dy * sine, dx * sine + dy * cosine)
    }

    /** The inverse of [toLocal]: an offset from the centre, rotated back and placed. */
    fun toWorld(
        local: OverlayPoint,
        centerX: Float,
        centerY: Float,
        rotationDegrees: Float,
    ): OverlayPoint {
        val radians = Math.toRadians(rotationDegrees.toDouble())
        val cosine = cos(radians).toFloat()
        val sine = sin(radians).toFloat()
        return OverlayPoint(
            centerX + local.x * cosine - local.y * sine,
            centerY + local.x * sine + local.y * cosine,
        )
    }

    /**
     * True when [point] falls on an object of these half-extents, centred and
     * rotated as given.
     *
     * [padding] widens the box in every direction, which is how a small object
     * still gets a finger-sized target: the caller passes the difference between
     * the object's drawn size and the 48 dp minimum, rather than this having any
     * opinion about dp.
     */
    fun contains(
        point: OverlayPoint,
        centerX: Float,
        centerY: Float,
        halfWidth: Float,
        halfHeight: Float,
        rotationDegrees: Float,
        padding: Float = 0f,
    ): Boolean {
        val local = toLocal(point, centerX, centerY, rotationDegrees)
        return abs(local.x) <= halfWidth + padding && abs(local.y) <= halfHeight + padding
    }

    /**
     * Where a handle sits, in the same space the extents were given in.
     *
     * Bottom-right scales and top-right rotates, and they are two handles rather
     * than one corner deliberately: a combined handle is fine while everything
     * on the canvas is an emoji, where neither exact size nor exact angle
     * matters, and stops being fine the moment a shape is in — a box drawn round
     * something is usually wanted axis-aligned or at a deliberate angle, and a
     * combined handle cannot rotate without also resizing
     * (`.claude/plans/image-editor.md` §3 Phase 5).
     */
    fun handleCenter(
        handle: OverlayHandle,
        centerX: Float,
        centerY: Float,
        halfWidth: Float,
        halfHeight: Float,
        rotationDegrees: Float,
    ): OverlayPoint {
        val localY = if (handle == OverlayHandle.SCALE) halfHeight else -halfHeight
        return toWorld(OverlayPoint(halfWidth, localY), centerX, centerY, rotationDegrees)
    }

    /** The clockwise angle from the centre out to [point], `0` pointing right. */
    fun angleTo(centerX: Float, centerY: Float, point: OverlayPoint): Float =
        Math.toDegrees(
            atan2((point.y - centerY).toDouble(), (point.x - centerX).toDouble()),
        ).toFloat()

    /**
     * What the rotate handle should write, given where the drag started and
     * where the finger is now.
     *
     * A *difference* of angles rather than the pointer's own angle, so grabbing
     * the handle anywhere along its hit rect does not jerk the object round to
     * meet the finger. Snapped on the way out, so the readout and the object
     * agree about where it landed.
     */
    fun rotationFromDrag(
        startRotation: Float,
        startAngle: Float,
        currentAngle: Float,
    ): Float = snapRotation(startRotation + normalizeAngle(currentAngle - startAngle))

    /**
     * What the scale handle should write: the drag's distance from the centre
     * against the distance it started at.
     *
     * A ratio rather than an offset, so the object tracks the finger at every
     * size — an offset would move a small object far too fast and a large one
     * imperceptibly. A grab that lands exactly on the centre has no ratio to
     * form and leaves the scale alone rather than dividing by zero.
     */
    fun scaleFromDrag(
        startScale: Float,
        startDistance: Float,
        currentDistance: Float,
    ): Float {
        if (startDistance <= 0f) return startScale.coerceIn(MIN_SCALE, MAX_SCALE)
        return (startScale * currentDistance / startDistance).coerceIn(MIN_SCALE, MAX_SCALE)
    }

    /** Straight-line distance, for the caller that is about to ask [scaleFromDrag]. */
    fun distance(centerX: Float, centerY: Float, point: OverlayPoint): Float =
        hypot(point.x - centerX, point.y - centerY)

    /**
     * A placement with its centre put at [centerX], [centerY] — fractions of the
     * image — clamped so that centre can never leave the photo.
     *
     * A target rather than a step. The drag reads the object as it was last
     * drawn, and a step worked out against that position but added to the
     * current one counts every pointer event since that frame again: two events
     * between frames, which is every Robolectric swipe and a phone whose drawing
     * has fallen behind the finger, ran the object into the edge of the photo.
     *
     * The *centre*, not the whole object: dragging a sticker half off the edge
     * is a thing people do deliberately, and forbidding it would be the wrong
     * rule. What must not happen is an object whose centre is outside the image,
     * because then nothing on screen can be tapped to get it back.
     */
    fun movedTo(overlay: ImageOverlay, centerX: Float, centerY: Float): ImageOverlay = overlay.copy(
        centerX = centerX.coerceIn(0f, 1f),
        centerY = centerY.coerceIn(0f, 1f),
    )

    /** The readout the scale handle shows — `1.4×`, to one decimal. */
    fun scaleLabel(scale: Float): String {
        val rounded = (scale * 10f).roundToInt() / 10f
        return "${rounded}×"
    }

    /**
     * The readout the rotate handle shows — `-8°`, whole degrees, signed.
     *
     * Signed rather than 0–359 because the number people are steering by is
     * "how far off upright", and 352° is a worse answer to that than −8°.
     */
    fun rotationLabel(degrees: Float): String = "${normalizeAngle(degrees).roundToInt()}°"
}
