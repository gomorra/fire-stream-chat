package com.firestream.chat.ui.chat.imageedit

/**
 * A zoom surface's transform: a uniform [scale] about the box centre, then a
 * translation of ([offsetX], [offsetY]) screen pixels — the same three numbers
 * `ZoomableBox` hands to `graphicsLayer`.
 */
internal data class ZoomTransform(val scale: Float, val offsetX: Float, val offsetY: Float) {
    companion object {
        val Identity = ZoomTransform(1f, 0f, 0f)
    }
}

/**
 * What a pinch-zoomed image *shows*, as a crop of the image.
 *
 * The send preview treats a zoom as a crop: whatever part of the photo is on
 * screen when the user presses Send, or opens an editor, is what gets sent.
 * That makes the zoom surface's `(scale, offset)` pair — screen pixels about a
 * box centre — a statement about the photo, and this object is the conversion
 * both ways, kept pure for the reason `CropGeometry` is: the arithmetic is
 * where the bugs would be, and a JVM test can pin every edge case a finger on
 * glass would take an afternoon to reach.
 *
 * Every function here describes an image drawn `ContentScale.Fit` in a
 * `boxWidth × boxHeight` box, then scaled about the box centre and translated,
 * which is exactly how `ZoomableBox` composes its content. `ImageFitMapper`
 * supplies the 1x fit; everything here is that fit times [ZoomTransform.scale].
 */
internal object ViewportGeometry {

    /**
     * The part of the image inside the box, in fractions of the image — the
     * frame a [RasterOp.Crop][com.firestream.chat.domain.util.RasterOp.Crop]
     * of "what I am looking at" needs.
     *
     * [CropRect.Full] for a degenerate box or image, and for an offset that has
     * pushed the image entirely out of the box, which a clamped surface never
     * produces: a crop of nothing would be a one-pixel JPEG, and keeping the
     * whole photo is the only sensible reading of a view that shows none of it.
     */
    fun visible(
        transform: ZoomTransform,
        boxWidth: Float,
        boxHeight: Float,
        imageWidth: Int,
        imageHeight: Int,
    ): CropRect {
        val drawn = drawnRect(transform, boxWidth, boxHeight, imageWidth, imageHeight) ?: return CropRect.Full
        val left = ((0f - drawn.left) / drawn.width).coerceIn(0f, 1f)
        val top = ((0f - drawn.top) / drawn.height).coerceIn(0f, 1f)
        val right = ((boxWidth - drawn.left) / drawn.width).coerceIn(0f, 1f)
        val bottom = ((boxHeight - drawn.top) / drawn.height).coerceIn(0f, 1f)
        if (right - left <= 0f || bottom - top <= 0f) return CropRect.Full
        return CropRect(left, top, right, bottom)
    }

    /**
     * [transform] with its offset pulled back so the image covers the box
     * wherever it is large enough to, and sits centred on any axis where it is
     * not.
     *
     * A zoom that is a crop cannot be allowed to show anything that is not the
     * photo: black past an edge would be black in the JPEG, or — because
     * [visible] clamps to the image — a crop the user did not see. On an axis
     * where the scaled image is still smaller than the box there is nothing to
     * pan, so the offset there is zero and the image stays centred, which is
     * also where `ContentScale.Fit` put it at 1x.
     */
    fun clamp(
        transform: ZoomTransform,
        boxWidth: Float,
        boxHeight: Float,
        imageWidth: Int,
        imageHeight: Int,
    ): ZoomTransform {
        val fit = ImageFitMapper(boxWidth, boxHeight, imageWidth, imageHeight)
        if (fit.scale <= 0f) return transform
        val slackX = ((fit.fittedWidth * transform.scale - boxWidth) / 2f).coerceAtLeast(0f)
        val slackY = ((fit.fittedHeight * transform.scale - boxHeight) / 2f).coerceAtLeast(0f)
        return transform.copy(
            offsetX = transform.offsetX.coerceIn(-slackX, slackX),
            offsetY = transform.offsetY.coerceIn(-slackY, slackY),
        )
    }

    /**
     * The transform that shows exactly [viewport] again — the inverse of
     * [visible], for restoring a saved zoom into a box that may be a different
     * size from the one it was made in (a rotation, a resize).
     *
     * The frame is *contained*: the scale is the smaller of the two fills, and
     * the offset is whatever moves the frame's centre to the box centre. In the
     * box the frame was made in that reproduces the transform exactly, because
     * the frame filled that box on the axis it was cut on. In a box of another
     * shape the frame no longer fits both ways, and containing it shows — and
     * so sends — a little more photo around what the user framed rather than
     * cutting into it. The result is then clamped, so a restore can never show
     * past the photo's edge, and capped at [maxScale], the surface's own
     * ceiling, so it can never leave the zoom somewhere a pinch cannot.
     */
    fun transformFor(
        viewport: CropRect,
        boxWidth: Float,
        boxHeight: Float,
        imageWidth: Int,
        imageHeight: Int,
        maxScale: Float,
    ): ZoomTransform {
        if (viewport.isFull) return ZoomTransform.Identity
        val fit = ImageFitMapper(boxWidth, boxHeight, imageWidth, imageHeight)
        if (fit.scale <= 0f || viewport.width <= 0f || viewport.height <= 0f) return ZoomTransform.Identity
        val scale = minOf(
            boxWidth / (viewport.width * fit.fittedWidth),
            boxHeight / (viewport.height * fit.fittedHeight),
        ).coerceIn(1f, maxScale)
        val unclamped = ZoomTransform(
            scale = scale,
            offsetX = fit.fittedWidth * scale * (0.5f - viewport.centerX),
            offsetY = fit.fittedHeight * scale * (0.5f - viewport.centerY),
        )
        return clamp(unclamped, boxWidth, boxHeight, imageWidth, imageHeight)
    }

    /**
     * [anchor], a zoom made in an `anchorBoxWidth × anchorBoxHeight` box, carried
     * into a `boxWidth × boxHeight` box so that [frame] — the crop it stands
     * for — looks the same: the same size on screen, centred, and wholly in view.
     *
     * The box changes size whenever the keyboard slides over the caption field,
     * and with it the 1x fit. Keeping the anchor's own scale and offset would
     * then draw the photo smaller and slide the frame sideways. So the scale is
     * the one that keeps the photo's screen pixels per photo pixel, reduced
     * only as far as [frame] needs to fit the box. [frame] changes when the crop
     * pill picks another shape, and a taller shape may need a smaller scale.
     * The point of the photo at the anchor's box centre stays at the box
     * centre, which keeps the frame centred, since a frame is centred on the
     * view it was cut from.
     *
     * The answer depends only on the anchor, never on an earlier carry, so a
     * keyboard sliding down and back up returns to exactly the anchor. An
     * unzoomed anchor stays unzoomed: a photo at 1x simply fits the new box.
     */
    fun transformAfterResize(
        anchor: ZoomTransform,
        anchorBoxWidth: Float,
        anchorBoxHeight: Float,
        frame: CropRect,
        boxWidth: Float,
        boxHeight: Float,
        imageWidth: Int,
        imageHeight: Int,
        maxScale: Float,
    ): ZoomTransform {
        if (anchor.scale <= 1f) return ZoomTransform.Identity
        val anchorFit = ImageFitMapper(anchorBoxWidth, anchorBoxHeight, imageWidth, imageHeight)
        val fit = ImageFitMapper(boxWidth, boxHeight, imageWidth, imageHeight)
        if (anchorFit.scale <= 0f || fit.scale <= 0f) return clamp(anchor, boxWidth, boxHeight, imageWidth, imageHeight)
        val sameSize = anchor.scale * anchorFit.scale / fit.scale
        val fitsFrame = if (frame.width > 0f && frame.height > 0f) {
            minOf(boxWidth / (frame.width * fit.fittedWidth), boxHeight / (frame.height * fit.fittedHeight))
        } else {
            sameSize
        }
        val scale = minOf(sameSize, fitsFrame).coerceIn(1f, maxScale)
        val centre = visible(anchor, anchorBoxWidth, anchorBoxHeight, imageWidth, imageHeight)
        val centred = ZoomTransform(
            scale = scale,
            offsetX = fit.fittedWidth * scale * (0.5f - centre.centerX),
            offsetY = fit.fittedHeight * scale * (0.5f - centre.centerY),
        )
        // A frame off the view's centre (a restore pushed against the photo's
        // edge) is pulled back into the box, which the scale guarantees it fits.
        // The clamp after that only closes gaps past the photo's edge, and the
        // frame is on the photo, so it cannot push the frame out again.
        val rect = toScreen(frame, centred, boxWidth, boxHeight, imageWidth, imageHeight)
            ?: return clamp(centred, boxWidth, boxHeight, imageWidth, imageHeight)
        val inView = centred.copy(
            offsetX = centred.offsetX + intoBox(rect.left, rect.right, boxWidth),
            offsetY = centred.offsetY + intoBox(rect.top, rect.bottom, boxHeight),
        )
        return clamp(inView, boxWidth, boxHeight, imageWidth, imageHeight)
    }

    /** How far to move a span of `start..end` so it lies inside `0..length`, when it fits. */
    private fun intoBox(start: Float, end: Float, length: Float): Float = when {
        end - start > length -> 0f
        start < 0f -> -start
        end > length -> length - end
        else -> 0f
    }

    /**
     * Where [frame], a rectangle in fractions of the image, lands in box pixels
     * under [transform] — how a crop frame is drawn over a zoomed photo. Null
     * when nothing is drawable.
     */
    fun toScreen(
        frame: CropRect,
        transform: ZoomTransform,
        boxWidth: Float,
        boxHeight: Float,
        imageWidth: Int,
        imageHeight: Int,
    ): ScreenRect? {
        val drawn = drawnRect(transform, boxWidth, boxHeight, imageWidth, imageHeight) ?: return null
        return ScreenRect(
            left = drawn.left + frame.left * drawn.width,
            top = drawn.top + frame.top * drawn.height,
            right = drawn.left + frame.right * drawn.width,
            bottom = drawn.top + frame.bottom * drawn.height,
        )
    }

    /** Where the scaled, translated image sits in box pixels, or null when nothing is drawable. */
    private fun drawnRect(
        transform: ZoomTransform,
        boxWidth: Float,
        boxHeight: Float,
        imageWidth: Int,
        imageHeight: Int,
    ): DrawnRect? {
        val fit = ImageFitMapper(boxWidth, boxHeight, imageWidth, imageHeight)
        if (fit.scale <= 0f || transform.scale <= 0f) return null
        val width = fit.fittedWidth * transform.scale
        val height = fit.fittedHeight * transform.scale
        return DrawnRect(
            left = boxWidth / 2f + transform.offsetX - width / 2f,
            top = boxHeight / 2f + transform.offsetY - height / 2f,
            width = width,
            height = height,
        )
    }

    private data class DrawnRect(val left: Float, val top: Float, val width: Float, val height: Float)
}

/** A rectangle in box pixels; what [ViewportGeometry.toScreen] hands a frame overlay. */
internal data class ScreenRect(val left: Float, val top: Float, val right: Float, val bottom: Float) {
    val width: Float get() = right - left
    val height: Float get() = bottom - top
}
