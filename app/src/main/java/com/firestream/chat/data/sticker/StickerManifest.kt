package com.firestream.chat.data.sticker

import com.firestream.chat.data.local.entity.StickerEntity
import com.firestream.chat.data.local.entity.StickerPackEntity
import com.firestream.chat.data.local.entity.StickerSyncState
import com.firestream.chat.data.remote.source.RemoteSticker
import com.firestream.chat.data.remote.source.RemoteStickerPack
import com.firestream.chat.data.util.parseStickerPackKind
import com.firestream.chat.domain.model.StickerFormat

/**
 * The two directions between the library's rows and a pack manifest.
 *
 * A manifest read from the backend is checked before a row is built from it,
 * the owner's own like anyone else's. A sticker id that is not a hash is
 * dropped, because an id becomes a file name. Names are cleaned like imported
 * ones, sizes are clamped, and the lists are capped.
 */
internal object StickerManifest {

    /** How many stickers a manifest lists. A larger pack is backed up without its tail. */
    const val MAX_STICKERS = 4000

    /** As many as an import keeps (`WaStickerMetadata`), so a restore brings every tag back. */
    const val MAX_EMOJIS = 8
    private const val MAX_EMOJI_LENGTH = 32

    /**
     * What the sticker list may weigh. A manifest is one document of at most
     * 1 MiB, and the rest is left to the pack's own fields. [MAX_STICKERS]
     * stickers with a few emojis each stay under it. A pack whose stickers
     * carry many long tags is cut short before the backend would refuse it.
     */
    const val MAX_LIST_BYTES = 900_000

    /** An entry without its emojis: each field name and string in UTF-8 plus one byte, eight bytes per number. Rounded up. */
    private const val ENTRY_BYTES = 128
    private const val MAX_IMPORT_KEY_LENGTH = 512
    private val PACK_ID = Regex("[A-Za-z0-9_-]{1,64}")

    /** Whether [id] can name a manifest. A pack id from a message or a manifest is checked before it is used. */
    fun isValidPackId(id: String): Boolean = PACK_ID.matches(id)

    /** The manifest of [pack] and its [stickers], in their order, as [ownerId]'s. */
    fun of(pack: StickerPackEntity, stickers: List<StickerEntity>, ownerId: String) = RemoteStickerPack(
        id = pack.id,
        ownerId = ownerId,
        name = pack.name,
        publisher = pack.publisher,
        kind = pack.kind,
        originPackId = pack.originPackId,
        importKey = pack.importKey,
        sortOrder = pack.sortOrder,
        createdAt = pack.createdAt,
        updatedAt = pack.updatedAt,
        stickers = listed(stickers),
        shownInRow = pack.shownInRow,
    )

    /** The entries of [stickers] a manifest has room for, by count and by weight. */
    private fun listed(stickers: List<StickerEntity>): List<RemoteSticker> = buildList {
        var bytes = 0
        for (sticker in stickers) {
            if (size == MAX_STICKERS) break
            if (!StickerFiles.isValidId(sticker.id)) continue
            val emojis = sticker.emojis.take(MAX_EMOJIS)
            bytes += ENTRY_BYTES + emojis.sumOf { it.toByteArray().size + 1 }
            if (bytes > MAX_LIST_BYTES) break
            add(RemoteSticker(sticker.id, sticker.format, sticker.width, sticker.height, sticker.isAnimated, emojis))
        }
    }

    /**
     * The pack row [remote] stands for, marked as synced. A manifest without
     * `shownInRow` gets what a new pack with its import key would. The two
     * unnamed packs are in the row whatever a manifest says.
     */
    fun packOf(remote: RemoteStickerPack): StickerPackEntity {
        val kind = parseStickerPackKind(remote.kind)
        // Not cleaned: it has to equal the key an import computes, separators included.
        val importKey = remote.importKey?.takeIf { it.isNotEmpty() && it.length <= MAX_IMPORT_KEY_LENGTH }
        return StickerPackEntity(
            id = remote.id,
            name = cleanStickerText(remote.name).orEmpty(),
            publisher = remote.publisher?.let(::cleanStickerText),
            kind = kind.name,
            originPackId = remote.originPackId?.takeIf(::isValidPackId),
            importKey = importKey,
            sortOrder = remote.sortOrder,
            createdAt = remote.createdAt,
            updatedAt = remote.updatedAt,
            syncState = StickerSyncState.SYNCED.name,
            shownInRow = !kind.isNamed || (remote.shownInRow ?: StickerPackEntity.shownInRowByDefault(importKey)),
        )
    }

    /**
     * The sticker rows [remote] lists, in its order. An entry is left out when
     * its id is not a hash, when it repeats an earlier one, or when its format
     * is one this build does not know.
     */
    fun stickersOf(remote: RemoteStickerPack, now: Long): List<StickerEntity> = remote.stickers.asSequence()
        .filter { StickerFiles.isValidId(it.id) }
        .distinctBy { it.id }
        .mapNotNull { sticker ->
            val format = StickerFormat.entries.firstOrNull { it.name == sticker.format } ?: return@mapNotNull null
            StickerEntity(
                id = sticker.id,
                format = format.name,
                width = sticker.width.coerceIn(0, StickerFiles.MAX_DIMENSION),
                height = sticker.height.coerceIn(0, StickerFiles.MAX_DIMENSION),
                isAnimated = sticker.isAnimated,
                emojis = sticker.emojis.filter { it.length in 1..MAX_EMOJI_LENGTH }.take(MAX_EMOJIS),
                createdAt = now,
            )
        }
        .take(MAX_STICKERS)
        .toList()
}
