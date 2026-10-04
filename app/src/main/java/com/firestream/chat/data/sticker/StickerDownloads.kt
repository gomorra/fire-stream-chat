// region: AGENT-NOTE
// Responsibility: The local copy of a sticker a message points at — fetched
//   once, checked against the id the message claims, and stored in StickerFiles
//   together with its `stickers` row.
// Owns: the hash check of received bytes (a mismatch stores nothing); the
//   refusals remembered for this process, so a bad sticker is fetched once.
// Collaborators: MediaFileManager.downloadFor (the only caller — every download
//   of a message's media is routed there), StickerFiles (the file, and the lock
//   that keeps it with its row), StickerDao, OkHttpClient.
// Don't put here: uploads (StickerObjectSource), pack membership
//   (StickerRepositoryImpl), the message row's localUri (the caller writes it),
//   the auto-download preference (MessageRepositoryImpl, MediaBackfillWorker).
// endregion

package com.firestream.chat.data.sticker

import android.util.Log
import com.firestream.chat.data.local.dao.StickerDao
import com.firestream.chat.data.local.entity.StickerEntity
import com.firestream.chat.data.util.SingleFlight
import com.firestream.chat.data.util.parseStickerFormat
import com.firestream.chat.domain.model.StickerFormat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Gives a received sticker message a local file.
 *
 * A message names its sticker by the hash of the bytes, and both the id and
 * the url come from the sender. So the bytes are hashed before anything is
 * written, and only a match reaches the directory. A sticker this device
 * already holds is never fetched again, whoever sent it.
 */
@Singleton
class StickerDownloads @Inject constructor(
    private val stickerFiles: StickerFiles,
    private val stickerDao: StickerDao,
    private val httpClient: OkHttpClient,
) {

    private val inFlight = SingleFlight<String, File?>()

    /** Id and url pairs that were refused. A refusal is final for the bytes a url serves, so it is not asked again. */
    private val refused: MutableSet<String> = ConcurrentHashMap.newKeySet()

    /**
     * The file of the sticker [stickerId], fetched from [mediaUrl] when this
     * device does not hold it yet.
     *
     * Returns `null` when the message cannot have a local file: [stickerId] is
     * not a sticker id, or what [mediaUrl] serves is not that sticker. The
     * message then renders from its url. Throws [IOException] when the download
     * itself fails, so the caller can try again later.
     */
    suspend fun ensureLocal(stickerId: String?, mediaUrl: String): File? {
        if (stickerId == null || !StickerFiles.isValidId(stickerId)) {
            Log.w(TAG, "refused a sticker message whose sticker id is not a hash")
            return null
        }
        val attempt = "$stickerId $mediaUrl"
        if (attempt in refused) return null
        // Per id and url: a second message with another url is not answered by this one's refusal.
        return inFlight.run(attempt) {
            held(stickerId) ?: fetch(stickerId, mediaUrl).also { if (it == null) refused += attempt }
        }
    }

    /** The sticker's file when the library knows the sticker and the file is there. */
    private suspend fun held(stickerId: String): File? {
        val row = stickerDao.getSticker(stickerId) ?: return null
        return stickerFiles.fileFor(stickerId, parseStickerFormat(row.format)).takeIf { it.isFile }
    }

    private suspend fun fetch(stickerId: String, mediaUrl: String): File? = withContext(Dispatchers.IO) {
        // A file without a row is what a destructive database bump leaves behind.
        // It goes through the same check as a download, and saves one when it passes.
        val bytes = orphan(stickerId)?.readBytes().matching(stickerId) ?: download(mediaUrl).matching(stickerId)
        if (bytes == null) {
            Log.w(TAG, "refused sticker $stickerId: its url does not serve those bytes")
            return@withContext null
        }
        stickerFiles.rowLock.withLock {
            val stored = stickerFiles.store(bytes)
            if (stored == null) {
                Log.w(TAG, "refused sticker $stickerId: not a sticker file")
                return@withContext null
            }
            val emojis = WaStickerMetadata.parse(stored.exif)?.emojis.orEmpty()
            stickerDao.mergeStickers(listOf(StickerEntity.of(stored, emojis, System.currentTimeMillis())))
            stickerFiles.fileFor(stored.id, stored.format)
        }
    }

    /** These bytes when they hash to [stickerId], else `null`. */
    private fun ByteArray?.matching(stickerId: String): ByteArray? =
        this?.takeIf { StickerFiles.sha256Hex(it) == stickerId }

    private fun orphan(stickerId: String): File? =
        StickerFormat.entries.firstNotNullOfOrNull { format -> stickerFiles.fileFor(stickerId, format).takeIf { it.isFile } }

    /** At most [StickerFiles.MAX_BYTES] of what [mediaUrl] serves, or `null` when it serves more or is no url. */
    private fun download(mediaUrl: String): ByteArray? {
        val request = try {
            Request.Builder().url(mediaUrl).build()
        } catch (e: IllegalArgumentException) {
            return null
        }
        return httpClient.newCall(request).execute().use { response ->
            if (!response.isSuccessful) throw IOException("Sticker download failed: ${response.code}")
            val body = response.body ?: throw IOException("Empty response body")
            body.byteStream().use(stickerFiles::readCapped)
        }
    }

    private companion object {
        const val TAG = "StickerDownloads"
    }
}
