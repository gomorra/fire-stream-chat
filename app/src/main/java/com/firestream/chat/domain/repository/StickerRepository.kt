package com.firestream.chat.domain.repository

import com.firestream.chat.domain.model.Sticker
import com.firestream.chat.domain.model.StickerCrop
import com.firestream.chat.domain.model.StickerDraft
import com.firestream.chat.domain.model.StickerImportResult
import com.firestream.chat.domain.model.StickerPack
import com.firestream.chat.domain.model.StickerPackPreview
import com.firestream.chat.domain.model.WhatsAppStickerFile
import kotlinx.coroutines.flow.Flow

/**
 * The sticker library on this device, and its backup under the account. Every
 * change to a pack is saved there, and the packs come back after a sign-in.
 * URIs cross this boundary as strings.
 */
interface StickerRepository {
    /**
     * Every pack with its stickers, in the user's order. While this is
     * collected, the library is kept in step with the account's backup.
     */
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

    /**
     * Adds [stickerId] to the favourites, or takes it out when it is one.
     * Succeeds with whether it is a favourite now. Fails for a sticker the
     * library does not hold.
     */
    suspend fun toggleFavourite(stickerId: String): Result<Boolean>

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

    /**
     * Fetches the file of [sticker] when this device does not hold it yet, and
     * says whether it is there now. Called when a sticker is first shown. It
     * does not throw: without a connection the answer is `false`.
     */
    suspend fun ensureFile(sticker: Sticker): Boolean

    /**
     * Looks up the pack [packId] that a received sticker names. Fails when the
     * pack is gone or was never shared.
     */
    suspend fun viewPack(packId: String): Result<StickerPackPreview>

    /** Adds a viewed pack to the library as an `INSTALLED` copy. Adding one that is already there changes nothing. */
    suspend fun installPack(preview: StickerPackPreview): Result<Unit>

    /**
     * Prepares the photo at [sourceUri] for the sticker maker: the photo, and
     * its subject cut out when this device can do that. Fails for a photo that
     * cannot be read. The draft replaces the one prepared before it.
     */
    suspend fun prepareStickerDraft(sourceUri: String): Result<StickerDraft>

    /**
     * Makes a sticker of the draft picture at [imagePath], placed by [crop],
     * and tags it with [emojis].
     *
     * It joins the pack [packId], which must be a `USER` pack. When [packId] is
     * null it joins the pack named [packName], which is made when it is not
     * there. Succeeds with the sticker's id.
     */
    suspend fun createSticker(
        imagePath: String,
        crop: StickerCrop,
        emojis: List<String>,
        packId: String?,
        packName: String,
    ): Result<String>
}
