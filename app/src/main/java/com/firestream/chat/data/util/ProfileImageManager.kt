package com.firestream.chat.data.util

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ImageDecoder
import android.net.Uri
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.roundToInt

@Singleton
class ProfileImageManager @Inject constructor(
    @ApplicationContext private val context: Context,
    private val httpClient: OkHttpClient,
    private val processingLimiter: MediaProcessingLimiter,
) {

    private val inFlightDownloads = ConcurrentHashMap<String, CompletableDeferred<File>>()

    /**
     * The avatar cache, in internal storage.
     *
     * Not `Android/media`: that folder goes through the shared-storage FUSE layer, where a file
     * can pass `exists()` and `canRead()` and still fail to open. `mkdirs()` runs on each access
     * because a folder deleted from under the app would otherwise fail every later download.
     */
    private val profileDir: File
        get() = File(context.filesDir, PROFILE_FOLDER).also { it.mkdirs() }

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

    /**
     * Writes the avatar picked at [sourceUri] to [getLocalFile] as a JPEG whose long edge is
     * at most [AVATAR_MAX_EDGE], and returns that file. The caller uploads this file, not the
     * original.
     *
     * A camera original is several megabytes and every client decodes it at 40-96dp. That
     * wastes bandwidth, and a heavily subsampled `BitmapFactory` decode of such an image
     * returns a black bitmap.
     *
     * The file is written to a temporary sibling and then renamed. The avatar on screen keeps
     * reading the old file until the new one is complete.
     */
    suspend fun saveLocalCopy(id: String, sourceUri: Uri): File =
        processingLimiter.withPermit {
            withContext(Dispatchers.IO) {
                val localFile = getLocalFile(id)
                val tmpFile = File(localFile.parentFile, "${localFile.name}.tmp")
                try {
                    writeScaledJpeg(sourceUri, tmpFile)
                } catch (e: ImageDecoder.DecodeException) {
                    // Upload the original rather than fail. The viewer's scaled decode copes with it.
                    copyOriginal(sourceUri, tmpFile)
                }
                if (!tmpFile.renameTo(localFile)) {
                    tmpFile.delete()
                    throw IOException("Failed to move avatar into place")
                }
                localFile
            }
        }

    private fun writeScaledJpeg(sourceUri: Uri, target: File) {
        val source = ImageDecoder.createSource(context.contentResolver, sourceUri)
        val decoded = ImageDecoder.decodeBitmap(source) { decoder, info, _ ->
            decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
            val (width, height) = avatarTargetSize(info.size.width, info.size.height)
            decoder.setTargetSize(width, height)
        }
        // JPEG has no alpha channel, so a transparent pixel would turn black.
        val opaque = if (decoded.hasAlpha()) onWhite(decoded).also { decoded.recycle() } else decoded
        try {
            target.outputStream().use { opaque.compress(Bitmap.CompressFormat.JPEG, AVATAR_JPEG_QUALITY, it) }
        } finally {
            opaque.recycle()
        }
    }

    private fun onWhite(bitmap: Bitmap): Bitmap {
        val result = Bitmap.createBitmap(bitmap.width, bitmap.height, Bitmap.Config.ARGB_8888)
        Canvas(result).apply {
            drawColor(Color.WHITE)
            drawBitmap(bitmap, 0f, 0f, null)
        }
        return result
    }

    private fun copyOriginal(sourceUri: Uri, target: File) {
        val inputStream = context.contentResolver.openInputStream(sourceUri)
            ?: throw IOException("Failed to open source URI")
        inputStream.use { input ->
            target.outputStream().use { output -> input.copyTo(output) }
        }
    }

    fun deleteAvatar(id: String) {
        getLocalFile(id).delete()
    }

    /**
     * Deletes the avatar cache that older versions kept in `Android/media`. The repositories
     * download each avatar again into [profileDir], because [fileExists] only looks there.
     */
    @Suppress("DEPRECATION")
    fun deleteLegacyExternalCache() {
        context.externalMediaDirs?.firstOrNull()
            ?.let { File(it, PROFILE_FOLDER) }
            ?.deleteRecursively()
    }

    companion object {
        /** The avatar folder's name, in internal storage and in the old `Android/media` location. */
        const val PROFILE_FOLDER = "profile_pictures"

        /** Long-edge cap for an uploaded avatar. Avatars show at 96dp at most, and fullscreen. */
        internal const val AVATAR_MAX_EDGE = 1024
        private const val AVATAR_JPEG_QUALITY = 90
    }
}

/**
 * The size to decode an avatar of [srcWidth] x [srcHeight] to: the long edge is capped at
 * [ProfileImageManager.AVATAR_MAX_EDGE], the aspect ratio is kept, and a small image is never
 * upscaled.
 */
internal fun avatarTargetSize(srcWidth: Int, srcHeight: Int): Pair<Int, Int> {
    val longEdge = maxOf(srcWidth, srcHeight)
    if (longEdge <= ProfileImageManager.AVATAR_MAX_EDGE) return srcWidth to srcHeight
    val factor = ProfileImageManager.AVATAR_MAX_EDGE / longEdge.toDouble()
    return (srcWidth * factor).roundToInt().coerceAtLeast(1) to
        (srcHeight * factor).roundToInt().coerceAtLeast(1)
}
