package com.firestream.chat.data.remote.pocketbase

import com.firestream.chat.data.remote.source.RemoteStickerPack
import com.firestream.chat.data.remote.source.StickerPackChanges
import com.firestream.chat.data.remote.source.StickerPackSource
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Stub. This flavor keeps no pack manifests in v0, so the library is neither
 * backed up nor restored and no pack can be viewed. [isSupported] says so, and
 * nothing calls the two writes while it is false.
 */
@Singleton
class PocketBaseStickerPackSource @Inject constructor() : StickerPackSource {

    override val isSupported: Boolean = false

    override fun observeOwnPacks(ownerId: String): Flow<StickerPackChanges> = emptyFlow()

    override suspend fun fetchPack(packId: String): RemoteStickerPack? = null

    override suspend fun writePack(pack: RemoteStickerPack): Unit =
        throw UnsupportedOperationException("Sticker packs are not synced on this backend")

    override suspend fun deletePack(packId: String): Unit =
        throw UnsupportedOperationException("Sticker packs are not synced on this backend")
}
