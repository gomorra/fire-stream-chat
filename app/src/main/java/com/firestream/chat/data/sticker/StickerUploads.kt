// region: AGENT-NOTE
// Responsibility: Getting a library sticker's file onto the backend, once — the
//   url of its shared object, uploaded only when the backend does not hold it.
// Owns: one lock per sticker id, so a send and a backup of the same sticker
//   never upload it twice; the write of `stickers.remoteUrl` once the object is
//   known to be there.
// Collaborators: OutboxSender (the first send of a sticker), StickerSyncWorker
//   (the backup of a pack), StickerObjectSource (look up, then upload),
//   StickerDao, StickerFiles.
// Don't put here: the download of a sticker (StickerDownloads), a message
//   row's mediaUrl (OutboxSender persists it), a second lock for the same
//   sticker anywhere else.
// endregion

package com.firestream.chat.data.sticker

import com.firestream.chat.data.local.dao.StickerDao
import com.firestream.chat.data.remote.source.StickerObjectSource
import com.firestream.chat.data.util.KeyedMutex
import com.firestream.chat.data.util.parseStickerFormat
import java.io.FileNotFoundException
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class StickerUploads @Inject constructor(
    private val stickerDao: StickerDao,
    private val stickerFiles: StickerFiles,
    private val stickerObjectSource: StickerObjectSource,
) {

    private val locks = KeyedMutex<String>()

    /**
     * Where the backend holds the sticker [stickerId]: the url its library row
     * already has, else the object's, which is looked up and uploaded only when
     * the backend does not hold it. The url is kept on the library row, so the
     * next caller asks nobody.
     *
     * One sticker at a time: of two callers with a new sticker, the second waits
     * and then finds the url the first one stored.
     *
     * Throws [IllegalStateException] when the library has no such sticker and
     * [FileNotFoundException] when its file is not on this device.
     */
    suspend fun ensureUploaded(stickerId: String): String = locks.withLock(stickerId) {
        val sticker = stickerDao.getSticker(stickerId)
            ?: throw IllegalStateException("Sticker $stickerId is not in the library")
        sticker.remoteUrl ?: run {
            val format = parseStickerFormat(sticker.format)
            val file = stickerFiles.fileFor(stickerId, format)
            if (!file.isFile) throw FileNotFoundException("The file of sticker $stickerId is missing")
            stickerObjectSource.ensureUploaded(stickerId, format.extension, format.mimeType, file)
                .also { stickerDao.setRemoteUrl(stickerId, it) }
        }
    }
}
