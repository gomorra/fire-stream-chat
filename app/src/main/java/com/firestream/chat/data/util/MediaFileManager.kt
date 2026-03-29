package com.firestream.chat.data.util

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Environment
import android.provider.MediaStore
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class MediaFileManager @Inject constructor(
    @ApplicationContext private val context: Context,
    private val httpClient: OkHttpClient
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

    suspend fun downloadAndSave(chatId: String, messageId: String, mediaUrl: String): File =
        withContext(Dispatchers.IO) {
            val extension = extractExtension(mediaUrl)
            val localFile = getLocalFile(chatId, messageId, extension)
            if (localFile.exists()) return@withContext localFile

            val myDeferred = CompletableDeferred<File>()
            val existing = inFlightDownloads.putIfAbsent(messageId, myDeferred)
            if (existing != null) return@withContext existing.await()

            try {
                val request = Request.Builder().url(mediaUrl).build()
                httpClient.newCall(request).execute().use { response ->
                    if (!response.isSuccessful) throw Exception("Download failed: ${response.code}")
                    val inputStream = response.body?.byteStream()
                        ?: throw Exception("Empty response body")
                    writeViaMediaStore(localFile.name, mimeFromExtension(extension), inputStream)
                }
                myDeferred.complete(localFile)
                localFile
            } catch (e: Exception) {
                myDeferred.completeExceptionally(e)
                throw e
            } finally {
                inFlightDownloads.remove(messageId, myDeferred)
            }
        }

    suspend fun saveToGallery(localFile: File, mimeType: String): Uri =
        withContext(Dispatchers.IO) {
            // Copy to Pictures/ root (not FireStream subfolder) for explicit user save
            val values = ContentValues().apply {
                put(MediaStore.MediaColumns.DISPLAY_NAME, localFile.name)
                put(MediaStore.MediaColumns.MIME_TYPE, mimeType)
                put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_PICTURES)
            }

            val collection = MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
            val resolver = context.contentResolver
            val uri = resolver.insert(collection, values)
                ?: throw Exception("Failed to create MediaStore entry")

            resolver.openOutputStream(uri)?.use { output ->
                localFile.inputStream().use { input ->
                    input.copyTo(output)
                }
            } ?: throw Exception("Failed to open output stream")

            uri
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
