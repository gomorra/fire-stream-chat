package com.firestream.chat.domain.repository

import com.firestream.chat.domain.model.Sticker
import com.firestream.chat.domain.model.StickerImportResult
import com.firestream.chat.domain.model.StickerPack
import com.firestream.chat.domain.model.WhatsAppStickerFile
import kotlinx.coroutines.flow.Flow

/** The sticker library on this device. URIs cross this boundary as strings. */
interface StickerRepository {
    /** Every pack with its stickers, in the user's order. */
    fun observePacks(): Flow<List<StickerPack>>

    /** The stickers used most recently, newest first. Device-only. */
    fun observeRecents(): Flow<List<Sticker>>

    /** The sticker files directly inside the granted folder [treeUri], newest first. */
    suspend fun listWhatsAppFolder(treeUri: String): Result<List<WhatsAppStickerFile>>

    /**
     * Imports the sticker files and pack archives at [uris].
     *
     * A sticker that names its pack joins that pack, and an archive with a title
     * is one pack. A sticker that names none joins the pack called [loosePackName],
     * or the `SAVED` pack when it is null. Importing the same input again adds nothing.
     */
    suspend fun importFrom(uris: List<String>, loosePackName: String? = null): Result<StickerImportResult>

    suspend fun setFavourite(stickerId: String, favourite: Boolean): Result<Unit>

    /** Renames a `USER` or `INSTALLED` pack. Fails for a blank name and for the two unnamed packs. */
    suspend fun renamePack(packId: String, name: String): Result<Unit>

    /** Puts the packs in the order of [packIds]. Packs not listed keep their place after them. */
    suspend fun reorderPacks(packIds: List<String>): Result<Unit>

    suspend fun deletePack(packId: String): Result<Unit>

    /** Moves [stickerIds] from one pack to the end of another. */
    suspend fun moveStickers(stickerIds: List<String>, fromPackId: String, toPackId: String): Result<Unit>

    suspend fun removeStickers(packId: String, stickerIds: List<String>): Result<Unit>

    /** Records that [stickerId] was just sent, for [observeRecents]. */
    suspend fun markUsed(stickerId: String)
}
