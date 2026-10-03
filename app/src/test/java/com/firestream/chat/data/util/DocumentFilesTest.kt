package com.firestream.chat.data.util

import android.content.Context
import android.webkit.MimeTypeMap
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [31], manifest = Config.NONE, application = android.app.Application::class)
class DocumentFilesTest {

    private val context: Context = ApplicationProvider.getApplicationContext()
    private val documentFiles = DocumentFiles(context)

    @Before
    fun setUp() {
        shadowOf(MimeTypeMap.getSingleton()).apply {
            addExtensionMimeTypeMapping("pdf", "application/pdf")
            addExtensionMimeTypeMapping("txt", "text/plain")
            addExtensionMimeTypeMapping("docx", "application/vnd.openxmlformats-officedocument.wordprocessingml.document")
        }
        documentFiles.dir.deleteRecursively()
    }

    @Test
    fun `the file name's extension wins over the mime type and the URL`() {
        assertEquals("md", DocumentFiles.extensionFor("Notes.MD", "text/plain", "https://x/o/id.pdf"))
    }

    @Test
    fun `without a usable name the mime type's platform extension is used, never its raw subtype`() {
        assertEquals("txt", DocumentFiles.extensionFor(null, "text/plain", null))
        assertEquals(
            "docx",
            DocumentFiles.extensionFor("README", "application/vnd.openxmlformats-officedocument.wordprocessingml.document", null),
        )
    }

    @Test
    fun `a legacy message falls back to the encoded Storage object name`() {
        val url = "https://firebasestorage.googleapis.com/v0/b/app/o/media%2Fchat1%2Fmsg1.pdf?alt=media&token=abc"
        assertEquals("pdf", DocumentFiles.extensionFor(null, null, url))
    }

    @Test
    fun `a legacy object named by the raw mime subtype gets that type's platform extension`() {
        // Before d974555 the Storage object was named "<id>.<subtype>": "plain", and
        // for a .docx a dotted "vnd.openxmlformats-officedocument.wordprocessingml.document".
        assertEquals("txt", DocumentFiles.extensionFor(null, null, "https://x/o/media%2Fchat1%2Fmsg1.plain?alt=media"))
        assertEquals(
            "docx",
            DocumentFiles.extensionFor(
                null, null,
                "https://x/o/media%2Fchat1%2Fmsg1.vnd.openxmlformats-officedocument.wordprocessingml.document?alt=media",
            ),
        )
    }

    @Test
    fun `nothing usable gives bin, and a hostile extension cannot steer the path`() {
        assertEquals("bin", DocumentFiles.extensionFor(null, null, null))
        assertEquals("bin", DocumentFiles.extensionFor("evil.x/../../y", "application/x-unknown", null))
        assertEquals("bin", DocumentFiles.extensionFor("noextension", null, "https://x/o/noext?alt=media"))
    }

    @Test
    fun `fileFor names the copy after the message id inside the documents dir`() {
        val file = documentFiles.fileFor("msg1", "Report.pdf", "application/pdf", null)
        assertEquals(File(context.filesDir, "documents/msg1.pdf"), file)
        assertTrue(file.parentFile!!.isDirectory)
    }

    @Test
    fun `describe reads name and size from a bare path`() = runTest {
        val file = File(context.cacheDir, "hello.txt").apply { writeText("hello") }
        assertEquals(DocumentInfo("hello.txt", 5L), documentFiles.describe(file.absolutePath))
    }

    @Test
    fun `adopt moves a staged copy into the documents dir under the picked name's extension`() = runTest {
        val staged = File(context.filesDir, "outbox/msg1.pdf").apply { parentFile!!.mkdirs(); writeText("%PDF") }

        val kept = documentFiles.adopt("msg1", staged.absolutePath, "Report.pdf", "application/pdf")

        assertEquals(File(context.filesDir, "documents/msg1.pdf").absolutePath, kept)
        assertEquals("%PDF", File(kept!!).readText())
        assertFalse(staged.exists())
    }

    @Test
    fun `adopt of a copy an earlier attempt already moved answers null`() = runTest {
        assertNull(documentFiles.adopt("msg1", File(context.filesDir, "outbox/msg1.pdf").absolutePath, "a.pdf", null))
    }

    @Test
    fun `discard deletes only the copy named after that message`() = runTest {
        val own = documentFiles.fileFor("msg1", "a.pdf", null, null).apply { writeText("x") }
        val source = documentFiles.fileFor("src1", "a.pdf", null, null).apply { writeText("y") }

        documentFiles.discard("msg1")

        assertFalse(own.exists())
        assertTrue("a forward's source keeps its file", source.exists())
    }
}
