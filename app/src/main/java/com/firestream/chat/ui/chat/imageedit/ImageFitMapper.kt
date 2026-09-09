package com.firestream.chat.ui.chat.imageedit

/**
 * A point in whichever space the surrounding call is working in.
 *
 * Deliberately not `android.graphics.PointF` or `androidx.compose.ui.geometry.Offset`:
 * [ImageFitMapper] is pure Kotlin so the mapping arithmetic — the thing every
 * overlay tool's correctness rests on — is testable on the JVM without
 * Robolectric or a Compose harness.
 */
internal data class FitPoint(val x: Float, val y: Float)

/**
 * Where the image being edited actually sits on screen, and how to get a point
 * between the three spaces the editor screens work in
 * (`.claude/plans/image-editor.md` §2.3).
 *
 * Every editor screen displays the *current* image axis-aligned under
 * `ContentScale.Fit`, which is what makes this one small helper enough for draw,
 * blur, sticker and text: the transform is a single uniform [scale] plus a
 * centring offset, so there is no inverse rotation anywhere and no per-tool
 * geometry to get subtly wrong four times.
 *
 * Three spaces:
 * - **screen** — pixels in the composable's own coordinates, what a pointer
 *   event hands you. The image occupies [fittedWidth] × [fittedHeight] starting
 *   at ([offsetX], [offsetY]); the rest is letterbox or pillarbox.
 * - **normalized** — `0..1` across the image itself. Overlay geometry is stored
 *   here, so a placed sticker survives a device rotation or a screen-size change
 *   without drifting: the numbers mean "40% across this photo", not "212 px into
 *   the canvas I happened to be laid out in".
 * - **bitmap** — pixels in the source image, which is what an
 *   `com.firestream.chat.domain.util.RasterOp` ultimately needs.
 *
 * Points outside the image are **not clamped**: a drag that leaves the photo
 * produces normalized values below 0 or above 1, and it is the caller's business
 * whether that means "clamp the stroke to the edge" or "the finger left the
 * canvas". [containsScreen] answers the membership question directly.
 *
 * A degenerate canvas or image (either dimension at or below zero, which
 * happens on the first composition before layout has measured) yields a [scale]
 * of `0` and maps everything to the origin rather than producing infinities.
 */
internal data class ImageFitMapper(
    val canvasWidth: Float,
    val canvasHeight: Float,
    val imageWidth: Int,
    val imageHeight: Int,
) {
    /** Uniform bitmap-pixel → screen-pixel factor; `0` for a degenerate input. */
    val scale: Float = if (canvasWidth <= 0f || canvasHeight <= 0f || imageWidth <= 0 || imageHeight <= 0) {
        0f
    } else {
        minOf(canvasWidth / imageWidth, canvasHeight / imageHeight)
    }

    /** Width of the image as drawn, in screen pixels. */
    val fittedWidth: Float = imageWidth * scale

    /** Height of the image as drawn, in screen pixels. */
    val fittedHeight: Float = imageHeight * scale

    /** Screen x of the image's left edge — half the pillarbox. */
    val offsetX: Float = (canvasWidth - fittedWidth) / 2f

    /** Screen y of the image's top edge — half the letterbox. */
    val offsetY: Float = (canvasHeight - fittedHeight) / 2f

    /** True when a screen point falls on the image rather than on a bar. */
    fun containsScreen(point: FitPoint): Boolean =
        scale > 0f &&
            point.x >= offsetX && point.x <= offsetX + fittedWidth &&
            point.y >= offsetY && point.y <= offsetY + fittedHeight

    /** Screen pixels → `0..1` across the image. */
    fun screenToNormalized(point: FitPoint): FitPoint =
        if (scale <= 0f) {
            FitPoint(0f, 0f)
        } else {
            FitPoint((point.x - offsetX) / fittedWidth, (point.y - offsetY) / fittedHeight)
        }

    /** `0..1` across the image → screen pixels. */
    fun normalizedToScreen(point: FitPoint): FitPoint =
        FitPoint(offsetX + point.x * fittedWidth, offsetY + point.y * fittedHeight)

    /** `0..1` across the image → source-bitmap pixels. */
    fun normalizedToBitmap(point: FitPoint): FitPoint =
        FitPoint(point.x * imageWidth, point.y * imageHeight)

    /** Source-bitmap pixels → `0..1` across the image. */
    fun bitmapToNormalized(point: FitPoint): FitPoint =
        if (imageWidth <= 0 || imageHeight <= 0) {
            FitPoint(0f, 0f)
        } else {
            FitPoint(point.x / imageWidth, point.y / imageHeight)
        }

    /** Screen pixels → source-bitmap pixels, the mapping a stroke is captured in. */
    fun screenToBitmap(point: FitPoint): FitPoint = normalizedToBitmap(screenToNormalized(point))

    /** Source-bitmap pixels → screen pixels, the mapping a handle is drawn with. */
    fun bitmapToScreen(point: FitPoint): FitPoint = normalizedToScreen(bitmapToNormalized(point))
}
