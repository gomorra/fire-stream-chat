package com.firestream.chat.data.remote.pocketbase

import android.net.Uri
import com.firestream.chat.data.remote.source.StickerObjectSource
import com.firestream.chat.data.remote.source.StorageSource
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Uploads a sticker through this flavor's [StorageSource], under the sticker's
 * id in place of a message id. No lookup comes first, so every send uploads.
 * [StorageSource] is a stub in v0, so a sticker send fails here until it is real.
 */
@Singleton
class PocketBaseStickerObjectSource @Inject constructor(
    private val storageSource: StorageSource,
) : StickerObjectSource {

    override suspend fun ensureUploaded(id: String, extension: String, mimeType: String, file: File): String =
        storageSource.uploadMedia(STICKERS, id, Uri.fromFile(file), mimeType)

    private companion object {
        const val STICKERS = "stickers"
    }
}
