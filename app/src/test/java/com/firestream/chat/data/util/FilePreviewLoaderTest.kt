package com.firestream.chat.data.util

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.firestream.chat.domain.util.FileKind
import com.firestream.chat.domain.util.FilePreview
import com.firestream.chat.domain.util.TextPreview
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
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
    private val loader = FilePreviewLoader()
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
}
