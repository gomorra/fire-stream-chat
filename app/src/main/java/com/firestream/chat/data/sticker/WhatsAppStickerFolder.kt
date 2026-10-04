package com.firestream.chat.data.sticker

import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import android.provider.DocumentsContract.Document
import com.firestream.chat.domain.model.WhatsAppStickerFile
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Lists the folder WhatsApp keeps its sticker files in, through the tree grant
 * the user gave for it.
 *
 * One query for the folder's children. `DocumentFile.listFiles` asks the provider
 * again for every file's name, size and date, which takes seconds for a folder
 * of a few thousand stickers.
 */
@Singleton
class WhatsAppStickerFolder @Inject constructor(
    @ApplicationContext private val context: Context,
) {

    /**
     * The sticker files directly inside [treeUri], newest first. Throws
     * [SecurityException] when the grant is gone.
     */
    suspend fun list(treeUri: String): List<WhatsAppStickerFile> = withContext(Dispatchers.IO) {
        val tree = Uri.parse(treeUri)
        val children = DocumentsContract.buildChildDocumentsUriUsingTree(tree, DocumentsContract.getTreeDocumentId(tree))
        val files = mutableListOf<WhatsAppStickerFile>()
        context.contentResolver.query(children, PROJECTION, null, null, null)?.use { cursor ->
            while (cursor.moveToNext()) {
                if (cursor.getString(2) == Document.MIME_TYPE_DIR) continue
                val documentId = cursor.getString(0) ?: continue
                files += WhatsAppStickerFile(
                    uri = DocumentsContract.buildDocumentUriUsingTree(tree, documentId).toString(),
                    name = cursor.getString(1).orEmpty(),
                    sizeBytes = cursor.getLong(3),
                    lastModified = cursor.getLong(4),
                )
            }
        }
        stickersNewestFirst(files)
    }

    companion object {
        private val PROJECTION = arrayOf(
            Document.COLUMN_DOCUMENT_ID,
            Document.COLUMN_DISPLAY_NAME,
            Document.COLUMN_MIME_TYPE,
            Document.COLUMN_SIZE,
            Document.COLUMN_LAST_MODIFIED,
        )
        private val STICKER_SUFFIXES = listOf(WhatsAppStickerFile.WEBP_SUFFIX, WhatsAppStickerFile.LOTTIE_SUFFIX)

        /**
         * The entries of [files] that can be stickers, newest first: a sticker's
         * name and a size the library accepts. That leaves out `.nomedia` and
         * anything [StickerFiles] would refuse unread.
         */
        internal fun stickersNewestFirst(files: List<WhatsAppStickerFile>): List<WhatsAppStickerFile> =
            files
                .filter { file ->
                    STICKER_SUFFIXES.any { file.name.endsWith(it, ignoreCase = true) } && file.sizeBytes in 1..StickerFiles.MAX_BYTES
                }
                .sortedByDescending { it.lastModified }
    }
}
