package com.firestream.chat.ui.chat.imageedit

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Fill
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.text.drawText
import androidx.compose.ui.unit.Density
import com.firestream.chat.domain.util.ImageOverlay
import com.firestream.chat.domain.util.OverlayContent
import com.firestream.chat.domain.util.OverlayGeometry
import com.firestream.chat.domain.util.ShapeKind
import com.firestream.chat.domain.util.StickerDesign
import com.firestream.chat.domain.util.StickerPack
import com.firestream.chat.domain.util.StickerPart
import kotlin.math.ceil

/**
 * The Compose half of drawing an overlay — the *preview*, whose only job is to
 * be indistinguishable from what `ImageEditRasterizer.drawOverlays` writes.
 *
 * ### Why this is written twice at all
 *
 * It is the same arrangement `DrawImageScreen` and `drawStrokes` are in, and for
 * the same reason: Compose and `android.graphics` share no path, paint or text
 * object, so the *painting* has to exist on both sides. What must not exist
 * twice is the arithmetic, and it does not — every number here comes out of
 * [OverlayGeometry] or [StickerPack], which the rasterizer also calls. When this
 * file and the rasterizer disagree it can only be about a brush, never about a
 * position.
 *
 * ### Text is centred by its layout box in both renderers
 *
 * Compose centres a text run by placing its layout box, and the rasterizer
 * centres a `StaticLayout` the same way — one built without font padding, at
 * the default line spacing, which is what Compose builds underneath. A text run
 * wraps at [OverlayGeometry.textWrapWidthPx] on both sides, each line centred
 * within that box, so the box is the thing that is placed and the lines fall
 * where they fall inside it. Stated here because it is an agreement that has to
 * be maintained rather than an identity: a `lineHeight`, an `includePadding`,
 * or a different wrap width on either side would quietly separate them.
 */

/** Every overlay on the photo, in list order, which is the z-order. */
internal fun DrawScope.drawOverlays(
    overlays: List<ImageOverlay>,
    imageRect: OverlayRect,
    measurer: TextMeasurer,
) {
    overlays.forEach { drawOverlay(it, imageRect, measurer) }
}

/** One overlay, rotated about its own centre. */
internal fun DrawScope.drawOverlay(
    overlay: ImageOverlay,
    imageRect: OverlayRect,
    measurer: TextMeasurer,
) {
    val center = imageRect.centerOf(overlay)
    val size = OverlayGeometry.sizePx(overlay.scale, imageRect.longEdge)
    if (size <= 0f) return
    rotate(overlay.rotationDegrees, pivot = center) {
        when (val content = overlay.content) {
            is OverlayContent.Emoji ->
                drawOverlayText(measurer, content.emoji, center, size, Color.White, filled = true)

            is OverlayContent.Text -> drawOverlayText(
                measurer = measurer,
                text = content.text,
                center = center,
                fontSize = size,
                color = Color(content.colorArgb),
                filled = content.filled,
                wrapWidth = OverlayGeometry.textWrapWidthPx(imageRect.width),
            )

            is OverlayContent.Sticker ->
                StickerPack.byId(content.stickerId)?.let { drawSticker(it, center, size) }

            is OverlayContent.Shape -> drawOverlayShape(content, center, size)
        }
    }
}

/**
 * How big an overlay is on screen, which is what the selection frame is drawn
 * around and what a tap is tested against.
 *
 * Returned rather than recomputed by each caller because a text run's width is
 * *measured*, not derived — it is the one overlay whose extents the geometry
 * cannot know on its own.
 */
internal fun overlayHalfExtents(
    overlay: ImageOverlay,
    imageRect: OverlayRect,
    measurer: TextMeasurer,
    density: Density,
): Size {
    val size = OverlayGeometry.sizePx(overlay.scale, imageRect.longEdge)
    return when (val content = overlay.content) {
        is OverlayContent.Emoji -> measuredHalfExtents(measurer, content.emoji, size, density)
        is OverlayContent.Text -> measuredHalfExtents(
            measurer, content.text, size, density,
            wrapWidth = OverlayGeometry.textWrapWidthPx(imageRect.width),
        )
        is OverlayContent.Sticker -> Size(size / 2f, size / 2f)
        is OverlayContent.Shape ->
            Size(size / 2f * OverlayGeometry.aspectFor(content.kind), size / 2f)
    }
}

private fun measuredHalfExtents(
    measurer: TextMeasurer,
    text: String,
    fontSize: Float,
    density: Density,
    wrapWidth: Float? = null,
): Size {
    val layout = measure(measurer, text, fontSize, density, wrapWidth)
    return Size(layout.size.width / 2f, layout.size.height / 2f)
}

private fun DrawScope.drawOverlayText(
    measurer: TextMeasurer,
    text: String,
    center: Offset,
    fontSize: Float,
    color: Color,
    filled: Boolean,
    wrapWidth: Float? = null,
) {
    if (text.isEmpty()) return
    val layout = measure(measurer, text, fontSize, this, wrapWidth)
    drawText(
        textLayoutResult = layout,
        color = color,
        topLeft = Offset(center.x - layout.size.width / 2f, center.y - layout.size.height / 2f),
        drawStyle = if (filled) {
            Fill
        } else {
            Stroke(
                width = fontSize * OverlayGeometry.TEXT_OUTLINE_RATIO,
                join = StrokeJoin.Round,
            )
        },
    )
}

/**
 * One sticker's parts, scaled out of their `0..1` box into a [size]-square
 * around [center] — the same walk `ImageEditRasterizer.paintSticker` does.
 */
internal fun DrawScope.drawSticker(design: StickerDesign, center: Offset, size: Float) {
    val left = center.x - size / 2f
    val top = center.y - size / 2f
    fun point(x: Float, y: Float) = Offset(left + x * size, top + y * size)

    design.parts.forEach { part ->
        when (part) {
            is StickerPart.Circle -> drawCircle(
                color = Color(part.colorArgb),
                radius = part.radius * size,
                center = point(part.centerX, part.centerY),
            )

            is StickerPart.Polygon -> drawPath(
                path = polyline(part.points, ::point, close = true),
                color = Color(part.colorArgb),
            )

            is StickerPart.Line -> drawPath(
                path = polyline(part.points, ::point, close = false),
                color = Color(part.colorArgb),
                style = Stroke(
                    width = part.width * size,
                    cap = StrokeCap.Round,
                    join = StrokeJoin.Round,
                ),
            )
        }
    }
}

/** One annotation primitive, in a box [size] tall and as wide as its kind wants. */
internal fun DrawScope.drawOverlayShape(
    shape: OverlayContent.Shape,
    center: Offset,
    size: Float,
) {
    val halfHeight = size / 2f
    val halfWidth = halfHeight * OverlayGeometry.aspectFor(shape.kind)
    val strokeWidth = size * OverlayGeometry.SHAPE_STROKE_RATIO
    val color = Color(shape.colorArgb)
    val outlined = OverlayGeometry.isOutlined(shape)
    val style = if (outlined) {
        Stroke(width = strokeWidth, cap = StrokeCap.Round, join = StrokeJoin.Round)
    } else {
        Fill
    }
    // Inset by half the stroke so an outline stays inside the box the selection
    // frame is drawn around, rather than straddling it.
    val inset = if (outlined) strokeWidth / 2f else 0f
    val topLeft = Offset(center.x - halfWidth + inset, center.y - halfHeight + inset)
    val boxSize = Size((halfWidth - inset) * 2f, (halfHeight - inset) * 2f)

    when (shape.kind) {
        ShapeKind.RECTANGLE -> drawRect(color = color, topLeft = topLeft, size = boxSize, style = style)
        ShapeKind.ROUNDED_RECTANGLE -> {
            val radius = size * OverlayGeometry.ROUNDED_SHAPE_RADIUS_RATIO
            drawRoundRect(
                color = color,
                topLeft = topLeft,
                size = boxSize,
                cornerRadius = androidx.compose.ui.geometry.CornerRadius(radius, radius),
                style = style,
            )
        }

        ShapeKind.ELLIPSE -> drawOval(color = color, topLeft = topLeft, size = boxSize, style = style)
        ShapeKind.LINE -> drawLine(
            color = color,
            start = Offset(center.x - halfWidth, center.y),
            end = Offset(center.x + halfWidth, center.y),
            strokeWidth = strokeWidth,
            cap = StrokeCap.Round,
        )

        ShapeKind.ARROW -> {
            val head = size * OverlayGeometry.ARROW_HEAD_RATIO
            val tip = center.x + halfWidth
            drawLine(
                color = color,
                start = Offset(center.x - halfWidth, center.y),
                end = Offset(tip - head * OverlayGeometry.ARROW_SHAFT_TRIM_RATIO, center.y),
                strokeWidth = strokeWidth,
                cap = StrokeCap.Round,
            )
            val arrowhead = Path().apply {
                moveTo(tip, center.y)
                lineTo(tip - head, center.y - head * OverlayGeometry.ARROW_BARB_RATIO)
                lineTo(tip - head, center.y + head * OverlayGeometry.ARROW_BARB_RATIO)
                close()
            }
            drawPath(path = arrowhead, color = color)
        }
    }
}

/** Where the image sits on screen, and the long edge every overlay size is a fraction of. */
internal data class OverlayRect(
    val left: Float,
    val top: Float,
    val width: Float,
    val height: Float,
) {
    val longEdge: Float get() = maxOf(width, height)

    /** An overlay's normalized centre, in screen pixels. */
    fun centerOf(overlay: ImageOverlay): Offset =
        Offset(left + overlay.centerX * width, top + overlay.centerY * height)

    /** A screen point as a fraction of the image, which is how a placement is stored. */
    fun normalize(point: Offset): Offset =
        if (width <= 0f || height <= 0f) {
            Offset.Zero
        } else {
            Offset((point.x - left) / width, (point.y - top) / height)
        }
}

/** The fit rect an [ImageFitMapper] describes, in the shape the painter wants. */
internal fun ImageFitMapper.toOverlayRect(): OverlayRect =
    OverlayRect(offsetX, offsetY, fittedWidth, fittedHeight)

/**
 * One text run laid out the way both renderers agree on.
 *
 * With a [wrapWidth] the run breaks into centred lines no wider than it, and
 * the result's width is the narrower of the run's own width and the box — so a
 * short caption's frame hugs the caption and a long one's spans the photo.
 * Without one (an emoji) it stays on a single unbounded line.
 */
private fun measure(
    measurer: TextMeasurer,
    text: String,
    fontSize: Float,
    density: Density,
    wrapWidth: Float? = null,
): TextLayoutResult {
    val style = TextStyle(fontSize = with(density) { fontSize.toSp() }, textAlign = TextAlign.Center)
    val box = wrapWidth?.let { ceil(it).toInt() }?.takeIf { it > 0 }
    return if (box == null) {
        measurer.measure(text = text, style = style, maxLines = 1)
    } else {
        measurer.measure(text = text, style = style, constraints = Constraints(maxWidth = box))
    }
}

private fun polyline(points: List<Float>, map: (Float, Float) -> Offset, close: Boolean): Path =
    Path().apply {
        points.chunked(2).forEachIndexed { index, pair ->
            if (pair.size < 2) return@forEachIndexed
            val offset = map(pair[0], pair[1])
            if (index == 0) moveTo(offset.x, offset.y) else lineTo(offset.x, offset.y)
        }
        if (close) close()
    }
