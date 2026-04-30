package com.firestream.chat.data.util

import android.content.Context
import com.firestream.chat.di.DownloadClient
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
import java.io.FileInputStream
import java.io.FileOutputStream
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
 * **Resumable.** If a partial file already exists for `update`, the next
 * call sends `Range: bytes=N-` and appends. The SHA-256 is seeded by
 * re-hashing the existing prefix. On `IOException` the partial file is
 * **kept** so the next attempt can pick up where it left off.
 *
 * The download directory is the cache dir so Android can reclaim space if
 * needed; on success the caller hands the file to the system installer
 * before it can be evicted.
 */
@Singleton
class ApkDownloader @Inject constructor(
    @ApplicationContext private val context: Context,
    @DownloadClient private val okHttpClient: OkHttpClient
) {

    fun download(update: AppUpdate): Flow<DownloadProgress> = flow {
        val dir = File(context.cacheDir, APK_DIR).apply { mkdirs() }
        val target = File(dir, "firestream-v${update.versionName}-${update.versionCode}.apk")

        // Wipe stale APKs (other versions) but preserve the file we may resume into.
        dir.listFiles()?.forEach { f ->
            if (f != target) f.delete()
        }

        var resumeFrom = if (target.exists()) target.length() else 0L

        val requestBuilder = Request.Builder().url(update.apkUrl)
        if (resumeFrom > 0) {
            requestBuilder.header("Range", "bytes=$resumeFrom-")
        }

        try {
            okHttpClient.newCall(requestBuilder.build()).execute().use { response ->
                // 416 — server says we already have the whole file (or our partial is too long).
                // Wipe and tell the caller to retry; safer than guessing.
                if (response.code == 416) {
                    target.delete()
                    emit(DownloadProgress.Failed("Partial file invalid — please retry"))
                    return@flow
                }
                if (!response.isSuccessful) {
                    emit(DownloadProgress.Failed("Download failed: HTTP ${response.code}"))
                    return@flow
                }

                // Server returned 200 OK despite our Range header — start over from byte 0.
                if (resumeFrom > 0 && response.code == 200) {
                    target.delete()
                    resumeFrom = 0L
                }

                val body = response.body
                    ?: run {
                        emit(DownloadProgress.Failed("Empty download response"))
                        return@flow
                    }

                val remaining = body.contentLength().takeIf { it > 0 } ?: -1L
                val total = if (remaining > 0) resumeFrom + remaining else -1L

                val digest = MessageDigest.getInstance("SHA-256")

                // Seed the digest by re-hashing the bytes already on disk.
                if (resumeFrom > 0) {
                    FileInputStream(target).use { existing ->
                        val seedBuf = ByteArray(BUFFER_SIZE)
                        while (true) {
                            val n = existing.read(seedBuf)
                            if (n == -1) break
                            digest.update(seedBuf, 0, n)
                        }
                    }
                }

                var downloaded = resumeFrom
                var lastEmitted = downloaded
                if (resumeFrom > 0) {
                    emit(DownloadProgress.InProgress(downloaded, total))
                }

                val buffer = ByteArray(BUFFER_SIZE)
                body.byteStream().use { input ->
                    FileOutputStream(target, /* append = */ resumeFrom > 0).use { output ->
                        while (true) {
                            val read = input.read(buffer)
                            if (read == -1) break
                            output.write(buffer, 0, read)
                            digest.update(buffer, 0, read)
                            downloaded += read
                            if (downloaded - lastEmitted >= EMIT_INTERVAL_BYTES) {
                                emit(DownloadProgress.InProgress(downloaded, total))
                                lastEmitted = downloaded
                            }
                        }
                    }
                }

                val actualSha = digest.digest().toHexString()
                if (!actualSha.equals(update.sha256, ignoreCase = true)) {
                    // Almost certainly a stale partial from a different APK at this path.
                    // Wipe so the next attempt starts clean.
                    target.delete()
                    emit(DownloadProgress.Failed("Checksum mismatch — refusing to install"))
                    return@flow
                }
                emit(DownloadProgress.Done(target))
            }
        } catch (e: IOException) {
            // Keep the partial file — the next attempt will resume from where we stopped.
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
