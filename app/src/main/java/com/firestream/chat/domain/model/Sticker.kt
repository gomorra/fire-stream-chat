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
 * [emojis] are the tags the sticker was imported with or a person gave it, the
 * first of which stands for it in text. [names] are the names a person typed.
 * [autoName] is what the naming service found, and never changes those two.
 * A search reads all three. [autoNameAllowed] is false for a sticker the
 * service must not be asked about, such as one made in the app.
 */
data class Sticker(
    val id: String,
    val format: StickerFormat,
    val width: Int,
    val height: Int,
    val isAnimated: Boolean,
    val emojis: List<String>,
    val localPath: String,
    val names: List<String> = emptyList(),
    val autoName: StickerAutoName? = null,
    val autoNameAllowed: Boolean = true,
) {
    /** The file a grid draws: the sticker itself, or the first frame of a Lottie sticker. */
    val stillPath: String
        get() = format.stillPathOf(localPath)
}

/**
 * What the naming service found on a sticker: a name in English and one in
 * German, search [words] in both languages, the [text] written on the sticker,
 * and [emojis] that fit it. A part it did not find is empty.
 */
data class StickerAutoName(
    val nameEn: String = "",
    val nameDe: String = "",
    val words: List<String> = emptyList(),
    val text: String = "",
    val emojis: List<String> = emptyList(),
) {
    val isEmpty: Boolean
        get() = nameEn.isEmpty() && nameDe.isEmpty() && words.isEmpty() && text.isEmpty() && emojis.isEmpty()
}

/**
 * Automatic sticker names as this device has them.
 *
 * [isSupported] is false on a backend without a naming service, where nothing
 * about it is offered. [isEnabled] is the person's switch, which is off until
 * they switch it on: with it on, the pictures of their stickers are read by the
 * naming service. [isRefused] says the backend refused the last look, so no
 * name can be had right now. It switches nothing off.
 */
data class StickerAutoNaming(
    val isSupported: Boolean = false,
    val isEnabled: Boolean = false,
    val isRefused: Boolean = false,
)
