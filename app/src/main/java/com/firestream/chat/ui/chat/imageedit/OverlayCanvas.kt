package com.firestream.chat.ui.chat.imageedit

import android.graphics.Bitmap
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import com.firestream.chat.domain.util.ImageOverlay
import com.firestream.chat.domain.util.OverlayGeometry
import com.firestream.chat.domain.util.OverlayHandle
import com.firestream.chat.domain.util.OverlayPoint
import com.firestream.chat.ui.theme.FireOrange
import kotlin.math.roundToInt

/**
 * The photo, everything placed on it, the frame round whatever is selected and
 * the two handles that resize and turn it.
 *
 * **One `Canvas` for all of it**, deliberately — the same rule the draw screen
 * follows. Phase 3 shipped a bug where the photo's container centred it *and*
 * [ImageFitMapper] centred it again, putting every crop handle half a letterbox
 * from the pixels it belonged to. Drawing the bitmap, the overlays and the
 * handles into one canvas at one mapper's rect removes the possibility rather
 * than re-avoiding it.
 */
@Composable
internal fun OverlayCanvas(
    preview: Bitmap?,
    overlays: List<ImageOverlay>,
    selected: Int?,
    enabled: Boolean,
    callbacks: OverlayCallbacks,
    modifier: Modifier = Modifier,
) {
    var canvasSize by remember { mutableStateOf(IntSize.Zero) }
    // Sized to the overlay cap rather than left at Compose's default of 8: every
    // emoji and text run is measured once per draw pass, so a photo carrying more
    // than eight of them would evict and re-measure the whole set on every frame
    // of a drag. One argument, and the cache can no longer thrash.
    val measurer = rememberTextMeasurer(cacheSize = OverlayGeometry.MAX_OVERLAYS)
    val density = LocalDensity.current
    val touchSlopPx = with(density) { TOUCH_TARGET_DP.dp.toPx() }
    val handleRadiusPx = with(density) { HANDLE_DIAMETER_DP.dp.toPx() / 2f }

    Box(modifier = modifier.onSizeChanged { canvasSize = it }, contentAlignment = Alignment.Center) {
        val bitmap = preview
        if (bitmap == null) {
            OverlayPreviewPlaceholder()
            return@Box
        }

        val mapper = ImageFitMapper(
            canvasWidth = canvasSize.width.toFloat(),
            canvasHeight = canvasSize.height.toFloat(),
            imageWidth = bitmap.width,
            imageHeight = bitmap.height,
        )
        if (mapper.scale <= 0f) return@Box
        val rect = mapper.toOverlayRect()
        val image = remember(bitmap) { bitmap.asImageBitmap() }

        // Everything the gesture reads is read through these, not captured.
        // `pointerInput` restarts only when a key changes, and neither the
        // overlay list nor the selection may be a key: making them one would
        // tear the detector down mid-drag, on the very state change the drag
        // itself is producing. Phase 3 shipped exactly that bug on the crop
        // frame — the frame could be dragged once and never moved bodily
        // (`9169ce31`) — and a drag/scale/rotate over a selection is the same
        // shape, only with more state moving under it.
        val currentOverlays by rememberUpdatedState(overlays)
        val currentSelected by rememberUpdatedState(selected)
        val currentRect by rememberUpdatedState(rect)
        val currentCallbacks by rememberUpdatedState(callbacks)

        var readout by remember { mutableStateOf<String?>(null) }

        Canvas(
            modifier = Modifier
                .fillMaxSize()
                .semantics { contentDescription = "Overlay canvas" }
                .pointerInput(enabled) {
                    if (!enabled) return@pointerInput
                    awaitEachGesture {
                        val down = awaitFirstDown(requireUnconsumed = false)
                        down.consume()

                        val grab = grabAt(
                            point = down.position,
                            overlays = currentOverlays,
                            selected = currentSelected,
                            rect = currentRect,
                            measurer = measurer,
                            density = density,
                            handleRadius = handleRadiusPx,
                            touchTarget = touchSlopPx,
                        )
                        if (grab is Grab.Select) currentCallbacks.onSelect(grab.index)

                        var pointer = down
                        var pinch: Pinch? = null
                        while (pointer.pressed) {
                            val event = awaitPointerEvent()
                            val down2 = event.changes.filter { it.pressed }

                            // Two fingers on a selected object scale and rotate
                            // it at once — the same two properties the handles
                            // set, through the same snapped arithmetic, so a
                            // pinch and a handle drag cannot disagree about
                            // where 90° is. Handles stay the precise route;
                            // this is the quick one.
                            val target = currentSelected
                            if (down2.size >= 2 && target != null) {
                                pinch = applyPinch(
                                    started = pinch,
                                    first = down2[0].position,
                                    second = down2[1].position,
                                    overlay = currentOverlays.getOrNull(target),
                                    callbacks = currentCallbacks,
                                )
                                readout = pinch?.readout
                                down2.forEach { it.consume() }
                                pointer = down2.firstOrNull { it.id == down.id } ?: down2.first()
                                continue
                            }
                            // A finger lifting out of a pinch must not carry the
                            // pinch's starting span into the one-finger drag
                            // that follows, or the object would jump.
                            pinch = null

                            // Only the finger that started the gesture: a second
                            // one landing mid-drag must not tug the object to it.
                            pointer = event.changes.firstOrNull { it.id == down.id } ?: break
                            if (!pointer.pressed) break
                            pointer.consume()
                            readout = applyDrag(
                                grab = grab,
                                position = pointer.position,
                                overlays = currentOverlays,
                                selected = currentSelected,
                                rect = currentRect,
                                measurer = measurer,
                                density = density,
                                callbacks = currentCallbacks,
                            )
                        }
                        readout = null
                    }
                },
        ) {
            drawImage(
                image = image,
                dstOffset = IntOffset(rect.left.roundToInt(), rect.top.roundToInt()),
                dstSize = IntSize(rect.width.roundToInt(), rect.height.roundToInt()),
            )
            drawOverlays(overlays, rect, measurer)

            val index = selected ?: return@Canvas
            val overlay = overlays.getOrNull(index) ?: return@Canvas
            drawSelection(overlay, rect, measurer, handleRadiusPx)
            readout?.let { drawReadout(it, overlay, rect, measurer, handleRadiusPx) }
        }
    }
}

/**
 * Where a two-finger gesture started, so its scale and rotation are measured
 * against that rather than against the object's own centre.
 *
 * A pinch has no anchor on the object the way a handle does — the two fingers
 * define their own span and angle — so this is what turns them into the same
 * `startDistance`/`startAngle` pair the handle drags feed to [OverlayGeometry].
 */
private data class Pinch(
    val span: Float,
    val angle: Float,
    val scale: Float,
    val rotation: Float,
    val readout: String? = null,
)

/**
 * One frame of a two-finger gesture: starts the pinch on the first frame that
 * has two fingers down, and applies it on every frame after.
 *
 * The first frame only records, because a scale needs a span to measure
 * against — applying on it would compare the fingers' span to itself and snap
 * the object to 1×.
 */
private fun applyPinch(
    started: Pinch?,
    first: Offset,
    second: Offset,
    overlay: ImageOverlay?,
    callbacks: OverlayCallbacks,
): Pinch? {
    if (overlay == null) return null
    val span = OverlayGeometry.distance(first.x, first.y, OverlayPoint(second.x, second.y))
    val angle = OverlayGeometry.angleTo(first.x, first.y, OverlayPoint(second.x, second.y))
    if (started == null) {
        return Pinch(span, angle, overlay.scale, overlay.rotationDegrees)
    }
    val scale = OverlayGeometry.scaleFromDrag(started.scale, started.span, span)
    val rotation = OverlayGeometry.rotationFromDrag(started.rotation, started.angle, angle)
    callbacks.onScale(scale)
    callbacks.onRotate(rotation)
    return started.copy(
        readout = "${OverlayGeometry.scaleLabel(scale)}  ${OverlayGeometry.rotationLabel(rotation)}",
    )
}

/** What a finger landed on, decided once at finger-down and held for the gesture. */
private sealed interface Grab {
    /** Nothing draggable: the tap changes the selection and the drag does nothing. */
    data class Select(val index: Int?) : Grab

    /**
     * The body of the selected object: a one-finger move.
     *
     * [offsetX]/[offsetY] are how far from the object's centre the finger
     * landed, in fractions of the image, kept for the whole drag so the point
     * that was grabbed stays under the finger — the same gap the crop grips keep
     * (`grabOffset` in `CropOverlay`).
     */
    data class Move(val offsetX: Float, val offsetY: Float) : Grab

    /** One of the two corner handles, with where the drag started. */
    data class Handle(
        val handle: OverlayHandle,
        val startScale: Float,
        val startRotation: Float,
        val startAngle: Float,
        val startDistance: Float,
    ) : Grab
}

/**
 * What the finger is on: a handle first, then the selected object's body, then
 * whatever object is under it, then nothing.
 *
 * Handles win over bodies because they sit *on* the object's corners and would
 * otherwise be unreachable; and the topmost object wins over the ones under it,
 * which is why the search runs the list backwards — the last thing placed is the
 * thing drawn on top, so it is the thing a tap should find.
 */
private fun grabAt(
    point: Offset,
    overlays: List<ImageOverlay>,
    selected: Int?,
    rect: OverlayRect,
    measurer: TextMeasurer,
    density: androidx.compose.ui.unit.Density,
    handleRadius: Float,
    touchTarget: Float,
): Grab {
    val touch = OverlayPoint(point.x, point.y)
    val current = selected?.let { overlays.getOrNull(it) }
    if (current != null) {
        val center = rect.centerOf(current)
        val half = overlayHalfExtents(current, rect.longEdge, measurer, density)
        for (handle in OverlayHandle.entries) {
            val at = OverlayGeometry.handleCenter(
                handle, center.x, center.y, half.width, half.height, current.rotationDegrees,
            )
            // The hit rect is expanded well past the drawn handle: 26 dp of dot
            // inside a 48 dp target is what makes a corner grabbable without the
            // dot itself covering what it sits on.
            val reach = maxOf(handleRadius, touchTarget / 2f)
            if (OverlayGeometry.distance(at.x, at.y, touch) <= reach) {
                return Grab.Handle(
                    handle = handle,
                    startScale = current.scale,
                    startRotation = current.rotationDegrees,
                    startAngle = OverlayGeometry.angleTo(center.x, center.y, touch),
                    startDistance = OverlayGeometry.distance(center.x, center.y, touch),
                )
            }
        }
        if (containsTouch(current, touch, rect, measurer, density, touchTarget)) {
            val grabbed = rect.normalize(point)
            return Grab.Move(grabbed.x - current.centerX, grabbed.y - current.centerY)
        }
    }

    for (index in overlays.indices.reversed()) {
        if (containsTouch(overlays[index], touch, rect, measurer, density, touchTarget)) {
            return Grab.Select(index)
        }
    }
    return Grab.Select(null)
}

private fun containsTouch(
    overlay: ImageOverlay,
    touch: OverlayPoint,
    rect: OverlayRect,
    measurer: TextMeasurer,
    density: androidx.compose.ui.unit.Density,
    touchTarget: Float,
): Boolean {
    val center = rect.centerOf(overlay)
    val half = overlayHalfExtents(overlay, rect.longEdge, measurer, density)
    // Padded up to a finger-sized target, so a small sticker stays selectable —
    // and therefore deletable by any route other than undo.
    val padX = (touchTarget / 2f - half.width).coerceAtLeast(0f)
    val padY = (touchTarget / 2f - half.height).coerceAtLeast(0f)
    return OverlayGeometry.contains(
        point = touch,
        centerX = center.x,
        centerY = center.y,
        halfWidth = half.width,
        halfHeight = half.height,
        rotationDegrees = overlay.rotationDegrees,
        padding = maxOf(padX, padY),
    )
}

/** Applies one pointer move and returns the readout the handle should show, if any. */
private fun applyDrag(
    grab: Grab,
    position: Offset,
    overlays: List<ImageOverlay>,
    selected: Int?,
    rect: OverlayRect,
    measurer: TextMeasurer,
    density: androidx.compose.ui.unit.Density,
    callbacks: OverlayCallbacks,
): String? {
    val current = selected?.let { overlays.getOrNull(it) } ?: return null
    val touch = OverlayPoint(position.x, position.y)
    val center = rect.centerOf(current)

    return when (grab) {
        is Grab.Select -> null

        is Grab.Move -> {
            // Absolute rather than accumulated deltas, so a long drag cannot
            // drift — but less the gap the finger landed at. Putting the *centre*
            // under the finger snapped anything grabbed off-centre at the start of
            // every drag, and a line of text wider than the photo, which can only
            // be grabbed off-centre, slid back to where each drag began.
            // The target centre, never a step from `current`: that is the
            // object as last drawn, and more than one pointer event can land
            // before the next frame — see OverlayGeometry.movedTo.
            val finger = rect.normalize(position)
            callbacks.onMove(finger.x - grab.offsetX, finger.y - grab.offsetY)
            null
        }

        is Grab.Handle -> when (grab.handle) {
            OverlayHandle.SCALE -> {
                val scale = OverlayGeometry.scaleFromDrag(
                    startScale = grab.startScale,
                    startDistance = grab.startDistance,
                    currentDistance = OverlayGeometry.distance(center.x, center.y, touch),
                )
                callbacks.onScale(scale)
                OverlayGeometry.scaleLabel(scale)
            }

            OverlayHandle.ROTATE -> {
                val rotation = OverlayGeometry.rotationFromDrag(
                    startRotation = grab.startRotation,
                    startAngle = grab.startAngle,
                    currentAngle = OverlayGeometry.angleTo(center.x, center.y, touch),
                )
                callbacks.onRotate(rotation)
                OverlayGeometry.rotationLabel(rotation)
            }
        }
    }
}

/** The dashed frame round the selected object, and its two handles. */
private fun DrawScope.drawSelection(
    overlay: ImageOverlay,
    rect: OverlayRect,
    measurer: TextMeasurer,
    handleRadius: Float,
) {
    val center = rect.centerOf(overlay)
    // `this` is the density: a DrawScope is one, so the gesture code's captured
    // LocalDensity is only threaded where there is no receiver to ask.
    val half = overlayHalfExtents(overlay, rect.longEdge, measurer, this)
    rotate(overlay.rotationDegrees, pivot = center) {
        drawRect(
            color = FireOrange,
            topLeft = Offset(center.x - half.width, center.y - half.height),
            size = Size(half.width * 2f, half.height * 2f),
            style = Stroke(
                width = FRAME_STROKE_PX,
                pathEffect = PathEffect.dashPathEffect(floatArrayOf(12f, 10f)),
            ),
        )
    }
    OverlayHandle.entries.forEach { handle ->
        val at = OverlayGeometry.handleCenter(
            handle, center.x, center.y, half.width, half.height, overlay.rotationDegrees,
        )
        drawCircle(color = Color.White, radius = handleRadius, center = Offset(at.x, at.y))
        drawCircle(
            color = FireOrange,
            radius = handleRadius,
            center = Offset(at.x, at.y),
            style = Stroke(width = FRAME_STROKE_PX),
        )
        // A dot in the rotate handle only, so the two are told apart by sight
        // rather than by remembering which corner does what.
        if (handle == OverlayHandle.ROTATE) {
            drawCircle(color = FireOrange, radius = handleRadius / 3f, center = Offset(at.x, at.y))
        }
    }
}

/**
 * The live `1.4×` / `-8°` readout, pinned clear of the finger.
 *
 * Above the object rather than beside the handle: a label at the handle is under
 * the hand that is dragging it, which is the one place it cannot be read.
 */
private fun DrawScope.drawReadout(
    text: String,
    overlay: ImageOverlay,
    rect: OverlayRect,
    measurer: TextMeasurer,
    handleRadius: Float,
) {
    val center = rect.centerOf(overlay)
    val half = overlayHalfExtents(overlay, rect.longEdge, measurer, this)
    val layout = measurer.measure(
        text = text,
        style = TextStyle(fontSize = READOUT_SIZE_DP.dp.toSp()),
    )
    val padding = READOUT_PADDING_DP.dp.toPx()
    val top = center.y - half.height - handleRadius * 2f - layout.size.height - padding * 2f
    val left = center.x - layout.size.width / 2f
    drawRoundRect(
        color = Color.Black.copy(alpha = 0.7f),
        topLeft = Offset(left - padding, top - padding),
        size = Size(layout.size.width + padding * 2f, layout.size.height + padding * 2f),
        cornerRadius = androidx.compose.ui.geometry.CornerRadius(padding, padding),
    )
    drawText(textLayoutResult = layout, color = Color.White, topLeft = Offset(left, top))
}

/** The minimum touch target, which is what a small object's hit rect is padded up to. */
private const val TOUCH_TARGET_DP = 48

/** How big the handles are *drawn* — smaller than their reach, so they occlude less. */
private const val HANDLE_DIAMETER_DP = 26

private const val FRAME_STROKE_PX = 3f
private const val READOUT_SIZE_DP = 13
private const val READOUT_PADDING_DP = 6
