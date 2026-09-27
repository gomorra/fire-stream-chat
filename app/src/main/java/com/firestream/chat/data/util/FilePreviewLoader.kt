// region: AGENT-NOTE
// Responsibility: Builds the preview a DOCUMENT bubble shows from the file's
//   local copy — the first lines of a text file; a PDF's first page, page count
//   and text excerpt — off the main thread, and keeps recent ones in memory so
//   scrolling back does not read them again.
// Owns: the in-memory preview cache; cacheDir/file_previews/ (PDF thumbnails);
//   how much of a file a preview may read.
// Collaborators: ChatViewModel (through FilePreviewSource, bound in AppModule), FileKind and
//   TextPreview (domain — which kinds preview, and the decoding rule), PdfText
//   (PdfBox text layer), MediaProcessingLimiter (PDF work takes a permit).
// Don't put here: downloading (MediaFileManager / MessageRepository.ensureLocalFile)
//   — a preview is only ever built from a file already on the device; the
//   bubble itself (FileMessageBubble).
// endregion

package com.firestream.chat.data.util

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.pdf.PdfRenderer
import android.os.ParcelFileDescriptor
import android.util.Log
import android.util.LruCache
import com.firestream.chat.domain.util.FileKind
import com.firestream.chat.domain.util.FilePreview
import com.firestream.chat.domain.util.FilePreviewSource
import com.firestream.chat.domain.util.TextPreview
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class FilePreviewLoader @Inject constructor(
    @ApplicationContext private val context: Context,
    private val limiter: MediaProcessingLimiter,
) : FilePreviewSource {

    private val thumbnailDir: File
        get() = File(context.cacheDir, THUMBNAIL_DIR)

    // Keyed by path and modification time: a re-downloaded or replaced copy is a new preview.
    private val cache = LruCache<String, FilePreview>(CACHE_ENTRIES)

    override fun cached(path: String): FilePreview? = cache.get(keyOf(File(path)))?.takeIf(::isIntact)

    override suspend fun load(path: String, kind: FileKind): FilePreview = withContext(Dispatchers.IO) {
        val file = File(path)
        val key = keyOf(file)
        cache.get(key)?.takeIf(::isIntact)?.let { return@withContext it }
        val preview = try {
            build(file, kind, key)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            FilePreview.None
        } catch (e: OutOfMemoryError) {
            // A pathological PDF page; the card without a preview is the answer.
            FilePreview.None
        }
        cache.put(key, preview)
        preview
    }

    private suspend fun build(file: File, kind: FileKind, key: String): FilePreview {
        if (!file.isFile || !file.canRead()) return FilePreview.None
        return when {
            kind.hasTextPreview -> TextPreview.decode(readHead(file), file.length()) ?: FilePreview.None
            kind == FileKind.PDF -> limiter.withPermit { buildPdf(file, key) }
            else -> FilePreview.None
        }
    }

    /** A PDF's first page and page count (platform renderer) and its text excerpt (PdfBox); either may be missing. */
    private fun buildPdf(file: File, key: String): FilePreview {
        val page = renderFirstPage(file, key)
        val text = try {
            PdfText.extract(context, file)
        } catch (e: Exception) {
            Log.w(TAG, "No text excerpt for ${file.name}", e)
            null
        }
        if (page == null && text == null) return FilePreview.None
        return FilePreview.Pdf(
            thumbnailPath = page?.path,
            thumbnailAspect = page?.aspect ?: A4_ASPECT,
            pageCount = page?.pageCount ?: 0,
            text = text,
        )
    }

    private class RenderedPage(val path: String, val aspect: Float, val pageCount: Int)

    /**
     * Page 1 rendered on white to a JPEG in [thumbnailDir], named by [key] so a
     * changed file gets a new one. `null` for a password-protected (the renderer
     * throws SecurityException) or damaged file.
     */
    private fun renderFirstPage(file: File, key: String): RenderedPage? = try {
        ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY).use { descriptor ->
            PdfRenderer(descriptor).use { renderer ->
                if (renderer.pageCount == 0) return null
                renderer.openPage(0).use { page ->
                    val (height, scale) = thumbnailGeometry(page.width, page.height)
                    val bitmap = Bitmap.createBitmap(THUMBNAIL_WIDTH, height, Bitmap.Config.ARGB_8888)
                    try {
                        bitmap.eraseColor(Color.WHITE)
                        // One scale for both axes, the page's top-left at the bitmap's: a
                        // page taller than the clamp is cut at the bottom, a wider one sits
                        // on white — never stretched (render() fills the bitmap without it).
                        val transform = Matrix().apply { setScale(scale, scale) }
                        page.render(bitmap, null, transform, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                        val out = File(thumbnailDir.apply { mkdirs() }, "${Integer.toHexString(key.hashCode())}.jpg")
                        out.outputStream().use { bitmap.compress(Bitmap.CompressFormat.JPEG, THUMBNAIL_QUALITY, it) }
                        RenderedPage(out.path, THUMBNAIL_WIDTH.toFloat() / height, renderer.pageCount)
                    } finally {
                        bitmap.recycle()
                    }
                }
            }
        }
    } catch (e: Exception) {
        Log.w(TAG, "No first page for ${file.name}", e)
        null
    }

    /**
     * The thumbnail's height and the page's scale into it, for a page of
     * [pageWidth] by [pageHeight] points: the page fills the width, and the
     * height follows it within [MIN_THUMBNAIL_HEIGHT]..[MAX_THUMBNAIL_HEIGHT].
     */
    internal fun thumbnailGeometry(pageWidth: Int, pageHeight: Int): Pair<Int, Float> {
        val scale = THUMBNAIL_WIDTH.toFloat() / pageWidth.coerceAtLeast(1)
        val height = (pageHeight * scale).toInt()
            .coerceIn(MIN_THUMBNAIL_HEIGHT, MAX_THUMBNAIL_HEIGHT)
        return height to scale
    }

    /** A cached PDF preview whose thumbnail the system has since purged from the cache dir is rebuilt. */
    private fun isIntact(preview: FilePreview): Boolean =
        preview !is FilePreview.Pdf || preview.thumbnailPath == null || File(preview.thumbnailPath).exists()

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

    internal companion object {
        const val TAG = "FilePreviewLoader"
        const val CACHE_ENTRIES = 64
        const val THUMBNAIL_DIR = "file_previews"
        // Wide enough for a 280dp bubble on a dense screen, small enough to stay ~100 KB.
        const val THUMBNAIL_WIDTH = 720
        const val THUMBNAIL_QUALITY = 85
        const val A4_ASPECT = 0.707f
        // A landscape slide gets at least half the width in height, a receipt at most three times it.
        const val MIN_THUMBNAIL_HEIGHT = THUMBNAIL_WIDTH / 2
        const val MAX_THUMBNAIL_HEIGHT = THUMBNAIL_WIDTH * 3
    }
}
