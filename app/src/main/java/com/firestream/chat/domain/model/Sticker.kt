package com.firestream.chat.domain.model

/**
 * The container a sticker file is stored in, with the extension and type it is named and sent by.
 *
 * [LOTTIE] is a Lottie animation as gzip-compressed JSON, which is what a
 * Telegram `.tgs` file is. No image decoder reads it, so every Lottie sticker
 * has its first frame beside it as a PNG ([stillPathOf]).
 */
enum class StickerFormat(val extension: String, val mimeType: String) {
    WEBP("webp", "image/webp"),
    LOTTIE("tgs", "application/x-tgsticker"),
    ;

    /**
     * The file that holds the first frame of the sticker stored at [stickerPath],
     * for a grid or a preview. A WebP is its own still.
     */
    fun stillPathOf(stickerPath: String): String = if (this == LOTTIE) "$stickerPath.$STILL_EXTENSION" else stickerPath

    companion object {
        private const val STILL_EXTENSION = "png"

        /** The format a sticker message's mime type names. An unknown type, or none, is a WebP. */
        fun ofMimeType(mimeType: String?): StickerFormat = entries.firstOrNull { it.mimeType == mimeType } ?: WEBP

        /** The format of the sticker file at [path], by its extension, or `null` when it has none of them. */
        fun ofPath(path: String): StickerFormat? = entries.firstOrNull { path.endsWith(".${it.extension}") }
    }
}

/**
 * One sticker in the library: an immutable file named by the SHA-256 of its bytes.
 *
 * [id] is that hash in lowercase hex, so two imports of the same bytes are one
 * sticker. [localPath] is where the file lives on this device. A sticker that
 * came back with a restored pack, or with a pack added from someone else, has
 * its row before its file, so the path may name a file that is not there yet.
 * [emojis] are the tags the sticker was imported with, the first of which
 * stands for it in text.
 */
data class Sticker(
    val id: String,
    val format: StickerFormat,
    val width: Int,
    val height: Int,
    val isAnimated: Boolean,
    val emojis: List<String>,
    val localPath: String,
) {
    /** The file a grid draws: the sticker itself, or the first frame of a Lottie sticker. */
    val stillPath: String
        get() = format.stillPathOf(localPath)
}
