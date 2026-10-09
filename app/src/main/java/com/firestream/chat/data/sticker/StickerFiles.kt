// region: AGENT-NOTE
// Responsibility: Where a sticker's file lives on this device —
//   filesDir/stickers/<sha256 of the bytes>.<ext> — and the only way a file gets
//   there: checked for size and format, hashed, written to a temp file and renamed.
// Owns: the stickers directory; the 1 MB and 2048 px caps; the id rule (64
//   lowercase hex digits), which is what keeps an id from steering a path; the
//   lock that keeps a file and its row together (rowLock); the first-frame PNG
//   beside a Lottie sticker.
// Collaborators: StickerRepositoryImpl (store() per imported file or archive
//   entry, discard() to undo a refused archive), StickerDownloads (store() for a
//   sticker a message points at), OutboxSender and MessageRepositoryImpl
//   (fileFor() of a sticker being sent), WebpContainer and LottieContainer (the
//   format checks), LottieThumbnails (the first frame).
// Don't put here: pack membership or any Room access (StickerRepositoryImpl),
//   archive reading (StickerPackArchive), message media (MediaFileManager,
//   DocumentFiles).
// endregion

package com.firestream.chat.data.sticker

import android.content.Context
import android.net.Uri
import com.firestream.chat.domain.model.StickerFormat
import com.firestream.chat.domain.util.WebpContainer
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileNotFoundException
import java.io.IOException
import java.io.InputStream
import java.security.MessageDigest
import javax.inject.Inject
import javax.inject.Singleton

/**
 * A sticker file in the directory. [isNew] is false when the same bytes were
 * already there. [metadata] is the pack and the emojis the file names, when it
 * names any.
 */
class StoredSticker(
    val id: String,
    val format: StickerFormat,
    val width: Int,
    val height: Int,
    val isAnimated: Boolean,
    val metadata: WaStickerMetadata?,
    val isNew: Boolean,
)

/**
 * The content-addressed home of sticker files. A file's name is the SHA-256 of
 * its bytes, so the same sticker imported twice, or sent by two people, is one file.
 */
@Singleton
class StickerFiles @Inject constructor(
    @ApplicationContext private val context: Context,
    private val thumbnails: LottieThumbnails = LottieThumbnails(),
) {

    val dir: File
        get() = File(context.filesDir, DIR_NAME)

    /**
     * Held while a file is stored together with its `stickers` row, and while
     * files that have no row are deleted. A refused archive deletes the files it
     * wrote that no row names. A sticker received in between is such a file until
     * its row is written, so both sides take this lock.
     */
    val rowLock = Mutex()

    /** The file the sticker [id] lives in. Throws for an [id] that is not a SHA-256 in lowercase hex. */
    fun fileFor(id: String, format: StickerFormat): File {
        require(isValidId(id)) { "Not a sticker id" }
        return File(dir, "$id.${format.extension}")
    }

    /**
     * Opens [uri] for reading: a `content://` uri through its provider, and a
     * `file://` uri or a bare path only when the file is in the app's cache
     * directory. A uri can come from another app, and a path anywhere else would
     * let it name one of the app's own files. Throws [FileNotFoundException] when
     * it cannot be opened, and [SecurityException] when its grant is gone.
     */
    fun open(uri: String): InputStream {
        val path = if (uri.startsWith("/")) uri else Uri.parse(uri).takeIf { it.scheme == "file" }?.path
        if (path != null) {
            val file = File(path).canonicalFile
            if (!file.startsWith(context.cacheDir.canonicalFile)) throw FileNotFoundException(uri)
            return FileInputStream(file)
        }
        return context.contentResolver.openInputStream(Uri.parse(uri)) ?: throw FileNotFoundException(uri)
    }

    /** [store] for a stream, of which at most [MAX_BYTES] + 1 bytes are read. The stream is not closed. */
    suspend fun store(input: InputStream): StoredSticker? = withContext(Dispatchers.IO) {
        readCapped(input)?.let { store(it) }
    }

    /**
     * Puts [bytes] into the directory under the hash of the stored file and says
     * what they are. The first bytes decide the format, never a name. Returns
     * `null` for bytes that are not a sticker: a file larger than [MAX_BYTES],
     * neither a whole WebP file nor a Lottie animation that [LottieContainer]
     * accepts and Lottie can draw, or wider or taller than [MAX_DIMENSION].
     * Throws [IOException] when the file cannot be written.
     *
     * Bare animation JSON, which a `.was` holds, is stored compressed. Its id
     * is then not the hash of [bytes].
     */
    suspend fun store(bytes: ByteArray): StoredSticker? = withContext(Dispatchers.IO) {
        if (LottieContainer.isJsonObject(bytes)) storeLottie(bytes, file = null) else storeFile(bytes)
    }

    /**
     * [store] for a sticker a message or a manifest names by its id. The bytes
     * are stored unchanged or not at all, so the id of what is stored is the
     * hash of exactly what was downloaded.
     */
    suspend fun storeReceived(bytes: ByteArray): StoredSticker? = withContext(Dispatchers.IO) { storeFile(bytes) }

    /** Stores [bytes] as the sticker file they are: a `.tgs` or a WebP. */
    private fun storeFile(bytes: ByteArray): StoredSticker? {
        if (LottieContainer.isGzip(bytes)) return storeLottie(bytes, file = bytes)
        if (bytes.size > MAX_BYTES) return null
        val info = WebpContainer.parse(bytes) ?: return null
        if (info.width > MAX_DIMENSION || info.height > MAX_DIMENSION) return null
        val id = sha256Hex(bytes)
        val isNew = put(id, bytes, StickerFormat.WEBP)
        return StoredSticker(id, StickerFormat.WEBP, info.width, info.height, info.isAnimated, WaStickerMetadata.parse(info.exif), isNew)
    }

    /**
     * [file] is what is stored: a `.tgs` as it came, or `null` for bare
     * animation JSON, which is compressed first.
     *
     * The first frame is drawn before anything is written, and an animation
     * Lottie cannot draw is not a sticker. So a file in the directory is one a
     * screen can play. The first frame is written beside the file whenever it
     * is missing, so a sticker that was received or restored has it as well.
     */
    private fun storeLottie(bytes: ByteArray, file: ByteArray?): StoredSticker? {
        val animation = LottieContainer.read(bytes) ?: return null
        val stored = file ?: LottieContainer.compress(animation.json)
        if (stored.size > MAX_BYTES) return null
        val format = StickerFormat.LOTTIE
        val id = sha256Hex(stored)
        val still = stillFor(id, format)
        if (!(still.isFile && holds(id, stored, format))) {
            val firstFrame = thumbnails.firstFrame(animation.json) ?: return null
            write(firstFrame, still)
        }
        val isNew = put(id, stored, format)
        return StoredSticker(id, format, animation.width, animation.height, isAnimated = true, animation.metadata, isNew)
    }

    /** Whether the file of the sticker [id] is there, whole. */
    private fun holds(id: String, bytes: ByteArray, format: StickerFormat): Boolean =
        fileFor(id, format).let { it.isFile && it.length() == bytes.size.toLong() }

    /** Writes [bytes], which hash to [id], unless that file is there already. Returns whether it wrote. */
    private fun put(id: String, bytes: ByteArray, format: StickerFormat): Boolean {
        val isNew = !holds(id, bytes, format)
        if (isNew) write(bytes, fileFor(id, format))
        return isNew
    }

    /** The first frame of the sticker [id]. For a WebP that is the sticker's own file. */
    fun stillFor(id: String, format: StickerFormat): File = File(format.stillPathOf(fileFor(id, format).path))

    /** Deletes the file of the sticker [id], and its first frame, if there are any. */
    suspend fun discard(id: String, format: StickerFormat) = withContext(Dispatchers.IO) {
        stillFor(id, format).delete()
        fileFor(id, format).delete()
        Unit
    }

    /** A rename, so a reader never sees a file that is only partly written. */
    private fun write(bytes: ByteArray, target: File) {
        val parent = dir.apply { mkdirs() }
        val partial = File.createTempFile("import-", ".$PARTIAL_EXTENSION", parent)
        try {
            partial.writeBytes(bytes)
            if (!partial.renameTo(target)) throw IOException("Cannot store sticker ${target.name}")
        } finally {
            partial.delete()
        }
    }

    /** The bytes of [input], or `null` when it holds more than [MAX_BYTES]. No more than that is read. The stream is not closed. */
    fun readCapped(input: InputStream): ByteArray? {
        val out = ByteArrayOutputStream()
        val buffer = ByteArray(16 * 1024)
        while (true) {
            val read = input.read(buffer)
            if (read < 0) break
            if (out.size() + read > MAX_BYTES) return null
            out.write(buffer, 0, read)
        }
        return out.toByteArray()
    }

    companion object {
        const val DIR_NAME = "stickers"
        const val MAX_BYTES = 1024 * 1024
        const val MAX_DIMENSION = 2048
        private const val PARTIAL_EXTENSION = "part"
        private val ID = Regex("[0-9a-f]{64}")

        fun isValidId(id: String): Boolean = ID.matches(id)

        private const val HEX = "0123456789abcdef"

        fun sha256Hex(bytes: ByteArray): String {
            val digest = MessageDigest.getInstance("SHA-256").digest(bytes)
            val hex = CharArray(digest.size * 2)
            digest.forEachIndexed { index, byte ->
                hex[index * 2] = HEX[(byte.toInt() shr 4) and 0xF]
                hex[index * 2 + 1] = HEX[byte.toInt() and 0xF]
            }
            return String(hex)
        }
    }
}
