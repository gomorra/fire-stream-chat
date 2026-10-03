// region: AGENT-NOTE
// Responsibility: Where a sticker's file lives on this device —
//   filesDir/stickers/<sha256 of the bytes>.<ext> — and the only way a file gets
//   there: checked for size and format, hashed, written to a temp file and renamed.
// Owns: the stickers directory; the 1 MB and 2048 px caps; the id rule (64
//   lowercase hex digits), which is what keeps an id from steering a path.
// Collaborators: StickerRepositoryImpl (store() per imported file or archive
//   entry, discard() to undo a refused archive), WebpContainer (the format check).
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
 * already there. [exif] is the file's raw EXIF chunk, for [WaStickerMetadata].
 */
class StoredSticker(
    val id: String,
    val format: StickerFormat,
    val width: Int,
    val height: Int,
    val isAnimated: Boolean,
    val exif: ByteArray?,
    val isNew: Boolean,
)

/**
 * The content-addressed home of sticker files. A file's name is the SHA-256 of
 * its bytes, so the same sticker imported twice, or sent by two people, is one file.
 */
@Singleton
class StickerFiles @Inject constructor(
    @ApplicationContext private val context: Context,
) {

    val dir: File
        get() = File(context.filesDir, DIR_NAME)

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
     * Puts [bytes] into the directory under their hash and says what they are.
     * Returns `null` for bytes that are not a sticker: larger than [MAX_BYTES],
     * not a whole WebP file, or wider or taller than [MAX_DIMENSION]. Throws
     * [IOException] when the file cannot be written.
     */
    suspend fun store(bytes: ByteArray): StoredSticker? = withContext(Dispatchers.IO) {
        if (bytes.size > MAX_BYTES) return@withContext null
        val info = WebpContainer.parse(bytes) ?: return@withContext null
        if (info.width > MAX_DIMENSION || info.height > MAX_DIMENSION) return@withContext null
        val id = sha256Hex(bytes)
        val format = StickerFormat.WEBP
        val target = fileFor(id, format)
        val isNew = !(target.isFile && target.length() == bytes.size.toLong())
        if (isNew) write(bytes, target)
        StoredSticker(id, format, info.width, info.height, info.isAnimated, info.exif, isNew)
    }

    /** Deletes the file of the sticker [id], if there is one. */
    suspend fun discard(id: String, format: StickerFormat) = withContext(Dispatchers.IO) {
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

    private fun readCapped(input: InputStream): ByteArray? {
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
