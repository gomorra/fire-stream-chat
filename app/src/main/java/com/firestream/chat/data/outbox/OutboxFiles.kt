// region: AGENT-NOTE
// Responsibility: The durable copy of a send's input — a picked content:// URI,
//   a camera or editor file in cacheDir — under filesDir/outbox/<messageId>.<ext>,
//   so OutboxWorker can read it after the process, and with it the picker's
//   grant, are gone. Also the rule for which localUri a SENT row may keep.
// Owns: the outbox directory; the durable-or-not test for a row's localUri; the
//   mime type a staged document uploads under (carried in its extension).
// Collaborators: MessageRepositoryImpl (stages before every enqueue, deletes on
//   a queued delete), OutboxSender (mime type of a staged document, the localUri
//   kept at SENT, the delete after SENT), OutboxScheduler (sweeps files no
//   queued row owns).
// Don't put here: the media dir of sent and received photos (MediaFileManager),
//   compression or transcoding (OutboxSender's steps), the row itself. Cites
//   "Sends are idempotent by client id and drained by OutboxWorker"
//   (docs/PATTERNS.md).
// endregion

package com.firestream.chat.data.outbox

import android.content.Context
import android.net.Uri
import android.webkit.MimeTypeMap
import com.firestream.chat.data.util.MediaFileManager
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileNotFoundException
import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Stages what a queued send reads, and says which files the app keeps.
 *
 * A photo-picker grant does not outlive the process and `cacheDir` can be
 * purged, so an input that is not already a file the app keeps is copied here
 * before its row is enqueued, and the row points at the copy. The copy goes
 * when the send reaches SENT or the queued message is deleted.
 */
@Singleton
class OutboxFiles @Inject constructor(
    @ApplicationContext private val context: Context,
    private val mediaFileManager: MediaFileManager,
) {

    private val dir: File
        get() = File(context.filesDir, DIR_NAME)

    /**
     * Copies the input at [localUri] into the outbox directory unless it is
     * already a file the app keeps ([isDurable]) or already a copy. Returns the
     * path the row should point at from now on, or `null` when it may stay.
     *
     * [mimeType] names the extension the copy gets: a document uploads under the
     * type its extension maps back to ([mimeTypeOf]), so the picked type survives
     * a retry without a column of its own. `null` asks the content resolver.
     * Throws [FileNotFoundException] when the input cannot be opened — a revoked
     * grant, a purged cache file — which fails the send rather than queue it.
     */
    suspend fun stage(messageId: String, localUri: String, mimeType: String?): String? = withContext(Dispatchers.IO) {
        if (isDurable(localUri) || isStaged(localUri)) return@withContext null
        val source = toUri(localUri)
        val type = mimeType ?: runCatching { context.contentResolver.getType(source) }.getOrNull()
        val target = File(dir.apply { mkdirs() }, "$messageId.${extensionFor(type)}")
        val partial = File(dir, "$messageId.$PARTIAL_EXTENSION")
        val input = context.contentResolver.openInputStream(source)
            ?: throw FileNotFoundException("Cannot open the input of message $messageId: $localUri")
        try {
            input.use { i -> partial.outputStream().use { o -> i.copyTo(o) } }
            if (!partial.renameTo(target)) throw IOException("Cannot stage the input of message $messageId")
        } finally {
            partial.delete()
        }
        target.absolutePath
    }

    /**
     * Whether [localUri] names a file the app keeps for good — one a SENT row may
     * go on pointing at: the media dir copy an image or video was encoded into,
     * or any other file under `filesDir` that is not a staged input. A picked
     * URI, a cache file and a staged copy are not.
     */
    fun isDurable(localUri: String): Boolean {
        val file = asBareFile(localUri) ?: return false
        if (file.parentFile == dir) return false
        return file.startsWith(context.filesDir) || mediaFileManager.isManagedFile(file)
    }

    /** Whether [localUri] is a copy this class made. */
    fun isStaged(localUri: String): Boolean = asBareFile(localUri)?.parentFile == dir

    /** The mime type a staged file uploads under, from its extension; `null` when the extension maps to none. */
    fun mimeTypeOf(localUri: String): String? {
        val extension = asBareFile(localUri)?.extension?.lowercase()?.takeIf { it.isNotEmpty() } ?: return null
        return MimeTypeMap.getSingleton().getMimeTypeFromExtension(extension)
    }

    /** Removes the staged input of [messageId], if any: the send finished, or the message was deleted. */
    suspend fun delete(messageId: String) = withContext(Dispatchers.IO) {
        dir.listFiles { file -> file.nameWithoutExtension == messageId }?.forEach { it.delete() }
        Unit
    }

    /**
     * Removes every staged input whose message is not in [queuedIds]: a send that
     * reached SENT but died before its cleanup, or a message removed with its chat.
     */
    suspend fun retainOnly(queuedIds: Set<String>) = withContext(Dispatchers.IO) {
        dir.listFiles()?.filter { it.nameWithoutExtension !in queuedIds }?.forEach { it.delete() }
        Unit
    }

    /** A bare absolute path as a [File]; `null` for anything with a scheme. */
    private fun asBareFile(localUri: String): File? = if (localUri.startsWith("/")) File(localUri) else null

    private fun toUri(localUri: String): Uri = asBareFile(localUri)?.let(Uri::fromFile) ?: Uri.parse(localUri)

    private fun extensionFor(mimeType: String?): String {
        if (mimeType == null) return DEFAULT_EXTENSION
        MimeTypeMap.getSingleton().getExtensionFromMimeType(mimeType)?.let { return it }
        return mimeType.substringAfter('/').substringBefore('+')
            .filter { it.isLetterOrDigit() }.take(MAX_GUESSED_EXTENSION).lowercase()
            .ifEmpty { DEFAULT_EXTENSION }
    }

    private companion object {
        const val DIR_NAME = "outbox"
        const val DEFAULT_EXTENSION = "bin"
        const val PARTIAL_EXTENSION = "part"
        const val MAX_GUESSED_EXTENSION = 8
    }
}
