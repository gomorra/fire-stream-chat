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

    @Volatile
    private var cacheDir: File? = null

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
        val uris = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            intent.getParcelableArrayListExtra(Intent.EXTRA_STREAM, Uri::class.java)
        } else {
            @Suppress("DEPRECATION")
            intent.getParcelableArrayListExtra<Uri>(Intent.EXTRA_STREAM)
        }
        if (uris.isNullOrEmpty()) return null

        val type = intent.type ?: "*/*"
        // Copy each URI independently: one revoked/unreadable item in a
        // multi-select must not sink the whole share. Sharing 10 photos where
        // the 4th has gone stale should still offer the other 9 rather than an
        // error screen. Only a total failure is propagated, so the caller can
        // still tell "nothing was readable" from "nothing was attached".
        var firstFailure: Throwable? = null
        val items = uris.mapNotNull { uri ->
            runCatching { copyToCache(uri, type) }
                .onFailure { e ->
                    Log.w(TAG, "Skipping unreadable shared URI: $uri", e)
                    if (firstFailure == null) firstFailure = e
                }
                .getOrNull()
        }
        if (items.isEmpty()) {
            firstFailure?.let { throw it }
            return null
        }
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
        val displayName = queryFileName(sourceUri)
        val nameExtension = displayName?.substringAfterLast('.', "")
            ?.takeIf { it.isNotEmpty() }
            ?.lowercase()
        // A SEND_MULTIPLE intent's own type is routinely a wildcard ("image/*",
        // "*/*") because it has to cover every item. That is useless as an
        // upload mime type and yields no extension, so only fall back to it once
        // the provider's own type and the file name have both come up empty.
        val mimeType = resolver.getType(sourceUri)?.takeUnless { it.contains('*') }
            ?: nameExtension?.let { MimeTypeMap.getSingleton().getMimeTypeFromExtension(it) }
            ?: concreteMimeType(fallbackMimeType)
        val extension = MimeTypeMap.getSingleton().getExtensionFromMimeType(mimeType)
            ?: nameExtension
            ?: "bin"
        val fileName = displayName ?: "shared_${UUID.randomUUID()}.$extension"

        val destFile = File(sharedMediaCacheDir(), "${UUID.randomUUID()}.$extension")

        val input = resolver.openInputStream(sourceUri)
            ?: throw IOException("Couldn't open shared URI: $sourceUri")
        // copyTo already reads through its own buffer; wrapping the sink in
        // another one would memcpy every byte a second time.
        input.use { stream ->
            destFile.outputStream().use { output -> stream.copyTo(output) }
        }

        return SharedContent.Media.MediaItem(
            cachedUri = Uri.fromFile(destFile).toString(),
            mimeType = mimeType,
            fileName = fileName
        )
    }

    /**
     * Last resort when neither the provider nor the file name yielded a type.
     * A wildcard must never reach a [SharedContent.Media.MediaItem]: it is used
     * verbatim as the upload's Content-Type and to classify the message, and a
     * wildcard is not a valid value for either. Collapse it to the most likely
     * concrete type for its family instead.
     */
    private fun concreteMimeType(mimeType: String): String = when {
        !mimeType.contains('*') -> mimeType
        mimeType.startsWith("image/") -> "image/jpeg"
        mimeType.startsWith("video/") -> "video/mp4"
        mimeType.startsWith("audio/") -> "audio/mpeg"
        mimeType.startsWith("text/") -> "text/plain"
        else -> "application/octet-stream"
    }

    /** Created once per process rather than per shared item. */
    private fun sharedMediaCacheDir(): File =
        cacheDir ?: File(context.cacheDir, "shared_media").apply { mkdirs() }.also { cacheDir = it }

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
