package com.firestream.chat.data.sticker

import android.content.Context
import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import com.firestream.chat.domain.model.StickerFormat
import com.firestream.chat.test.WebpFixtures.riff
import com.firestream.chat.test.WebpFixtures.sticker
import com.firestream.chat.test.WebpFixtures.vp8l
import com.firestream.chat.test.WebpFixtures.waExif
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import java.io.FileNotFoundException
import java.security.MessageDigest

/** The content-addressed directory: a file is named by its hash, checked before it lands, and stored once. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [31], manifest = Config.NONE, application = android.app.Application::class)
class StickerFilesTest {

    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val files = StickerFiles(context)

    @After
    fun tearDown() {
        context.filesDir.deleteRecursively()
        context.cacheDir.deleteRecursively()
    }

    private fun stored(): List<String> = files.dir.list()?.sorted().orEmpty()

    private fun sha256(bytes: ByteArray) =
        MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

    @Test
    fun `a sticker is stored under the hash of its bytes`() = runTest {
        val bytes = sticker(1, exifJson = """{"emojis":["😀"]}""", animated = true)

        val stored = files.store(bytes)!!

        assertEquals(sha256(bytes), stored.id)
        assertEquals(StickerFormat.WEBP, stored.format)
        assertEquals(512, stored.width)
        assertEquals(512, stored.height)
        assertTrue(stored.isAnimated)
        assertTrue(stored.isNew)
        assertArrayEquals(waExif("""{"emojis":["😀"]}"""), stored.exif)
        assertEquals(listOf("${stored.id}.webp"), stored())
        assertArrayEquals(bytes, files.fileFor(stored.id, stored.format).readBytes())
    }

    @Test
    fun `the same bytes a second time are not stored again`() = runTest {
        val bytes = sticker(2)
        val first = files.store(bytes)!!
        val file = files.fileFor(first.id, first.format)
        file.setLastModified(1_000L)

        val second = files.store(bytes.inputStream())!!

        assertEquals(first.id, second.id)
        assertFalse(second.isNew)
        assertEquals("the file was not rewritten", 1_000L, file.lastModified())
        assertEquals(1, stored().size)
    }

    @Test
    fun `a file that was cut short on disk is written again`() = runTest {
        val bytes = sticker(3)
        val first = files.store(bytes)!!
        val file = files.fileFor(first.id, first.format)
        file.writeBytes(bytes.copyOf(10))

        val second = files.store(bytes)!!

        assertTrue(second.isNew)
        assertArrayEquals(bytes, file.readBytes())
    }

    @Test
    fun `a file at the size cap is stored and one byte more is refused`() = runTest {
        val overhead = sticker(4).size
        val atCap = sticker(4, fillerBytes = StickerFiles.MAX_BYTES - overhead)
        val overCap = sticker(4, fillerBytes = StickerFiles.MAX_BYTES - overhead + 2)
        assertEquals(StickerFiles.MAX_BYTES, atCap.size)

        assertTrue(files.store(atCap.inputStream())!!.isNew)
        assertNull(files.store(overCap))
        assertNull(files.store(overCap.inputStream()))
        assertEquals(1, stored().size)
    }

    @Test
    fun `a stream is read no further than the cap`() = runTest {
        var served = 0L
        val endless = object : java.io.InputStream() {
            override fun read(): Int = 0.also { served++ }
            override fun read(b: ByteArray, off: Int, len: Int): Int = len.also { served += len }
        }

        assertNull(files.store(endless))

        assertTrue("read $served bytes of an endless stream", served <= StickerFiles.MAX_BYTES + 64 * 1024)
    }

    @Test
    fun `bytes that are not a whole WebP file are refused and leave nothing behind`() = runTest {
        assertNull(files.store("not an image".toByteArray()))
        assertNull(files.store(sticker(5).copyOf(30)))
        assertNull(files.store(ByteArray(0).inputStream()))

        assertEquals(emptyList<String>(), stored())
    }

    @Test
    fun `a canvas larger than the dimension cap is refused`() = runTest {
        assertNull(files.store(riff(vp8l(StickerFiles.MAX_DIMENSION + 1, 16))))
        assertNull(files.store(riff(vp8l(16, StickerFiles.MAX_DIMENSION + 1))))
        assertTrue(files.store(riff(vp8l(StickerFiles.MAX_DIMENSION, StickerFiles.MAX_DIMENSION)))!!.isNew)
    }

    @Test
    fun `no temp file is left beside a stored sticker`() = runTest {
        files.store(sticker(6))

        assertTrue(stored().none { it.endsWith(".part") })
    }

    @Test
    fun `an id that is not a hash never becomes a path`() {
        val valid = "a".repeat(64)
        assertEquals(File(files.dir, "$valid.webp"), files.fileFor(valid, StickerFormat.WEBP))

        listOf("../../databases/fire_stream_chat", "a".repeat(63), "A".repeat(64), "$valid/x", "").forEach { id ->
            assertFalse(StickerFiles.isValidId(id))
            assertThrows(IllegalArgumentException::class.java) { files.fileFor(id, StickerFormat.WEBP) }
        }
    }

    @Test
    fun `discard deletes the sticker's file`() = runTest {
        val stored = files.store(sticker(7))!!

        files.discard(stored.id, stored.format)

        assertEquals(emptyList<String>(), stored())
    }

    @Test
    fun `a path and a file uri in the cache directory are opened directly, and a missing file throws`() {
        val source = File(context.cacheDir, "in.webp").apply { writeBytes(byteArrayOf(1, 2, 3)) }

        assertArrayEquals(byteArrayOf(1, 2, 3), files.open(source.absolutePath).use { it.readBytes() })
        assertArrayEquals(byteArrayOf(1, 2, 3), files.open(Uri.fromFile(source).toString()).use { it.readBytes() })
        assertThrows(FileNotFoundException::class.java) { files.open(File(context.cacheDir, "gone.webp").absolutePath) }
    }

    @Test
    fun `a path outside the cache directory is not opened, however it is spelled`() {
        val private = File(context.filesDir, "secret.webp").apply {
            parentFile!!.mkdirs()
            writeBytes(byteArrayOf(9))
        }
        val dotted = File(context.cacheDir, "../${context.filesDir.name}/secret.webp")

        assertThrows(FileNotFoundException::class.java) { files.open(private.absolutePath) }
        assertThrows(FileNotFoundException::class.java) { files.open(Uri.fromFile(private).toString()) }
        assertThrows(FileNotFoundException::class.java) { files.open(dotted.path) }
    }
}
