package com.firestream.chat.ui.stickers

import com.firestream.chat.domain.model.Sticker
import com.firestream.chat.domain.model.StickerImportResult
import com.firestream.chat.domain.model.StickerPack
import com.firestream.chat.domain.model.StickerPackKind

/** The name a pack is shown by. The favourites and the loose stickers have no name of their own. */
internal fun StickerPack.label(): String = when (kind) {
    StickerPackKind.FAVOURITES -> "Favourites"
    StickerPackKind.SAVED -> "Saved stickers"
    StickerPackKind.USER, StickerPackKind.INSTALLED -> name
}

/** Whether the rename action is offered. The repository refuses a rename of the two unnamed packs. */
internal val StickerPack.canRename: Boolean
    get() = kind.isNamed

/** One line saying what an import did, leaving out the counts that are zero. */
internal fun importSummary(result: StickerImportResult): String {
    val parts = buildList {
        if (result.imported > 0) add("Imported ${result.imported} ${stickers(result.imported)}")
        val known = result.duplicates + result.alreadyInLibrary
        if (known > 0) add("$known already in the library")
        if (result.deletedEarlier > 0) add("${result.deletedEarlier} deleted earlier")
        if (result.rejected > 0) add("${result.rejected} could not be imported")
    }
    return if (parts.isEmpty()) "Nothing to import" else parts.joinToString(" · ")
}

/**
 * The names a list row shows for the sticker: the person's first, then the
 * German and the English name that were found. `null` for a sticker with none.
 */
internal fun Sticker.nameLine(): String? =
    (names + listOfNotNull(autoName?.nameDe, autoName?.nameEn)).filter { it.isNotEmpty() }.distinct()
        .joinToString(" · ").ifEmpty { null }

/**
 * What else a list row shows the sticker is found by: its emojis, the search
 * words that were found, and the text read off it. `null` for a sticker with none.
 */
internal fun Sticker.wordLine(): String? {
    val found = autoName
    return listOfNotNull(
        (emojis + found?.emojis.orEmpty()).distinct().joinToString(" ").ifEmpty { null },
        found?.words?.joinToString(", ")?.ifEmpty { null },
        found?.text?.ifEmpty { null }?.let { "“$it”" },
    ).joinToString(" · ").ifEmpty { null }
}

internal fun stickers(count: Int): String = if (count == 1) "sticker" else "stickers"
