package com.firestream.chat.domain.util

import com.firestream.chat.domain.model.StickerCrop
import kotlin.math.PI
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.roundToInt
import kotlin.math.sin

/**
 * The sticker maker's arithmetic: where a picture sits in the square sticker,
 * and the ring an outline is stamped along. The one copy, because the preview
 * (Compose) and the saved file (`android.graphics`) must not disagree.
 */
object StickerGeometry {

    /** The side of a made sticker, in pixels. */
    const val CANVAS = 512

    const val MAX_SCALE = 5f

    /** The outline's width as a fraction of the subject's long edge. */
    private const val OUTLINE_FRACTION = 1f / 40f
    private const val MIN_OUTLINE = 2

    /** A rectangle in fractions of the square's side. It may reach outside 0..1, where the square cuts it off. */
    data class Placement(val left: Float, val top: Float, val right: Float, val bottom: Float) {
        val width: Float get() = right - left
        val height: Float get() = bottom - top
    }

    /** One stamp of the outline: how far the subject's shape is moved, in pixels. */
    data class Shift(val dx: Float, val dy: Float)

    /** The size of a [width] × [height] picture fitted into the unit square: its long edge is 1. */
    fun fit(width: Int, height: Int): Pair<Float, Float> {
        val longEdge = max(width, height)
        if (longEdge <= 0) return 1f to 1f
        return width.toFloat() / longEdge to height.toFloat() / longEdge
    }

    /**
     * [crop] kept inside what the picture allows: the scale between 1 and
     * [MAX_SCALE], and an offset that never opens a gap on an axis the picture
     * fills. On an axis it does not fill, the picture stays centred.
     */
    fun clamp(crop: StickerCrop, width: Int, height: Int): StickerCrop {
        val scale = if (crop.scale.isNaN()) 1f else crop.scale.coerceIn(1f, MAX_SCALE)
        val (fitWidth, fitHeight) = fit(width, height)
        val maxX = max(0f, (fitWidth * scale - 1f) / 2f)
        val maxY = max(0f, (fitHeight * scale - 1f) / 2f)
        return StickerCrop(
            scale = scale,
            offsetX = if (crop.offsetX.isNaN()) 0f else crop.offsetX.coerceIn(-maxX, maxX),
            offsetY = if (crop.offsetY.isNaN()) 0f else crop.offsetY.coerceIn(-maxY, maxY),
        )
    }

    /** [crop] after a pinch by [zoom] and a drag by [panX], [panY] in fractions of the square's side. */
    fun transformed(crop: StickerCrop, zoom: Float, panX: Float, panY: Float, width: Int, height: Int): StickerCrop =
        clamp(StickerCrop(crop.scale * zoom, crop.offsetX + panX, crop.offsetY + panY), width, height)

    /** Where a [width] × [height] picture lands in the square under [crop]: scaled about the centre, then moved. */
    fun placement(width: Int, height: Int, crop: StickerCrop): Placement {
        val (fitWidth, fitHeight) = fit(width, height)
        val halfWidth = fitWidth * crop.scale / 2f
        val halfHeight = fitHeight * crop.scale / 2f
        val centreX = 0.5f + crop.offsetX
        val centreY = 0.5f + crop.offsetY
        return Placement(centreX - halfWidth, centreY - halfHeight, centreX + halfWidth, centreY + halfHeight)
    }

    /** The outline's width in pixels, for a subject whose long edge is [longEdge] pixels. */
    fun outlineWidth(longEdge: Int): Int = max(MIN_OUTLINE, (longEdge * OUTLINE_FRACTION).roundToInt())

    /**
     * The shifts the subject's shape is stamped at, in white, to make an outline
     * [width] pixels wide. Two rings: the outer one is the outline's edge, with
     * stamps at most two pixels apart so the edge is round. The inner one fills
     * what the outer ring leaves open around a part thinner than the outline.
     */
    fun outlineShifts(width: Int): List<Shift> = ring(width.toFloat()) + ring(width / 2f)

    private fun ring(radius: Float): List<Shift> {
        if (radius <= 0f) return emptyList()
        val count = max(8, ceil(PI * radius).toInt())
        return List(count) { index ->
            val angle = 2.0 * PI * index / count
            Shift((radius * cos(angle)).toFloat(), (radius * sin(angle)).toFloat())
        }
    }
}
