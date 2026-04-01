package com.firestream.chat.data.util

import android.content.Context
import android.net.Uri
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
class ProfileImageManager @Inject constructor(
    @ApplicationContext private val context: Context,
    private val httpClient: OkHttpClient
) {

    private val inFlightDownloads = ConcurrentHashMap<String, CompletableDeferred<File>>()

    private val profileDir: File by lazy {
        val dir = File(
            context.externalMediaDirs?.firstOrNull() ?: context.filesDir,
            PROFILE_FOLDER
        )
        dir.mkdirs()
        dir
    }

    fun getLocalFile(id: String): File = File(profileDir, "$id.jpg")

    fun fileExists(id: String): Boolean = getLocalFile(id).exists()

    suspend fun downloadAvatar(id: String, url: String): File =
        withContext(Dispatchers.IO) {
            val localFile = getLocalFile(id)

            val myDeferred = CompletableDeferred<File>()
            val existing = inFlightDownloads.putIfAbsent(id, myDeferred)
            if (existing != null) return@withContext existing.await()

            try {
                val request = Request.Builder().url(url).build()
                httpClient.newCall(request).execute().use { response ->
                    if (!response.isSuccessful) throw Exception("Download failed: ${response.code}")
                    val body = response.body?.byteStream()
                        ?: throw Exception("Empty response body")
                    localFile.outputStream().use { output ->
                        body.copyTo(output)
                    }
                }
                myDeferred.complete(localFile)
                localFile
            } catch (e: Exception) {
                // Delete any partial write so the file doesn't look valid on the next attempt.
                localFile.delete()
                myDeferred.completeExceptionally(e)
                throw e
            } finally {
                inFlightDownloads.remove(id, myDeferred)
            }
        }

    suspend fun saveLocalCopy(id: String, sourceUri: Uri): File =
        withContext(Dispatchers.IO) {
            val localFile = getLocalFile(id)
            val inputStream = context.contentResolver.openInputStream(sourceUri)
                ?: throw Exception("Failed to open source URI")
            inputStream.use { input ->
                localFile.outputStream().use { output ->
                    input.copyTo(output)
                }
            }
            localFile
        }

    fun deleteAvatar(id: String) {
        getLocalFile(id).delete()
    }

    companion object {
        private const val PROFILE_FOLDER = "profile_pictures"
    }
}
