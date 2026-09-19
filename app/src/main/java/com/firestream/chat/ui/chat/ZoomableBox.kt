// region: AGENT-NOTE
// The pinch-zoom / pan / double-tap surface shared by every zoomable media
// viewer: FullscreenImageViewer's pager pages and ImagePreviewScreen's
// pre-send pages. Reuse `ZoomableBox` instead of hand-rolling a second gesture
// detector — see `detectZoomAndPan`'s KDoc for why the stock one won't do.
// The state is hoistable (`ZoomableState`) because the preview reads the zoom
// back as the crop it sends; the image-space arithmetic for that lives in
// `imageedit/ViewportGeometry`, not here.
//
// Don't put here: image loading, Coil requests, or any viewer chrome — this
// file must stay agnostic about what is being zoomed.
// endregion
package com.firestream.chat.ui.chat

import android.graphics.drawable.Drawable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculateCentroid
import androidx.compose.foundation.gestures.calculateCentroidSize
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.PointerInputScope
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChanged
import androidx.compose.ui.unit.IntSize
import com.firestream.chat.ui.chat.imageedit.ViewportGeometry
import com.firestream.chat.ui.chat.imageedit.ZoomTransform
import kotlin.math.abs

/**
 * The scale and offset of one [ZoomableBox], hoisted so a host can read them —
 * the send preview turns them into the crop it sends — and put them back after
 * a rotation. Screen pixels about the box centre, exactly what `graphicsLayer`
 * takes; a host that needs them to mean something about the *image* converts
 * through `ViewportGeometry`.
 */
@Stable
internal class ZoomableState {
    var scale: Float by mutableFloatStateOf(MIN_SCALE)
        private set
    var offset: Offset by mutableStateOf(Offset.Zero)
        private set

    val isZoomed: Boolean get() = scale > MIN_SCALE

    val transform: ZoomTransform get() = ZoomTransform(scale, offset.x, offset.y)

    fun set(transform: ZoomTransform) {
        scale = transform.scale.coerceIn(MIN_SCALE, MAX_SCALE)
        offset = if (scale > MIN_SCALE) Offset(transform.offsetX, transform.offsetY) else Offset.Zero
    }

    fun reset() = set(ZoomTransform.Identity)

    companion object {
        const val MIN_SCALE = 1f

        /** The pinch ceiling; [set] clamps to it too, so a restore cannot exceed a pinch. */
        const val MAX_SCALE = 10f
    }
}

@Composable
internal fun rememberZoomableState(): ZoomableState = remember { ZoomableState() }

/**
 * A decoded drawable's size in pixels for [ZoomableBox]'s `contentSize`, or
 * null when it has none to report. Taken from Coil's result drawable rather
 * than its painter: with crossfade on, the painter is the fade between
 * placeholder and result and reports the larger of the two.
 */
internal fun Drawable.toContentSize(): IntSize? =
    if (intrinsicWidth >= 1 && intrinsicHeight >= 1) IntSize(intrinsicWidth, intrinsicHeight) else null

/**
 * A zoom/pan surface that owns its own scale and offset and hands the resulting
 * `graphicsLayer` [Modifier] to [content], so the caller decides *what* gets
 * transformed (a Coil image, a video frame, anything).
 *
 * [isActive] is the pager contract: when it flips to false the page has scrolled
 * out of view, so the zoom resets and [onZoomChange] reports `false` — otherwise
 * a page scrolled back into view would still be zoomed. Hosts use
 * [onZoomChange] to drive `userScrollEnabled` on the pager, so a pan at >1x
 * moves the image instead of paging. A host for which the zoom *means*
 * something — the send preview, where it is the crop that gets sent — passes
 * `resetWhenInactive = false` and keeps the zoom per page itself.
 *
 * [contentSize] is the intrinsic size of what [content] draws, when the host
 * knows it. With it, every pan and pinch is clamped so the content never leaves
 * the box: no black past an edge, and on an axis where the scaled content is
 * still smaller than the box it stays centred. Without it (a viewer that has
 * not asked Coil, a frame still loading) the surface pans freely, as it always
 * has.
 *
 * [onTap] fires only at 1x, so a tap-to-dismiss host doesn't dismiss while the
 * user is working inside a zoomed image.
 */
@Composable
internal fun ZoomableBox(
    isActive: Boolean = true,
    onZoomChange: (Boolean) -> Unit = {},
    onTap: (() -> Unit)? = null,
    state: ZoomableState = rememberZoomableState(),
    resetWhenInactive: Boolean = true,
    contentSize: IntSize? = null,
    content: @Composable (transform: Modifier) -> Unit,
) {
    // Read through a holder rather than captured: the pointer-input blocks
    // below are keyed on Unit and would otherwise hold the size the content
    // had on first composition, which for an image still decoding is null.
    val currentContentSize by rememberUpdatedState(contentSize)

    LaunchedEffect(isActive) {
        if (!isActive && resetWhenInactive) {
            state.reset()
            onZoomChange(false)
        }
    }

    // The offset a gesture asked for, pulled back inside the content when the
    // host has said how big the content is; unchanged when it has not.
    fun clamped(transform: ZoomTransform, box: IntSize): ZoomTransform {
        val content = currentContentSize ?: return transform
        return ViewportGeometry.clamp(
            transform,
            box.width.toFloat(),
            box.height.toFloat(),
            content.width,
            content.height,
        )
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .pointerInput(Unit) {
                detectTapGestures(
                    onTap = { if (!state.isZoomed) onTap?.invoke() },
                    onDoubleTap = { tapPos ->
                        val scale = state.scale
                        val offset = state.offset
                        val targetScale = when {
                            scale >= 6f -> 1f
                            scale >= 2f -> 6f
                            else -> 3f
                        }
                        if (targetScale == 1f) {
                            state.reset()
                        } else {
                            // graphicsLayer pivots on the composable center, so to keep the
                            // tapped content point under the finger we solve for newOffset in:
                            //   tap = center + (content - center) * newScale + newOffset
                            // where content = center + (tap - center - offset) / scale.
                            val center = Offset(size.width / 2f, size.height / 2f)
                            val newOffset = tapPos - center - (tapPos - center - offset) * (targetScale / scale)
                            state.set(clamped(ZoomTransform(targetScale, newOffset.x, newOffset.y), size))
                        }
                        onZoomChange(state.isZoomed)
                    }
                )
            }
            .pointerInput(Unit) {
                detectZoomAndPan(isZoomed = { state.isZoomed }) { centroid, pan, zoom ->
                    val scale = state.scale
                    val offset = state.offset
                    val newScale = (scale * zoom).coerceIn(ZoomableState.MIN_SCALE, ZoomableState.MAX_SCALE)
                    if (newScale > 1f) {
                        // Keep the content point under the centroid fixed:
                        // translate so centroid maps to the same content point
                        // at the new scale.
                        val center = Offset(size.width / 2f, size.height / 2f)
                        val newOffset = centroid - center -
                            (centroid - center - offset) * (newScale / scale) + pan
                        state.set(clamped(ZoomTransform(newScale, newOffset.x, newOffset.y), size))
                    } else {
                        state.reset()
                    }
                    onZoomChange(state.isZoomed)
                }
            },
        contentAlignment = Alignment.Center
    ) {
        content(
            Modifier.graphicsLayer(
                scaleX = state.scale,
                scaleY = state.scale,
                translationX = state.offset.x,
                translationY = state.offset.y
            )
        )
    }
}

/**
 * Pinch-zoom / pan detector that cooperates with an enclosing `HorizontalPager`.
 *
 * It is modeled on Compose's own `detectTransformGestures`, but only **consumes**
 * pointer events when the image should own the gesture: a pinch (2+ pointers, so
 * zoom works even starting from 1x) or a pan while already zoomed ([isZoomed]).
 * A single-finger drag at 1x is left **unconsumed**, so — because pointer events
 * reach descendants before ancestors in the main pass — the drag bubbles up to the
 * pager and pages. The plain `detectTransformGestures` consumes every drag past
 * touch slop, which swallowed the swipe and was why paging never triggered.
 */
private suspend fun PointerInputScope.detectZoomAndPan(
    isZoomed: () -> Boolean,
    onGesture: (centroid: Offset, pan: Offset, zoom: Float) -> Unit,
) {
    awaitEachGesture {
        var zoom = 1f
        var pan = Offset.Zero
        var pastTouchSlop = false
        val touchSlop = viewConfiguration.touchSlop

        awaitFirstDown(requireUnconsumed = false)
        do {
            val event = awaitPointerEvent()
            val canceled = event.changes.any { it.isConsumed }
            if (!canceled) {
                val zoomChange = event.calculateZoom()
                val panChange = event.calculatePan()

                if (!pastTouchSlop) {
                    zoom *= zoomChange
                    pan += panChange
                    val centroidSize = event.calculateCentroidSize(useCurrent = false)
                    val zoomMotion = abs(1 - zoom) * centroidSize
                    val panMotion = pan.getDistance()
                    if (zoomMotion > touchSlop || panMotion > touchSlop) {
                        pastTouchSlop = true
                    }
                }

                if (pastTouchSlop) {
                    // Own (and consume) the gesture only for a pinch or a pan while
                    // zoomed; otherwise leave the single-finger 1x drag for the pager.
                    val multiTouch = event.changes.count { it.pressed } > 1
                    if (multiTouch || isZoomed()) {
                        val centroid = event.calculateCentroid(useCurrent = false)
                        if (zoomChange != 1f || panChange != Offset.Zero) {
                            onGesture(centroid, panChange, zoomChange)
                        }
                        event.changes.forEach { if (it.positionChanged()) it.consume() }
                    }
                }
            }
        } while (!canceled && event.changes.any { it.pressed })
    }
}
