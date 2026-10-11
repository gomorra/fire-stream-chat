package com.firestream.chat.data.remote.pocketbase

import com.firestream.chat.data.remote.source.RemoteStickerName
import com.firestream.chat.data.remote.source.StickerNameSource
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Stub. This flavor has no naming service in v0, so no sticker is named
 * automatically. [isSupported] says so, and nothing calls the other two while
 * it is false.
 */
@Singleton
class PocketBaseStickerNameSource @Inject constructor() : StickerNameSource {

    override val isSupported: Boolean = false

    override suspend fun request(stickerId: String, format: String): Unit =
        throw UnsupportedOperationException("Stickers are not named on this backend")

    override suspend fun fetch(stickerId: String): RemoteStickerName? =
        throw UnsupportedOperationException("Stickers are not named on this backend")
}
