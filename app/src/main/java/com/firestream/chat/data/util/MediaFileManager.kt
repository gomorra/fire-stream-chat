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

    private val mediaRoot: File by lazy {
        // Android/media/com.firestream.chat/ — app-specific external storage,
        // visible in file managers, no permissions needed on API 29+
        (context.externalMediaDirs.firstOrNull() ?: File(context.filesDir, "media")).also { it.mkdirs() }
    }

    fun getLocalFile(chatId: String, messageId: String, extension: String): File {
        val dir = File(mediaRoot, chatId)
        dir.mkdirs()
        return File(dir, "$messageId.${normalizeExtension(extension)}")
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
                    response.body?.byteStream()?.use { input ->
                        localFile.outputStream().use { output ->
                            input.copyTo(output)
                        }
                    } ?: throw Exception("Empty response body")
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
            val relativePath = if (mimeType.startsWith("image/")) {
                "${Environment.DIRECTORY_PICTURES}/FireStream"
            } else {
                "${Environment.DIRECTORY_DOWNLOADS}/FireStream"
            }

            val collection = if (mimeType.startsWith("image/")) {
                MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
            } else {
                MediaStore.Downloads.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
            }

            val values = ContentValues().apply {
                put(MediaStore.MediaColumns.DISPLAY_NAME, localFile.name)
                put(MediaStore.MediaColumns.MIME_TYPE, mimeType)
                put(MediaStore.MediaColumns.RELATIVE_PATH, relativePath)
            }

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
            context.contentResolver.openInputStream(sourceUri)?.use { input ->
                localFile.outputStream().use { output ->
                    input.copyTo(output)
                }
            } ?: throw Exception("Failed to open source URI")
            localFile
        }

    private fun extractExtension(url: String): String {
        // Strip query params, then get extension
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
}
