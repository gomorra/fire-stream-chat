// region: AGENT-NOTE
// The pinch-zoom / pan / double-tap surface shared by every zoomable media
// viewer: FullscreenImageViewer's pager pages and ImagePreviewScreen's
// pre-send pages. Reuse `ZoomableBox` instead of hand-rolling a second gesture
// detector — see `detectZoomAndPan`'s KDoc for why the stock one won't do.
//
// Don't put here: image loading, Coil requests, or any viewer chrome — this
// file must stay agnostic about what is being zoomed.
// endregion
package com.firestream.chat.ui.chat

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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.PointerInputScope
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChanged
import kotlin.math.abs

/**
 * A zoom/pan surface that owns its own scale and offset and hands the resulting
 * `graphicsLayer` [Modifier] to [content], so the caller decides *what* gets
 * transformed (a Coil image, a video frame, anything).
 *
 * [isActive] is the pager contract: when it flips to false the page has scrolled
 * out of view, so the zoom resets and [onZoomChange] reports `false` — otherwise
 * a page scrolled back into view would still be zoomed. Hosts use
 * [onZoomChange] to drive `userScrollEnabled` on the pager, so a pan at >1x
 * moves the image instead of paging.
 *
 * [onTap] fires only at 1x, so a tap-to-dismiss host doesn't dismiss while the
 * user is working inside a zoomed image.
 */
@Composable
internal fun ZoomableBox(
    isActive: Boolean,
    onZoomChange: (Boolean) -> Unit,
    onTap: (() -> Unit)? = null,
    content: @Composable (transform: Modifier) -> Unit,
) {
    var scale by remember { mutableFloatStateOf(1f) }
    var offset by remember { mutableStateOf(Offset.Zero) }

    LaunchedEffect(isActive) {
        if (!isActive) {
            scale = 1f
            offset = Offset.Zero
            onZoomChange(false)
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .pointerInput(Unit) {
                detectTapGestures(
                    onTap = { if (scale == 1f) onTap?.invoke() },
                    onDoubleTap = { tapPos ->
                        val targetScale = when {
                            scale >= 6f -> 1f
                            scale >= 2f -> 6f
                            else -> 3f
                        }
                        if (targetScale == 1f) {
                            scale = 1f
                            offset = Offset.Zero
                        } else {
                            // graphicsLayer pivots on the composable center, so to keep the
                            // tapped content point under the finger we solve for newOffset in:
                            //   tap = center + (content - center) * newScale + newOffset
                            // where content = center + (tap - center - offset) / scale.
                            val center = Offset(size.width / 2f, size.height / 2f)
                            offset = tapPos - center - (tapPos - center - offset) * (targetScale / scale)
                            scale = targetScale
                        }
                        onZoomChange(scale > 1f)
                    }
                )
            }
            .pointerInput(Unit) {
                detectZoomAndPan(isZoomed = { scale > 1f }) { centroid, pan, zoom ->
                    val newScale = (scale * zoom).coerceIn(1f, 10f)
                    if (newScale > 1f) {
                        // Keep the content point under the centroid fixed:
                        // translate so centroid maps to the same content point
                        // at the new scale.
                        val center = Offset(size.width / 2f, size.height / 2f)
                        val newOffset = centroid - center -
                            (centroid - center - offset) * (newScale / scale) + pan
                        offset = newOffset
                    } else {
                        offset = Offset.Zero
                    }
                    scale = newScale
                    onZoomChange(scale > 1f)
                }
            },
        contentAlignment = Alignment.Center
    ) {
        content(
            Modifier.graphicsLayer(
                scaleX = scale,
                scaleY = scale,
                translationX = offset.x,
                translationY = offset.y
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
