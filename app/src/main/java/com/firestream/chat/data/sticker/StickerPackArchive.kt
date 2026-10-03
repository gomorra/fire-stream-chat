package com.firestream.chat.data.sticker

import java.io.BufferedInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.util.zip.ZipInputStream

/** An archive that is not a sticker pack, or that broke one of [StickerPackArchive.Limits]. */
class StickerArchiveException(message: String) : IOException(message)

/**
 * Reads a `.wastickers` pack, which is a zip of WebP files beside a `title.txt`
 * and an `author.txt`.
 *
 * The archive is untrusted. Three caps bound what it can cost: the number of
 * entries, the bytes of one sticker, and the bytes decompressed in total. The
 * total counts every entry, including the ones that are skipped, so an entry
 * that inflates without end stops the read instead of being drained.
 *
 * An entry's name decides only what the entry is. It is never used as a path,
 * so nothing an archive names can reach the file system.
 */
object StickerPackArchive {

    data class Limits(
        val maxEntries: Int = 200,
        val maxEntryBytes: Int = StickerFiles.MAX_BYTES,
        val maxTotalBytes: Long = 32L * 1024 * 1024,
    )

    /** [skipped] counts sticker entries larger than [Limits.maxEntryBytes]. */
    data class Summary(val title: String?, val author: String?, val skipped: Int)

    private const val MAX_TEXT_BYTES = 1024

    /**
     * Hands each sticker entry's bytes to [onSticker], in archive order, and
     * returns the pack's title and author. Blocking: call it off the main thread.
     *
     * Throws [StickerArchiveException] when [input] holds no sticker entry, has
     * more than [Limits.maxEntries] entries, inflates past [Limits.maxTotalBytes]
     * or names an entry in bytes that are not UTF-8,
     * and [IOException] when the zip itself is malformed. Stickers handed over
     * before the throw are the caller's to undo.
     */
    suspend fun read(
        input: InputStream,
        limits: Limits = Limits(),
        onSticker: suspend (ByteArray) -> Unit,
    ): Summary {
        val zip = ZipInputStream(input)
        var entries = 0
        var skipped = 0
        var title: String? = null
        var author: String? = null
        var stickerEntries = 0
        val budget = Budget(limits.maxTotalBytes)
        while (true) {
            // A name that is not UTF-8 makes the JDK throw IllegalArgumentException, not IOException.
            val entry = try {
                zip.nextEntry
            } catch (e: IllegalArgumentException) {
                throw StickerArchiveException("The archive has an entry whose name cannot be read")
            } ?: break
            if (++entries > limits.maxEntries) {
                throw StickerArchiveException("The archive has more than ${limits.maxEntries} entries")
            }
            val name = entry.name.substringAfterLast('/').substringAfterLast('\\').lowercase()
            when {
                name == "title.txt" -> title = zip.drain(MAX_TEXT_BYTES, budget)?.toText() ?: title
                name == "author.txt" -> author = zip.drain(MAX_TEXT_BYTES, budget)?.toText() ?: author
                name.endsWith(".webp") -> {
                    stickerEntries++
                    val bytes = zip.drain(limits.maxEntryBytes, budget)
                    if (bytes == null) skipped++ else onSticker(bytes)
                }
                else -> zip.drain(keep = 0, budget)
            }
        }
        if (stickerEntries == 0) throw StickerArchiveException("Not a sticker pack archive")
        return Summary(title, author, skipped)
    }

    /** Whether [input] starts with a zip local-file header. Leaves the stream where it was. */
    fun isArchive(input: BufferedInputStream): Boolean {
        input.mark(ZIP_HEADER.size)
        val head = ByteArray(ZIP_HEADER.size)
        var read = 0
        while (read < head.size) {
            val n = input.read(head, read, head.size - read)
            if (n < 0) break
            read += n
        }
        input.reset()
        return read == head.size && head.contentEquals(ZIP_HEADER)
    }

    private val ZIP_HEADER = byteArrayOf('P'.code.toByte(), 'K'.code.toByte(), 3, 4)

    private class Budget(private val max: Long) {
        private var used = 0L

        fun spend(bytes: Int) {
            used += bytes
            if (used > max) throw StickerArchiveException("The archive unpacks to more than $max bytes")
        }
    }

    /**
     * Reads the current entry to its end, charging every byte to [budget], and
     * returns its content when it fits in [keep] bytes, else `null`.
     */
    private fun ZipInputStream.drain(keep: Int, budget: Budget): ByteArray? {
        val kept = ByteArrayOutputStream()
        var fits = keep > 0
        val buffer = ByteArray(8 * 1024)
        while (true) {
            val read = read(buffer)
            if (read < 0) break
            budget.spend(read)
            if (fits && kept.size() + read <= keep) {
                kept.write(buffer, 0, read)
            } else {
                fits = false
                kept.reset()
            }
        }
        return if (fits) kept.toByteArray() else null
    }

    private fun ByteArray.toText(): String? = cleanStickerText(String(this, Charsets.UTF_8))
}
