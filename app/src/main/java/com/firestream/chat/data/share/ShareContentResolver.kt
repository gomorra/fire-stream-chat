package com.firestream.chat.data.share

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.util.Log
import android.webkit.MimeTypeMap
import com.firestream.chat.domain.model.SharedContent
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

private const val TAG = "ShareContentResolver"

@Singleton
class ShareContentResolver @Inject constructor(
    @ApplicationContext private val context: Context
) {

    /**
     * Resolve a SEND / SEND_MULTIPLE intent into a [SharedContent].
     *
     * Returns `null` only when the intent genuinely carries nothing to share
     * (no text, no stream, unknown action). Any actual I/O or permission
     * failure is propagated so the caller can surface it to the user instead
     * of showing an empty preview.
     */
    @Throws(IOException::class, SecurityException::class)
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
        val item = copyToCache(uri, type)
        return SharedContent.Media(listOf(item))
    }

    private fun resolveMultiple(intent: Intent): SharedContent? {
        @Suppress("DEPRECATION")
        val uris = intent.getParcelableArrayListExtra<Uri>(Intent.EXTRA_STREAM)
        if (uris.isNullOrEmpty()) return null

        val type = intent.type ?: "*/*"
        val items = uris.map { uri -> copyToCache(uri, type) }
        if (items.isEmpty()) return null
        return SharedContent.Media(items)
    }

    /**
     * Copy a content URI into our private cache so it survives the caller's
     * process death. Throws on I/O / permission errors so the caller can
     * report them instead of silently showing an empty preview.
     */
    @Throws(IOException::class, SecurityException::class)
    private fun copyToCache(sourceUri: Uri, fallbackMimeType: String): SharedContent.Media.MediaItem {
        val resolver = context.contentResolver
        val mimeType = resolver.getType(sourceUri) ?: fallbackMimeType
        val extension = MimeTypeMap.getSingleton()
            .getExtensionFromMimeType(mimeType) ?: "bin"
        val fileName = queryFileName(sourceUri) ?: "shared_${UUID.randomUUID()}.$extension"

        val cacheDir = File(context.cacheDir, "shared_media").apply { mkdirs() }
        val destFile = File(cacheDir, "${UUID.randomUUID()}.$extension")

        val input = resolver.openInputStream(sourceUri)
            ?: throw IOException("Couldn't open shared URI: $sourceUri")
        input.use { stream ->
            destFile.outputStream().buffered().use { output ->
                stream.copyTo(output)
            }
        }

        return SharedContent.Media.MediaItem(
            cachedUri = Uri.fromFile(destFile).toString(),
            mimeType = mimeType,
            fileName = fileName
        )
    }

    private fun queryFileName(uri: Uri): String? {
        return try {
            context.contentResolver.query(uri, arrayOf("_display_name"), null, null, null)?.use { cursor ->
                if (cursor.moveToFirst()) cursor.getString(0) else null
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to query display name for $uri", e)
            null
        }
    }
}
