package com.firestream.chat.data.share

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.webkit.MimeTypeMap
import com.firestream.chat.domain.model.SharedContent
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class ShareContentResolver @Inject constructor(
    @ApplicationContext private val context: Context
) {

    suspend fun resolve(intent: Intent): SharedContent? = withContext(Dispatchers.IO) {
        when (intent.action) {
            Intent.ACTION_SEND -> resolveSingle(intent)
            Intent.ACTION_SEND_MULTIPLE -> resolveMultiple(intent)
            else -> null
        }
    }

    private fun resolveSingle(intent: Intent): SharedContent? {
        val type = intent.type ?: return null

        if (type == "text/plain") {
            val text = intent.getStringExtra(Intent.EXTRA_TEXT)
            if (text != null) return SharedContent.Text(text)
        }

        val uri = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            intent.getParcelableExtra(Intent.EXTRA_STREAM, Uri::class.java)
        } else {
            @Suppress("DEPRECATION")
            intent.getParcelableExtra<Uri>(Intent.EXTRA_STREAM)
        } ?: return null
        val item = copyToCache(uri, type) ?: return null
        return SharedContent.Media(listOf(item))
    }

    private fun resolveMultiple(intent: Intent): SharedContent? {
        @Suppress("DEPRECATION")
        val uris = intent.getParcelableArrayListExtra<Uri>(Intent.EXTRA_STREAM)
        if (uris.isNullOrEmpty()) return null

        val type = intent.type ?: "*/*"
        val items = uris.mapNotNull { uri -> copyToCache(uri, type) }
        if (items.isEmpty()) return null
        return SharedContent.Media(items)
    }

    private fun copyToCache(sourceUri: Uri, fallbackMimeType: String): SharedContent.Media.MediaItem? {
        return try {
            val resolver = context.contentResolver
            val mimeType = resolver.getType(sourceUri) ?: fallbackMimeType
            val extension = MimeTypeMap.getSingleton()
                .getExtensionFromMimeType(mimeType) ?: "bin"
            val fileName = queryFileName(sourceUri) ?: "shared_${UUID.randomUUID()}.$extension"

            val cacheDir = File(context.cacheDir, "shared_media").apply { mkdirs() }
            val destFile = File(cacheDir, "${UUID.randomUUID()}.$extension")

            resolver.openInputStream(sourceUri)?.use { input ->
                destFile.outputStream().buffered().use { output ->
                    input.copyTo(output)
                }
            } ?: return null

            SharedContent.Media.MediaItem(
                cachedUri = Uri.fromFile(destFile).toString(),
                mimeType = mimeType,
                fileName = fileName
            )
        } catch (_: Exception) {
            null
        }
    }

    private fun queryFileName(uri: Uri): String? {
        return try {
            context.contentResolver.query(uri, arrayOf("_display_name"), null, null, null)?.use { cursor ->
                if (cursor.moveToFirst()) cursor.getString(0) else null
            }
        } catch (_: Exception) {
            null
        }
    }
}
