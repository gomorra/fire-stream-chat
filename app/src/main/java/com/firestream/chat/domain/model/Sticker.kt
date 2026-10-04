package com.firestream.chat.domain.model

/** The container a sticker file is stored in, with the extension and type it is named and sent by. */
enum class StickerFormat(val extension: String, val mimeType: String) {
    WEBP("webp", "image/webp"),
}

/**
 * One sticker in the library: an immutable file named by the SHA-256 of its bytes.
 *
 * [id] is that hash in lowercase hex, so two imports of the same bytes are one
 * sticker. [localPath] is where the file lives on this device. [emojis] are the
 * tags the sticker was imported with, the first of which stands for it in text.
 */
data class Sticker(
    val id: String,
    val format: StickerFormat,
    val width: Int,
    val height: Int,
    val isAnimated: Boolean,
    val emojis: List<String>,
    val localPath: String,
)
