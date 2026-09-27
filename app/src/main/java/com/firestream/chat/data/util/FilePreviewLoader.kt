// region: AGENT-NOTE
// Responsibility: Builds the preview a DOCUMENT bubble shows from the file's
//   local copy — the first lines of a text file — off the main thread, and
//   keeps recent ones in memory so scrolling back does not read them again.
// Owns: the in-memory preview cache; how much of a file a preview may read.
// Collaborators: ChatViewModel (through FilePreviewSource, bound in AppModule), FileKind and
//   TextPreview (domain — which kinds preview, and the decoding rule).
// Don't put here: downloading (MediaFileManager / MessageRepository.ensureLocalFile)
//   — a preview is only ever built from a file already on the device; the
//   bubble itself (FileMessageBubble).
// endregion

package com.firestream.chat.data.util

import android.util.LruCache
import com.firestream.chat.domain.util.FileKind
import com.firestream.chat.domain.util.FilePreview
import com.firestream.chat.domain.util.FilePreviewSource
import com.firestream.chat.domain.util.TextPreview
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class FilePreviewLoader @Inject constructor() : FilePreviewSource {

    // Keyed by path and modification time: a re-downloaded or replaced copy is a new preview.
    private val cache = LruCache<String, FilePreview>(CACHE_ENTRIES)

    override fun cached(path: String): FilePreview? = cache.get(keyOf(File(path)))

    override suspend fun load(path: String, kind: FileKind): FilePreview = withContext(Dispatchers.IO) {
        val file = File(path)
        val key = keyOf(file)
        cache.get(key)?.let { return@withContext it }
        val preview = runCatching { build(file, kind) }.getOrDefault(FilePreview.None)
        cache.put(key, preview)
        preview
    }

    private fun build(file: File, kind: FileKind): FilePreview {
        if (!file.isFile || !file.canRead()) return FilePreview.None
        return when {
            kind.hasTextPreview -> TextPreview.decode(readHead(file), file.length()) ?: FilePreview.None
            else -> FilePreview.None
        }
    }

    /** At most [TextPreview.MAX_BYTES] from the start of [file] — never the whole of a large file. */
    private fun readHead(file: File): ByteArray = file.inputStream().use { input ->
        val buffer = ByteArray(minOf(file.length(), TextPreview.MAX_BYTES.toLong()).toInt())
        var read = 0
        while (read < buffer.size) {
            val n = input.read(buffer, read, buffer.size - read)
            if (n < 0) break
            read += n
        }
        if (read == buffer.size) buffer else buffer.copyOf(read)
    }

    private fun keyOf(file: File) = "${file.path}|${file.lastModified()}"

    private companion object {
        const val CACHE_ENTRIES = 64
    }
}
