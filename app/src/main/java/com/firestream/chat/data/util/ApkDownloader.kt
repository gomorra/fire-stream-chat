package com.firestream.chat.data.util

import android.content.Context
import com.firestream.chat.domain.model.AppUpdate
import com.firestream.chat.domain.repository.DownloadProgress
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.IOException
import java.security.MessageDigest
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Streaming download of an APK to `cacheDir/apk_updates/`, with SHA-256
 * verified against the manifest. Emits [DownloadProgress.InProgress] roughly
 * every 64 KiB so the UI can render a progress bar; the final emission is
 * always either [DownloadProgress.Done] or [DownloadProgress.Failed].
 *
 * The download directory is the cache dir so Android can reclaim space if
 * needed; on success the caller hands the file to the system installer
 * before it can be evicted.
 */
@Singleton
class ApkDownloader @Inject constructor(
    @ApplicationContext private val context: Context,
    private val okHttpClient: OkHttpClient
) {

    fun download(update: AppUpdate): Flow<DownloadProgress> = flow {
        val dir = File(context.cacheDir, APK_DIR).apply { mkdirs() }
        // Wipe stale APKs before each fresh download — keeps the cache bounded
        // even if the user kicks off multiple checks.
        dir.listFiles()?.forEach { it.delete() }

        val target = File(dir, "firestream-v${update.versionName}-${update.versionCode}.apk")
        val request = Request.Builder().url(update.apkUrl).build()

        try {
            okHttpClient.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    emit(DownloadProgress.Failed("Download failed: HTTP ${response.code}"))
                    return@flow
                }
                val body = response.body
                    ?: run {
                        emit(DownloadProgress.Failed("Empty download response"))
                        return@flow
                    }

                val total = body.contentLength().takeIf { it > 0 } ?: -1L
                val digest = MessageDigest.getInstance("SHA-256")
                val source = body.byteStream()
                val sink = target.outputStream()
                var downloaded = 0L
                val buffer = ByteArray(BUFFER_SIZE)
                var lastEmitted = 0L

                source.use { input ->
                    sink.use { output ->
                        while (true) {
                            val read = input.read(buffer)
                            if (read == -1) break
                            output.write(buffer, 0, read)
                            digest.update(buffer, 0, read)
                            downloaded += read
                            // Throttle progress emissions so we don't flood the UI.
                            if (downloaded - lastEmitted >= EMIT_INTERVAL_BYTES) {
                                emit(DownloadProgress.InProgress(downloaded, total))
                                lastEmitted = downloaded
                            }
                        }
                    }
                }

                val actualSha = digest.digest().toHexString()
                if (!actualSha.equals(update.sha256, ignoreCase = true)) {
                    target.delete()
                    emit(DownloadProgress.Failed("Checksum mismatch — refusing to install"))
                    return@flow
                }
                emit(DownloadProgress.Done(target))
            }
        } catch (e: IOException) {
            target.delete()
            emit(DownloadProgress.Failed(e.message ?: "Network error"))
        }
    }.flowOn(Dispatchers.IO)

    private fun ByteArray.toHexString(): String =
        joinToString("") { "%02x".format(it) }

    companion object {
        private const val APK_DIR = "apk_updates"
        private const val BUFFER_SIZE = 8 * 1024
        private const val EMIT_INTERVAL_BYTES = 64L * 1024
    }
}
