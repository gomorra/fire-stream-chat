package com.firestream.chat.domain.model

/** What a pack is to its owner. */
enum class StickerPackKind {
    /** Made or imported by the user. */
    USER,

    /** Copied from someone else's pack; [StickerPack.originPackId] names the source. */
    INSTALLED,

    /** The one pack of favourites. Created the first time a sticker is favourited. */
    FAVOURITES,

    /** The one pack of loose stickers that came with no pack of their own. */
    SAVED,
    ;

    /**
     * Whether a pack of this kind is one of the user's named packs. Only those
     * can be renamed and merged, and can give up their own thumbnail in the
     * picker's row.
     */
    val isNamed: Boolean
        get() = this == USER || this == INSTALLED
}

/**
 * An ordered list of stickers owned by one user.
 *
 * The [FAVOURITES][StickerPackKind.FAVOURITES] and [SAVED][StickerPackKind.SAVED]
 * packs have an empty [name]. A screen labels them by [kind].
 *
 * [shownInRow] says the pack has its own thumbnail in the picker's row. It is
 * false for a pack a WhatsApp import made, until its owner switches it on.
 * Those two unnamed packs are always in the row.
 */
data class StickerPack(
    val id: String,
    val name: String,
    val publisher: String?,
    val kind: StickerPackKind,
    val originPackId: String?,
    val stickers: List<Sticker>,
    val createdAt: Long,
    val updatedAt: Long,
    val shownInRow: Boolean = true,
)

/**
 * A pack someone else may own, as it is offered for adding to the library.
 *
 * [packId] is the pack that was looked up. [rootPackId] is the pack it was
 * first copied from, or [packId] itself when it is nobody's copy. A copy in the
 * library remembers the root, so the same pack reached through two people is
 * recognised as one. [isInLibrary] says the library already holds it, as the
 * user's own pack or as a copy.
 *
 * The files of [stickers] may not be on this device yet.
 */
data class StickerPackPreview(
    val packId: String,
    val rootPackId: String,
    val name: String,
    val publisher: String?,
    val stickers: List<Sticker>,
    val isInLibrary: Boolean,
)

/**
 * One file in the WhatsApp sticker folder, before it is imported. WhatsApp
 * keeps a still or animated sticker as a `.webp` and a Lottie sticker as a `.was`.
 */
data class WhatsAppStickerFile(
    val uri: String,
    val name: String,
    val sizeBytes: Long,
    val lastModified: Long,
) {
    /** Whether an image request can draw the file. A `.was` is a zip around an animation, which none can. */
    val hasStill: Boolean
        get() = !name.endsWith(LOTTIE_SUFFIX, ignoreCase = true)

    companion object {
        const val WEBP_SUFFIX = ".webp"
        const val LOTTIE_SUFFIX = ".was"
    }
}

/**
 * What an import did.
 *
 * [imported] counts stickers added to a pack. [duplicates] counts stickers the
 * target pack already held. [rejected] counts inputs that were refused: a file
 * that could not be read, is not a sticker or is too large, and an archive that
 * broke a cap counts once. [packIds] are the packs that gained a sticker.
 *
 * The last two are counted only by an import that skips what the library
 * knows. [alreadyInLibrary] counts stickers left out because some pack holds
 * them. [deletedEarlier] counts stickers left out because they were deleted
 * from the library.
 */
data class StickerImportResult(
    val imported: Int,
    val duplicates: Int,
    val rejected: Int,
    val packIds: List<String>,
    val alreadyInLibrary: Int = 0,
    val deletedEarlier: Int = 0,
)
