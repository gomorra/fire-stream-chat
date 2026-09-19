package com.firestream.chat.ui.chat

import android.graphics.drawable.Drawable
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import com.firestream.chat.ui.chat.imageedit.CropAspect
import com.firestream.chat.ui.chat.imageedit.PendingCrop
import com.firestream.chat.ui.chat.imageedit.ViewportGeometry
import kotlinx.coroutines.flow.drop

/**
 * A [ZoomableBox] whose zoom *means* a crop: the part of the photo on screen,
 * and the shape the crop pill asked for, kept in a [PendingCrop] the host owns.
 *
 * The zoom surface works in screen pixels and the crop in fractions of the
 * image, and the two are kept in step here. Once the box is measured and Coil
 * has said how big the photo is — [content] reports the decoded drawable, its
 * *decoded* size with orientation applied, which is what a normalized frame has
 * to be relative to and what a header-only probe does not know — the saved
 * viewport is put back onto the surface once, and from then on every gesture
 * writes the visible frame back through [onCropChange].
 *
 * Only a gesture writes the viewport. The restore never does, and it happens
 * once per surface rather than on every size change: a restore into a box of
 * another shape is contained rather than exact (`ViewportGeometry.transformFor`),
 * and the box changes shape every time the keyboard slides over a caption
 * field, so re-deriving the zoom from the frame on each of those frames would
 * pulse the photo's scale and, if written back, widen the framing on every
 * keystroke. A later size change only clamps the transform the user already
 * has, so the photo never shows past its edge and never jumps in scale.
 *
 * The frame of a non-free shape is drawn over the photo — scrim outside, a
 * hairline and corner brackets on it, the look of the adjust screen's crop tool
 * without its handles: the way to move this frame is to move the photo under
 * it. The overlay takes no pointer input, so the gestures beneath it are
 * untouched.
 *
 * Identity is the host's: wrap this in `key(...)` on whatever the photo is, so
 * a different photo gets a fresh surface at 1x rather than one frame of the
 * new image under the old zoom.
 */
@Composable
internal fun ZoomCropSurface(
    crop: PendingCrop,
    onCropChange: (PendingCrop) -> Unit,
    modifier: Modifier = Modifier,
    isActive: Boolean = true,
    resetWhenInactive: Boolean = false,
    onZoomChange: (Boolean) -> Unit = {},
    onTap: (() -> Unit)? = null,
    content: @Composable (transform: Modifier, onDecoded: (Drawable) -> Unit) -> Unit,
) {
    val zoom = remember { ZoomableState() }
    var boxSize by remember { mutableStateOf(IntSize.Zero) }
    var contentSize by remember { mutableStateOf<IntSize?>(null) }
    var restored by remember { mutableStateOf(false) }
    // The latest crop and callback, read from inside the collector below, which
    // outlives many recompositions: the pill can change the aspect while a
    // pan is in progress, and the pan must write over that aspect, not over
    // the one it started with.
    val currentCrop by rememberUpdatedState(crop)
    val currentOnCropChange by rememberUpdatedState(onCropChange)

    LaunchedEffect(contentSize, boxSize) {
        val content = contentSize
        if (content == null || boxSize.width <= 0 || boxSize.height <= 0) return@LaunchedEffect
        val boxWidth = boxSize.width.toFloat()
        val boxHeight = boxSize.height.toFloat()
        if (currentCrop.imageWidth != content.width || currentCrop.imageHeight != content.height) {
            currentOnCropChange(currentCrop.copy(imageWidth = content.width, imageHeight = content.height))
        }
        if (restored) {
            zoom.set(ViewportGeometry.clamp(zoom.transform, boxWidth, boxHeight, content.width, content.height))
        } else {
            zoom.set(
                ViewportGeometry.transformFor(
                    viewport = currentCrop.viewport,
                    boxWidth = boxWidth,
                    boxHeight = boxHeight,
                    imageWidth = content.width,
                    imageHeight = content.height,
                    maxScale = ZoomableState.MAX_SCALE,
                )
            )
            restored = true
        }
        // The first emission is the set just made; everything after it is a
        // gesture, or the surface resetting itself on paging away.
        snapshotFlow { zoom.transform }.drop(1).collect { transform ->
            val visible = ViewportGeometry.visible(transform, boxWidth, boxHeight, content.width, content.height)
            // The size is stamped on every write, not only the one above: a
            // read of the crop between that write and the recomposition that
            // carries it back here would otherwise put the size back to zero.
            currentOnCropChange(
                currentCrop.copy(viewport = visible, imageWidth = content.width, imageHeight = content.height)
            )
        }
    }

    Box(modifier = modifier.fillMaxSize().onSizeChanged { boxSize = it }) {
        ZoomableBox(
            isActive = isActive,
            onZoomChange = onZoomChange,
            onTap = onTap,
            state = zoom,
            resetWhenInactive = resetWhenInactive,
            contentSize = contentSize,
        ) { transform ->
            content(transform) { drawable -> contentSize = drawable.toContentSize() }
        }
        val content = contentSize
        if (crop.aspect != CropAspect.FREE && content != null) {
            CropFrameOverlay(crop = crop, zoom = zoom, content = content)
        }
    }
}

/**
 * The frame [PendingCrop.frame] would cut, drawn where the zoomed photo puts
 * it. Reads the zoom inside the draw lambda, so a pan redraws this without
 * recomposing anything.
 */
@Composable
private fun CropFrameOverlay(crop: PendingCrop, zoom: ZoomableState, content: IntSize) {
    val density = LocalDensity.current
    val armPx = with(density) { FRAME_ARM_DP.dp.toPx() }
    val strokePx = with(density) { FRAME_STROKE_DP.dp.toPx() }
    val frame = crop.frame
    Canvas(modifier = Modifier.fillMaxSize()) {
        val rect = ViewportGeometry.toScreen(
            frame, zoom.transform, size.width, size.height, content.width, content.height,
        ) ?: return@Canvas
        val left = rect.left.coerceIn(0f, size.width)
        val top = rect.top.coerceIn(0f, size.height)
        val right = rect.right.coerceIn(0f, size.width)
        val bottom = rect.bottom.coerceIn(0f, size.height)
        val width = (right - left).coerceAtLeast(0f)
        val height = (bottom - top).coerceAtLeast(0f)

        val scrim = Color.Black.copy(alpha = 0.55f)
        drawRect(scrim, topLeft = Offset.Zero, size = Size(size.width, top))
        drawRect(scrim, topLeft = Offset(0f, bottom), size = Size(size.width, (size.height - bottom).coerceAtLeast(0f)))
        drawRect(scrim, topLeft = Offset(0f, top), size = Size(left, height))
        drawRect(scrim, topLeft = Offset(right, top), size = Size((size.width - right).coerceAtLeast(0f), height))

        drawRect(
            color = Color.White.copy(alpha = 0.85f),
            topLeft = Offset(left, top),
            size = Size(width, height),
            style = Stroke(width = 1f),
        )
        val arm = minOf(armPx, width / 2f, height / 2f)
        val corners = listOf(
            Triple(left, top, 1f to 1f),
            Triple(right, top, -1f to 1f),
            Triple(left, bottom, 1f to -1f),
            Triple(right, bottom, -1f to -1f),
        )
        for ((x, y, direction) in corners) {
            val (dirX, dirY) = direction
            drawLine(Color.White, Offset(x, y), Offset(x + arm * dirX, y), strokeWidth = strokePx)
            drawLine(Color.White, Offset(x, y), Offset(x, y + arm * dirY), strokeWidth = strokePx)
        }
    }
}

/** The corner brackets' arm length and stroke, the adjust screen's own numbers. */
private const val FRAME_ARM_DP = 22
private const val FRAME_STROKE_DP = 3
