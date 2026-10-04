package com.firestream.chat.data.remote.source

import kotlinx.coroutines.flow.Flow

/** One sticker as a pack manifest lists it. It names the file by its hash and holds no url. */
data class RemoteSticker(
    val id: String,
    val format: String,
    val width: Int,
    val height: Int,
    val isAnimated: Boolean,
    val emojis: List<String>,
)

/**
 * A pack's manifest on the backend: the pack's fields and its stickers in order.
 *
 * Everything in one that another user wrote is that user's claim. `StickerManifest`
 * checks it before a row is built from it.
 */
data class RemoteStickerPack(
    val id: String,
    val ownerId: String,
    val name: String,
    val publisher: String?,
    val kind: String,
    val originPackId: String?,
    val importKey: String?,
    val sortOrder: Int,
    val createdAt: Long,
    val updatedAt: Long,
    val stickers: List<RemoteSticker>,
)

/** What changed among one user's packs since the last emission: packs added or modified, and the ids of packs deleted. */
data class StickerPackChanges(
    val upserted: List<RemoteStickerPack>,
    val removedIds: List<String>,
)

/**
 * Backend-neutral home of pack manifests. A manifest is the backup of a pack
 * and what "view this pack" reads. Only its owner writes it. Any signed-in user
 * who has its id can fetch it.
 */
interface StickerPackSource {

    /** False for a backend that keeps no manifests. Nothing is synced then, and no pack can be viewed. */
    val isSupported: Boolean

    /**
     * The changes to [ownerId]'s packs, starting with every pack the backend
     * holds. A removal is emitted only for a pack that was there and is gone, so
     * a pack that is missing from an incomplete first read is not taken for a
     * deleted one.
     */
    fun observeOwnPacks(ownerId: String): Flow<StickerPackChanges>

    /** The manifest [packId], whoever owns it, or `null` when the backend has none. */
    suspend fun fetchPack(packId: String): RemoteStickerPack?

    /** Creates or replaces the manifest of [pack]. Returns once the backend has acknowledged it. */
    suspend fun writePack(pack: RemoteStickerPack)

    /** Deletes the manifest [packId]. Deleting one that is not there is not an error. */
    suspend fun deletePack(packId: String)
}
