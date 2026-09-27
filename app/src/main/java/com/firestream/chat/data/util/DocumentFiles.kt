// region: AGENT-NOTE
// Responsibility: Where a DOCUMENT message's file lives on this device —
//   filesDir/documents/<messageId>.<ext>, for sent and received documents alike —
//   what extension it gets, and what a picked uri says about itself (name, size).
// Owns: the documents directory; the extension rule (file name → mime type → URL
//   → "bin"); the move of a sent document's staged copy into the directory.
// Collaborators: MediaFileManager (downloads a received document to fileFor()),
//   OutboxSender (adopt() once uploaded, discard() for a row deleted while
//   queued), MessageRepositoryImpl (describe() at send).
// Don't put here: photo/video storage (MediaFileManager — MediaStore, Pictures/),
//   the outbox staging copy (OutboxFiles) — "Sends are idempotent by client id
//   and drained by OutboxWorker" (docs/PATTERNS.md) keeps staging there.
// endregion

package com.firestream.chat.data.util

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import android.webkit.MimeTypeMap
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton

/** What a picked document says about itself; either part may be unknown. */
data class DocumentInfo(val name: String?, val size: Long?)

/**
 * The app-private home of document files. Documents never go through MediaStore:
 * its image and video collections refuse any other mime type, and a chat's
 * documents are not the user's gallery.
 */
@Singleton
class DocumentFiles @Inject constructor(
    @ApplicationContext private val context: Context,
) {

    val dir: File
        get() = File(context.filesDir, DIR_NAME)

    /** The file a document message's copy lives in; the directory is created. */
    fun fileFor(messageId: String, fileName: String?, mimeType: String?, mediaUrl: String?): File =
        File(dir.apply { mkdirs() }, "$messageId.${extensionFor(fileName, mimeType, mediaUrl)}")

    /**
     * Moves a sent document's staged copy at [stagedPath] into the documents
     * directory and returns the new path, or `null` when there is nothing to
     * move (already moved by an earlier attempt, or never staged). A rename when
     * both sit on one filesystem, as they do; otherwise a copy through a `.part`
     * sibling, so a failed copy never leaves a truncated file a later download
     * would take for finished.
     */
    suspend fun adopt(messageId: String, stagedPath: String, fileName: String?, mimeType: String?): String? =
        withContext(Dispatchers.IO) {
            val source = File(stagedPath)
            if (!source.isFile) return@withContext null
            val target = fileFor(messageId, fileName, mimeType ?: mimeTypeOf(source.extension), mediaUrl = null)
            if (source.renameTo(target)) return@withContext target.absolutePath
            val partial = File(target.parentFile, "${target.name}.part")
            try {
                source.copyTo(partial, overwrite = true)
                if (!partial.renameTo(target)) return@withContext null
            } catch (e: IOException) {
                return@withContext null
            } finally {
                partial.delete()
            }
            source.delete()
            target.absolutePath
        }

    /** Deletes the document copy named after [messageId], if any — its message was deleted before it was sent. */
    suspend fun discard(messageId: String) = withContext(Dispatchers.IO) {
        dir.listFiles { file -> file.nameWithoutExtension == messageId }?.forEach { it.delete() }
        Unit
    }

    /**
     * Name and size of the document at [localUri], from the provider's
     * `OpenableColumns` for a `content://` uri or from the file itself for a path.
     * Never throws: a provider that answers nothing leaves both unknown — never a
     * guess from the uri, whose last segment is often an opaque id (`msf:1234`).
     */
    suspend fun describe(localUri: String): DocumentInfo = withContext(Dispatchers.IO) {
        if (localUri.startsWith("/")) {
            val file = File(localUri)
            return@withContext DocumentInfo(file.name, file.length().takeIf { file.isFile })
        }
        val uri = Uri.parse(localUri)
        if (uri.scheme == "file") {
            val file = File(uri.path ?: return@withContext DocumentInfo(null, null))
            return@withContext DocumentInfo(file.name, file.length().takeIf { file.isFile })
        }
        runCatching {
            context.contentResolver.query(
                uri, arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE), null, null, null,
            )?.use { cursor ->
                if (!cursor.moveToFirst()) return@use null
                val nameIndex = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                val sizeIndex = cursor.getColumnIndex(OpenableColumns.SIZE)
                DocumentInfo(
                    name = if (nameIndex >= 0 && !cursor.isNull(nameIndex)) cursor.getString(nameIndex) else null,
                    size = if (sizeIndex >= 0 && !cursor.isNull(sizeIndex)) cursor.getLong(sizeIndex) else null,
                )
            }
        }.getOrNull() ?: DocumentInfo(null, null)
    }

    companion object {
        const val DIR_NAME = "documents"
        private const val DEFAULT_EXTENSION = "bin"
        private const val MAX_EXTENSION = 10

        /**
         * The extension a document's local file gets: the one its [fileName]
         * carries, else the one [mimeType] maps to, else the one the [mediaUrl]'s
         * object name ends in, else `bin`. Only letters and digits survive, so a
         * name from another device cannot steer the path.
         */
        fun extensionFor(fileName: String?, mimeType: String?, mediaUrl: String?): String {
            fileName?.substringAfterLast('.', "")?.let(::clean)?.let { return it }
            mimeType?.let { MimeTypeMap.getSingleton().getExtensionFromMimeType(it) }?.let(::clean)?.let { return it }
            mediaUrl?.substringBefore('?')?.substringBefore('#')?.substringAfterLast('/')
                ?.let(Uri::decode)?.substringAfterLast('/')?.substringAfterLast('.', "")
                ?.let(::clean)?.let { return it }
            return DEFAULT_EXTENSION
        }

        /** The mime type [extension] maps to, if any. */
        fun mimeTypeOf(extension: String): String? =
            MimeTypeMap.getSingleton().getMimeTypeFromExtension(extension.lowercase())

        private fun clean(extension: String): String? =
            extension.lowercase().takeIf { ext -> ext.length in 1..MAX_EXTENSION && ext.all { it.isLetterOrDigit() } }
    }
}
