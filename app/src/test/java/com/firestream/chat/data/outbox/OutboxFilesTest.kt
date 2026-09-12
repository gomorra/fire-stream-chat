package com.firestream.chat.data.outbox

import android.content.Context
import android.net.Uri
import android.webkit.MimeTypeMap
import androidx.test.core.app.ApplicationProvider
import com.firestream.chat.data.util.MediaFileManager
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertArrayEquals
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
import java.io.FileNotFoundException

/**
 * Staging: a send's input is copied under `filesDir/outbox/<id>.<ext>` unless
 * it is already a file the app keeps, and the copy's extension carries the
 * picked mime type through a retry.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29], manifest = Config.NONE, application = android.app.Application::class)
class OutboxFilesTest {

    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val mediaFileManager = mockk<MediaFileManager>()
    private val files = OutboxFiles(context, mediaFileManager)

    private val outboxDir = File(context.filesDir, "outbox")
    private val mediaDir = File("/media")

    @Before
    fun setUp() {
        every { mediaFileManager.isManagedFile(any()) } answers { firstArg<File>().parentFile == mediaDir }
        shadowOf(MimeTypeMap.getSingleton()).apply {
            addExtensionMimeTypeMapping("jpg", "image/jpeg")
            addExtensionMimeTypeMapping("pdf", "application/pdf")
        }
    }

    @After
    fun tearDown() {
        context.cacheDir.deleteRecursively()
        context.filesDir.deleteRecursively()
    }

    private fun cacheFile(name: String, bytes: ByteArray = byteArrayOf(1, 2, 3)) =
        File(context.cacheDir, "camera/$name").apply {
            parentFile!!.mkdirs()
            writeBytes(bytes)
        }

    @Test
    fun `a cache file URI is copied under the message id with the type's extension`() = runTest {
        val source = cacheFile("photo.jpg")

        val staged = files.stage("msg1", Uri.fromFile(source).toString(), "image/jpeg")!!

        assertEquals(File(outboxDir, "msg1.jpg").absolutePath, staged)
        assertArrayEquals(byteArrayOf(1, 2, 3), File(staged).readBytes())
        assertTrue(files.isStaged(staged))
        assertFalse("a staged copy is deleted at SENT, so a SENT row must not keep it", files.isDurable(staged))
        assertEquals("image/jpeg", files.mimeTypeOf(staged))
    }

    @Test
    fun `a bare cache path is staged like a URI`() = runTest {
        val source = cacheFile("clip.mp4")

        val staged = files.stage("vid1", source.absolutePath, "video/mp4")

        assertEquals(File(outboxDir, "vid1.mp4").absolutePath, staged)
    }

    @Test
    fun `a picked document's type survives as the copy's extension`() = runTest {
        val source = cacheFile("report", bytes = byteArrayOf(9))

        val staged = files.stage("doc1", Uri.fromFile(source).toString(), "application/pdf")!!

        assertTrue(staged.endsWith("/outbox/doc1.pdf"))
        assertEquals("application/pdf", files.mimeTypeOf(staged))
        assertArrayEquals(byteArrayOf(9), File(staged).readBytes())
    }

    @Test
    fun `a type with no known extension still gets one, mapping back to no type`() = runTest {
        val source = cacheFile("blob")

        val staged = files.stage("blob1", source.absolutePath, "application/x-custom-thing")!!

        assertTrue(File(staged).extension.isNotEmpty())
        assertNull(files.mimeTypeOf(staged))
    }

    @Test
    fun `an unknown type falls back to bin`() = runTest {
        val source = cacheFile("blob")

        val staged = files.stage("blob2", source.absolutePath, null)!!

        assertEquals("bin", File(staged).extension)
    }

    @Test
    fun `a file in the media dir is durable and left where it is`() = runTest {
        val localUri = File(mediaDir, "img1.jpg").absolutePath

        assertTrue(files.isDurable(localUri))
        assertNull(files.stage("img1", localUri, "image/jpeg"))
    }

    @Test
    fun `a filesDir file outside the outbox is durable, a staged copy is not`() = runTest {
        val kept = File(context.filesDir, "voice/v1.aac").absolutePath

        assertTrue(files.isDurable(kept))
        assertFalse(files.isDurable(File(outboxDir, "v1.aac").absolutePath))
        assertFalse(files.isDurable("content://picker/1"))
    }

    @Test
    fun `staging an already staged path is a no-op`() = runTest {
        val staged = files.stage("msg1", cacheFile("photo.jpg").absolutePath, "image/jpeg")!!

        assertNull(files.stage("msg1", staged, "image/jpeg"))
        assertTrue(File(staged).exists())
    }

    @Test
    fun `an input that cannot be opened fails the staging rather than queue a dead row`() = runTest {
        val missing = File(context.cacheDir, "camera/gone.jpg")

        val error = runCatching { files.stage("msg1", Uri.fromFile(missing).toString(), "image/jpeg") }.exceptionOrNull()

        assertTrue("got $error", error is FileNotFoundException)
        assertFalse(File(outboxDir, "msg1.jpg").exists())
        assertFalse(File(outboxDir, "msg1.part").exists())
    }

    @Test
    fun `delete removes a message's copy and retainOnly sweeps every other one`() = runTest {
        files.stage("a", cacheFile("a.jpg").absolutePath, "image/jpeg")
        files.stage("b", cacheFile("b.jpg").absolutePath, "image/jpeg")
        files.stage("c", cacheFile("c.jpg").absolutePath, "image/jpeg")

        files.delete("a")
        assertFalse(File(outboxDir, "a.jpg").exists())
        assertTrue(File(outboxDir, "b.jpg").exists())

        files.retainOnly(setOf("c"))
        assertFalse(File(outboxDir, "b.jpg").exists())
        assertTrue(File(outboxDir, "c.jpg").exists())
    }
}
