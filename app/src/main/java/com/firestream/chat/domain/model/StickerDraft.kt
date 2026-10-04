package com.firestream.chat.domain.model

/** One picture of a [StickerDraft]: a file on this device and its size in pixels. */
data class StickerDraftImage(
    val path: String,
    val width: Int,
    val height: Int,
)

/**
 * A photo prepared for the sticker maker.
 *
 * [original] is the whole photo. [cutout] is its subject on a transparent
 * background, trimmed to the subject's bounds, and [outlined] is that subject
 * with a white outline around it. Both are `null` when no subject was found,
 * or when this device cannot cut one out. The maker then offers the crop only.
 */
data class StickerDraft(
    val original: StickerDraftImage,
    val cutout: StickerDraftImage?,
    val outlined: StickerDraftImage?,
) {
    companion object {
        /** How many emojis a made sticker is tagged with at most, which is WhatsApp's limit too. */
        const val MAX_EMOJIS = 3

        /**
         * The pack a made sticker joins unless another is chosen. The repository
         * stores the name in that pack's import key, which is backed up with the
         * pack. So this value must not change: a changed name starts a second
         * pack beside the restored one.
         */
        const val DEFAULT_PACK_NAME = "My stickers"
    }
}

/**
 * How a draft's picture sits in the square sticker.
 *
 * At [scale] 1 the whole picture fits the square. [offsetX] and [offsetY] move
 * it, as fractions of the square's side. `StickerGeometry` turns this into a
 * rectangle, for the preview and for the saved sticker alike.
 */
data class StickerCrop(
    val scale: Float = 1f,
    val offsetX: Float = 0f,
    val offsetY: Float = 0f,
)
