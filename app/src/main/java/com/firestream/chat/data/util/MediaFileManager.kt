package com.firestream.chat.data.util

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Environment
import android.provider.MediaStore
import com.firestream.chat.data.sticker.StickerDownloads
import com.firestream.chat.domain.model.MessageType
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class MediaFileManager @Inject constructor(
    @ApplicationContext private val context: Context,
    private val httpClient: OkHttpClient,
    private val documentFiles: DocumentFiles,
    private val stickerDownloads: StickerDownloads,
) {

    private val inFlightDownloads = ConcurrentHashMap<String, CompletableDeferred<File>>()

    @Suppress("DEPRECATION")
    private val mediaRoot: File by lazy {
        File(
            Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PICTURES),
            MEDIA_FOLDER
        )
    }

    fun getLocalFile(chatId: String, messageId: String, extension: String): File {
        return File(mediaRoot, "$messageId.${normalizeExtension(extension)}")
    }

    fun fileExists(chatId: String, messageId: String, extension: String): Boolean {
        return getLocalFile(chatId, messageId, extension).exists()
    }

    /** Whether [file] is one of the copies this manager keeps — the durable local file of a sent or received message. */
    fun isManagedFile(file: File): Boolean = file.parentFile == mediaRoot

    suspend fun downloadAndSave(chatId: String, messageId: String, mediaUrl: String): File =
        withContext(Dispatchers.IO) {
            val extension = extractExtension(mediaUrl)
            val localFile = getLocalFile(chatId, messageId, extension)
            if (localFile.exists()) return@withContext localFile
            deduplicated(messageId) {
                fetch(mediaUrl) { input -> writeViaMediaStore(localFile.name, mimeFromExtension(extension), input) }
                localFile
            }
        }

    /**
     * Downloads a message's media to where its [type] keeps it. The one router,
     * so the auto-download, the chat-open scan and the backfill agree:
     *
     * - A DOCUMENT and a GIF go into the app's documents directory
     *   ([DocumentFiles]) with plain file IO. The MediaStore image collection
     *   refuses a non-image type, and a GIF must not reach the gallery.
     * - A STICKER goes into the sticker directory, checked against [stickerId]
     *   ([StickerDownloads]).
     * - Anything else goes through [downloadAndSave].
     *
     * Returns `null` for a sticker that is refused: the message keeps rendering
     * from its url and gets no local file. A failed download throws.
     */
    suspend fun downloadFor(
        chatId: String,
        messageId: String,
        type: MessageType,
        mediaUrl: String,
        fileName: String?,
        mimeType: String?,
        stickerId: String?,
    ): File? = when (type) {
        MessageType.STICKER -> stickerDownloads.ensureLocal(stickerId, mediaUrl)
        MessageType.DOCUMENT, MessageType.GIF ->
            downloadToFile(messageId, mediaUrl, documentFiles.fileFor(messageId, fileName, mimeType, mediaUrl))
        else -> downloadAndSave(chatId, messageId, mediaUrl)
    }

    /** Downloads [mediaUrl] into [target] through a `.part` sibling, so a cut-off download never looks finished. */
    private suspend fun downloadToFile(messageId: String, mediaUrl: String, target: File): File =
        withContext(Dispatchers.IO) {
            if (target.exists()) return@withContext target
            deduplicated(messageId) {
                val partial = File(target.parentFile, "${target.name}.part")
                try {
                    fetch(mediaUrl) { input -> partial.outputStream().use { input.copyTo(it) } }
                    if (!partial.renameTo(target)) throw IOException("Cannot finish download of $messageId")
                } finally {
                    partial.delete()
                }
                target
            }
        }

    /**
     * Runs [download] for [messageId] unless one is already in flight, in which
     * case its result is awaited instead — the auto-download, the chat-open scan
     * and the backfill can all reach the same message at once.
     */
    private suspend fun deduplicated(messageId: String, download: suspend () -> File): File {
        val myDeferred = CompletableDeferred<File>()
        val existing = inFlightDownloads.putIfAbsent(messageId, myDeferred)
        if (existing != null) return existing.await()
        return try {
            download().also(myDeferred::complete)
        } catch (e: Exception) {
            myDeferred.completeExceptionally(e)
            throw e
        } finally {
            inFlightDownloads.remove(messageId, myDeferred)
        }
    }

    /** GETs [mediaUrl] and hands the body to [write]; a non-2xx answer throws. */
    private fun fetch(mediaUrl: String, write: (InputStream) -> Unit) {
        val request = Request.Builder().url(mediaUrl).build()
        httpClient.newCall(request).execute().use { response ->
            if (!response.isSuccessful) throw Exception("Download failed: ${response.code}")
            val body = response.body ?: throw Exception("Empty response body")
            body.byteStream().use(write)
        }
    }

    suspend fun saveToDownloads(localFile: File, mimeType: String): Uri =
        withContext(Dispatchers.IO) {
            writeToDownloads(localFile.name, mimeType) { output ->
                localFile.inputStream().use { it.copyTo(output) }
            }
        }

    /**
     * Save an arbitrary readable [sourceUri] to Downloads under [displayName].
     *
     * The [File] overload above only reaches media we already own a local copy
     * of. A picked-but-unsent image is still a `content://` URI belonging to
     * the gallery provider, so downloading one from the send preview streams
     * straight from the resolver rather than staging a temp file first.
     */
    suspend fun saveToDownloads(sourceUri: Uri, mimeType: String, displayName: String): Uri =
        withContext(Dispatchers.IO) {
            writeToDownloads(displayName, mimeType) { output ->
                context.contentResolver.openInputStream(sourceUri)?.use { it.copyTo(output) }
                    ?: throw Exception("Failed to open source URI")
            }
        }

    /**
     * Creates the pending Downloads entry, lets [writeContent] fill it, then
     * publishes it. A half-written entry is deleted rather than left visible in
     * the user's Downloads as a zero-byte file.
     */
    private fun writeToDownloads(
        displayName: String,
        mimeType: String,
        writeContent: (OutputStream) -> Unit,
    ): Uri {
        // Copy to the user-visible Downloads folder via MediaStore (API 29+).
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, displayName)
            put(MediaStore.MediaColumns.MIME_TYPE, mimeType)
            put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS)
            put(MediaStore.MediaColumns.IS_PENDING, 1)
        }

        val collection = MediaStore.Downloads.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
        val resolver = context.contentResolver
        val uri = resolver.insert(collection, values)
            ?: throw Exception("Failed to create MediaStore entry")

        try {
            resolver.openOutputStream(uri)?.use(writeContent)
                ?: throw Exception("Failed to open output stream")

            val done = ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) }
            resolver.update(uri, done, null, null)
        } catch (e: Exception) {
            resolver.delete(uri, null, null)
            throw e
        }

        return uri
    }

    suspend fun copyToLocal(chatId: String, messageId: String, sourceUri: Uri, extension: String): File =
        withContext(Dispatchers.IO) {
            val localFile = getLocalFile(chatId, messageId, extension)
            if (localFile.exists()) return@withContext localFile

            val inputStream = context.contentResolver.openInputStream(sourceUri)
                ?: throw Exception("Failed to open source URI")
            inputStream.use { input ->
                writeViaMediaStore(localFile.name, mimeFromExtension(extension), input)
            }
            localFile
        }

    /**
     * Write content to Pictures/FireStream/{chatId}/ via MediaStore.
     * The file is owned by our app (readable via File API) and indexed by Google Photos.
     */
    private fun writeViaMediaStore(
        displayName: String,
        mimeType: String,
        inputStream: java.io.InputStream
    ) {
        val relativePath = "${Environment.DIRECTORY_PICTURES}/$MEDIA_FOLDER"

        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, displayName)
            put(MediaStore.MediaColumns.MIME_TYPE, mimeType)
            put(MediaStore.MediaColumns.RELATIVE_PATH, relativePath)
            put(MediaStore.MediaColumns.IS_PENDING, 1)
        }

        val collection = if (mimeType.startsWith("video/")) {
            MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
        } else {
            MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
        }

        val resolver = context.contentResolver
        val uri = resolver.insert(collection, values)
            ?: throw Exception("Failed to create MediaStore entry")

        try {
            resolver.openOutputStream(uri)?.use { output ->
                inputStream.copyTo(output)
            } ?: throw Exception("Failed to open output stream")

            val done = ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) }
            resolver.update(uri, done, null, null)
        } catch (e: Exception) {
            resolver.delete(uri, null, null)
            throw e
        }
    }

    /**
     * Migrate files from old storage locations to Pictures/FireStream/.
     * Handles both filesDir/media/ (old internal) and Android/media/ (old external).
     */
    suspend fun migrateOldStorage(): Int = withContext(Dispatchers.IO) {
        var moved = 0
        // Old location 1: filesDir/media/
        moved += migrateDirectory(File(context.filesDir, "media"))
        // Old location 2: Android/media/com.firestream.chat/
        val oldExternal = context.externalMediaDirs?.firstOrNull()
        if (oldExternal != null) moved += migrateDirectory(oldExternal)
        moved
    }

    private suspend fun migrateDirectory(root: File): Int {
        if (!root.exists()) return 0

        var moved = 0
        val chatDirs = root.listFiles()?.filter { it.isDirectory } ?: return 0

        for (chatDir in chatDirs) {
            val chatId = chatDir.name
            val files = chatDir.listFiles() ?: continue
            for (oldFile in files) {
                val newFile = getLocalFile(chatId, oldFile.nameWithoutExtension, oldFile.extension)
                if (!newFile.exists()) {
                    oldFile.inputStream().use { input ->
                        writeViaMediaStore(newFile.name, mimeFromExtension(oldFile.extension), input)
                    }
                }
                oldFile.delete()
                moved++
            }
        }

        // Clean up
        chatDirs.forEach { it.deleteRecursively() }
        if (root.listFiles()?.isEmpty() == true) root.delete()

        return moved
    }

    private fun extractExtension(url: String): String {
        val path = url.substringBefore("?").substringBefore("#")
        val ext = path.substringAfterLast(".", "")
        return normalizeExtension(if (ext.length in 1..5) ext else "jpg")
    }

    private fun normalizeExtension(ext: String): String = when (val lower = ext.lowercase()) {
        "jpeg" -> "jpg"
        "tiff" -> "tif"
        "mpeg" -> "mpg"
        else -> lower
    }

    companion object {
        private const val MEDIA_FOLDER = "FireStream Images"
    }

    private fun mimeFromExtension(ext: String): String = when (normalizeExtension(ext)) {
        "jpg" -> "image/jpeg"
        "png" -> "image/png"
        "gif" -> "image/gif"
        "webp" -> "image/webp"
        "mp4" -> "video/mp4"
        else -> "application/octet-stream"
    }
}
