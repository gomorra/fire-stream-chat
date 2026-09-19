package com.firestream.chat.ui.chat.imageedit

import androidx.compose.runtime.Immutable
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.listSaver
import com.firestream.chat.domain.util.RasterOp

/**
 * The crop a fullscreen photo would get, before anything has been written:
 * what its zoom shows, in fractions of the image, and the shape the crop pill
 * has asked for.
 *
 * Two inputs, one answer. [viewport] is the part of the photo on screen —
 * written by gestures on the zoom surface and by nothing else — and [aspect]
 * is the preset the pill has cycled to. [frame] is the crop that results: the
 * largest frame of the chosen shape that fits inside the viewport, centred on
 * it, or the viewport itself for a free crop. The image's decoded size is
 * carried along because a ratio in pixels is a different ratio in fractions
 * of a photo that is not square (`CropGeometry.normalizedRatio`); it is filled
 * in once the photo has loaded and is zero until then, when the aspect is
 * simply not applied yet.
 *
 * Held per displayed step in the send preview, and for the page on screen in
 * the fullscreen viewer, which hands it to the preview when Edit is pressed so
 * the crop chosen while looking at a received photo is the crop the editor
 * opens with.
 */
@Immutable
internal data class PendingCrop(
    val viewport: CropRect = CropRect.Full,
    val aspect: CropAspect = CropAspect.FREE,
    val imageWidth: Int = 0,
    val imageHeight: Int = 0,
) {
    /** True when the zoom shows less than the whole photo — a pan must move the image, not page. */
    val isZoomed: Boolean get() = !viewport.isFull

    /** The crop this would write: the viewport cut to [aspect]'s shape. */
    val frame: CropRect
        get() = CropGeometry.fitInside(aspect, viewport, imageWidth, imageHeight)

    /** True when writing this would change the photo at all. */
    val hasCrop: Boolean get() = !frame.isFull

    /** The op that flattens [frame], or null when there is nothing to crop. */
    fun toOp(): RasterOp.Crop? = frame.toOp()

    /** This crop with the next preset on the pill, wrapping round after the last. */
    fun cycleAspect(): PendingCrop {
        val entries = CropAspect.entries
        return copy(aspect = entries[(entries.indexOf(aspect) + 1) % entries.size])
    }

    companion object {
        val None = PendingCrop()

        /** Field count in [Saver]; the preview's map saver chunks by it. */
        const val SAVED_FIELDS = 7

        /** Survives a rotation for the reason [CropRect.Saver] does: the frame means the same thing in either orientation. */
        val Saver: Saver<PendingCrop, Any> = listSaver(
            save = { save(it) },
            restore = { restore(it) ?: None },
        )

        fun save(crop: PendingCrop): List<Any> = listOf(
            crop.viewport.left, crop.viewport.top, crop.viewport.right, crop.viewport.bottom,
            crop.aspect.name, crop.imageWidth, crop.imageHeight,
        )

        /** The crop [save] flattened, or null when [flat] is not one. */
        fun restore(flat: List<Any?>): PendingCrop? {
            if (flat.size != SAVED_FIELDS) return null
            val edges = flat.take(4).map { it as? Float ?: return null }
            val aspect = CropAspect.entries.firstOrNull { it.name == flat[4] } ?: return null
            val width = flat[5] as? Int ?: return null
            val height = flat[6] as? Int ?: return null
            return PendingCrop(CropRect(edges[0], edges[1], edges[2], edges[3]), aspect, width, height)
        }
    }
}
