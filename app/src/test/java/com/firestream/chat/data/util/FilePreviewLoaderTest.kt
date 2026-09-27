package com.firestream.chat.data.util

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.firestream.chat.domain.util.FileKind
import com.firestream.chat.domain.util.FilePreview
import com.firestream.chat.domain.util.TextPreview
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.PDPage
import com.tom_roush.pdfbox.pdmodel.PDPageContentStream
import com.tom_roush.pdfbox.pdmodel.font.PDType1Font
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [31], manifest = Config.NONE, application = android.app.Application::class)
class FilePreviewLoaderTest {

    private val context: Context = ApplicationProvider.getApplicationContext()
    private val loader = FilePreviewLoader(context, MediaProcessingLimiter())
    private val files = mutableListOf<File>()

    private fun file(name: String, content: String) =
        File(context.cacheDir, name).apply { writeText(content); files += this }

    @After
    fun tearDown() {
        files.forEach { it.delete() }
    }

    @Test
    fun `a text file previews its first lines`() = runTest {
        val notes = file("notes.txt", "first\nsecond")

        assertEquals(FilePreview.Text("first\nsecond", truncated = false), loader.load(notes.path, FileKind.TEXT))
    }

    @Test
    fun `a large file is read only up to the preview limit`() = runTest {
        val big = file("big.log", "x".repeat(TextPreview.MAX_BYTES * 3))

        val preview = loader.load(big.path, FileKind.TEXT) as FilePreview.Text

        assertEquals(TextPreview.MAX_BYTES, preview.text.length)
        assertTrue(preview.truncated)
    }

    @Test
    fun `a kind without a text preview, or a missing file, is None`() = runTest {
        assertEquals(FilePreview.None, loader.load(file("a.docx", "PK").path, FileKind.WORD))
        assertEquals(FilePreview.None, loader.load(File(context.cacheDir, "gone.txt").path, FileKind.TEXT))
    }

    @Test
    fun `a built preview is cached for the same file and shown at once`() = runTest {
        val notes = file("cached.txt", "hello")
        assertNull(loader.cached(notes.path))

        val first = loader.load(notes.path, FileKind.TEXT)

        assertSame(first, loader.cached(notes.path))
        assertSame(first, loader.load(notes.path, FileKind.TEXT))
    }

    /** A real PDF with a text layer on each of [pages] pages, written by PdfBox itself. */
    private fun pdf(name: String, pages: Int): File {
        PDFBoxResourceLoader.init(context)
        val out = File(context.cacheDir, name).also { files += it }
        PDDocument().use { document ->
            repeat(pages) { index ->
                val page = PDPage()
                document.addPage(page)
                PDPageContentStream(document, page).use { stream ->
                    stream.beginText()
                    stream.setFont(PDType1Font.HELVETICA, 12f)
                    stream.newLineAtOffset(72f, 700f)
                    stream.showText("Page ${index + 1} of the quarterly report")
                    stream.endText()
                }
            }
            document.save(out)
        }
        return out
    }

    @Test
    fun `a PDF previews the text of its first pages, and says when more pages follow`() = runTest {
        val report = pdf("report.pdf", pages = 5)

        val preview = loader.load(report.path, FileKind.PDF) as FilePreview.Pdf
        val text = preview.text!!

        assertTrue(text.text.contains("Page 1 of the quarterly report"))
        assertTrue(text.text.contains("Page 3 of the quarterly report"))
        assertFalse("only the first ${PdfText.MAX_PAGES} pages are read", text.text.contains("Page 4"))
        assertTrue(text.truncated)
    }

    @Test
    fun `a damaged PDF gives no preview rather than throwing`() = runTest {
        val broken = file("broken.pdf", "%PDF-1.4 this is not really a pdf")

        assertEquals(FilePreview.None, loader.load(broken.path, FileKind.PDF))
    }

    // Regression: the page was rendered with no transform, which stretches it to
    // fill the clamped bitmap — a long receipt came out squashed.
    @Test
    fun `a page is scaled uniformly, and only its height is clamped`() {
        val (letterHeight, letterScale) = loader.thumbnailGeometry(612, 792)
        assertEquals(931, letterHeight)
        assertEquals(720f / 612, letterScale, 0.0001f)

        val (receiptHeight, receiptScale) = loader.thumbnailGeometry(200, 2_000)
        assertEquals("cut at three times the width", 720 * 3, receiptHeight)
        assertEquals("not squashed to fit", 720f / 200, receiptScale, 0.0001f)

        val (bannerHeight, _) = loader.thumbnailGeometry(2_000, 200)
        assertEquals("a wide page sits on white at least half as tall as wide", 360, bannerHeight)
    }
}
