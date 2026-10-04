package com.firestream.chat.data.sticker

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import com.firestream.chat.data.util.MediaProcessingLimiter
import com.firestream.chat.domain.model.StickerCrop
import com.firestream.chat.domain.util.StickerGeometry
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

/**
 * The maker's bitmap work on real pixels: the trim to the subject, the outline,
 * the draft's files, and where a picture lands on the sticker's canvas.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [31], manifest = Config.NONE, application = android.app.Application::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class StickerMakerTest {

    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val cutout = mockk<SubjectCutout>()
    private val encoder = mockk<StickerEncoder>()
    private val maker = StickerMaker(context, MediaProcessingLimiter(), cutout, encoder)

    @After
    fun tearDown() {
        context.cacheDir.deleteRecursively()
    }

    /** A transparent bitmap with one red rectangle, the stand-in for a cutout. */
    private fun subjectAt(width: Int, height: Int, left: Int, top: Int, right: Int, bottom: Int): Bitmap =
        Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888).also { bitmap ->
            Canvas(bitmap).drawRect(
                left.toFloat(), top.toFloat(), right.toFloat(), bottom.toFloat(),
                Paint().apply { color = Color.RED },
            )
        }

    private fun photo(width: Int, height: Int, color: Int = Color.BLUE): Uri {
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888).apply { eraseColor(color) }
        val file = File(context.cacheDir, "picked.png")
        file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        return Uri.fromFile(file)
    }

    /** Makes the encoder answer [bytes], and keeps a copy of what it was given: the maker recycles the bitmap itself. */
    private fun captureEncoded(bytes: ByteArray): List<Bitmap> {
        val drawn = mutableListOf<Bitmap>()
        every { encoder.encode(any()) } answers {
            drawn += firstArg<Bitmap>().copy(Bitmap.Config.ARGB_8888, false)
            bytes
        }
        return drawn
    }

    @Test
    fun `the trim keeps exactly the subject's pixels`() {
        val trimmed = StickerMaker.trimToSubject(subjectAt(200, 100, left = 40, top = 10, right = 100, bottom = 50))

        assertNotNull(trimmed)
        assertEquals(60, trimmed!!.width)
        assertEquals(40, trimmed.height)
        assertEquals(Color.RED, trimmed.getPixel(0, 0))
        assertEquals(Color.RED, trimmed.getPixel(59, 39))
    }

    @Test
    fun `a cutout with nothing in it, or only a speck, has no subject`() {
        assertNull(StickerMaker.trimToSubject(Bitmap.createBitmap(64, 64, Bitmap.Config.ARGB_8888)))
        assertNull(StickerMaker.trimToSubject(subjectAt(64, 64, left = 10, top = 10, right = 14, bottom = 14)))
    }

    @Test
    fun `the outline is white, lies around the subject and leaves the subject as it was`() {
        val subject = Bitmap.createBitmap(400, 400, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.RED) }
        val width = StickerGeometry.outlineWidth(400)

        val outlined = StickerMaker.outlined(subject)

        assertEquals(400 + 2 * width, outlined.width)
        assertEquals(400 + 2 * width, outlined.height)
        assertEquals("the subject", Color.RED, outlined.getPixel(outlined.width / 2, outlined.height / 2))
        assertEquals("beside the subject", Color.WHITE, outlined.getPixel(2, outlined.height / 2))
        assertEquals("above the subject", Color.WHITE, outlined.getPixel(outlined.width / 2, 2))
        assertEquals("the corner is round", 0, Color.alpha(outlined.getPixel(0, 0)))
    }

    @Test
    fun `a draft holds the photo, its trimmed subject and the outlined subject`() = runTest {
        coEvery { cutout.cutOut(any()) } answers {
            val photo = firstArg<Bitmap>()
            subjectAt(photo.width, photo.height, left = 20, top = 20, right = 120, bottom = 70)
        }

        val draft = maker.prepare(photo(300, 200).toString())

        assertEquals(300 to 200, draft.original.width to draft.original.height)
        assertEquals(100 to 50, draft.cutout!!.width to draft.cutout!!.height)
        val outline = StickerGeometry.outlineWidth(100)
        assertEquals(100 + 2 * outline, draft.outlined!!.width)
        listOf(draft.original, draft.cutout!!, draft.outlined!!).forEach { image ->
            val decoded = BitmapFactory.decodeFile(image.path)
            assertEquals(image.width to image.height, decoded.width to decoded.height)
        }
    }

    @Test
    fun `a large photo is decoded down to the working size`() = runTest {
        coEvery { cutout.cutOut(any()) } returns null

        val draft = maker.prepare(photo(2048, 1024).toString())

        assertEquals(StickerMaker.SOURCE_MAX_DIMENSION to StickerMaker.SOURCE_MAX_DIMENSION / 2, draft.original.width to draft.original.height)
    }

    @Test
    fun `a photo without a subject is a draft with the photo alone`() = runTest {
        coEvery { cutout.cutOut(any()) } returns null

        val draft = maker.prepare(photo(120, 120).toString())

        assertNull(draft.cutout)
        assertNull(draft.outlined)
        assertTrue(File(draft.original.path).isFile)
    }

    @Test
    fun `preparing a photo deletes the draft before it`() = runTest {
        coEvery { cutout.cutOut(any()) } returns null
        val first = maker.prepare(photo(120, 120).toString())

        val second = maker.prepare(photo(120, 120).toString())

        assertTrue(!File(first.original.path).exists())
        assertTrue(File(second.original.path).isFile)
    }

    @Test
    fun `a picture is drawn on a square canvas where the crop places it`() = runTest {
        coEvery { cutout.cutOut(any()) } returns null
        val drawn = captureEncoded(byteArrayOf(1, 2, 3))
        val draft = maker.prepare(photo(400, 200, Color.GREEN).toString())

        val bytes = maker.render(draft.original.path, StickerCrop())

        assertEquals(3, bytes.size)
        val sticker = drawn.single()
        assertEquals(StickerGeometry.CANVAS to StickerGeometry.CANVAS, sticker.width to sticker.height)
        assertEquals("the wide photo fills the middle", Color.GREEN, sticker.getPixel(256, 256))
        assertEquals("and leaves the top transparent", 0, Color.alpha(sticker.getPixel(256, 60)))
        assertEquals("and the bottom", 0, Color.alpha(sticker.getPixel(256, 450)))
    }

    @Test
    fun `a zoom fills the canvas`() = runTest {
        coEvery { cutout.cutOut(any()) } returns null
        val drawn = captureEncoded(byteArrayOf(1))
        val draft = maker.prepare(photo(400, 200, Color.GREEN).toString())

        maker.render(draft.original.path, StickerCrop(scale = 2f))

        assertEquals(Color.GREEN, drawn.single().getPixel(256, 4))
        assertEquals(Color.GREEN, drawn.single().getPixel(256, 507))
    }

    @Test
    fun `only a draft's own file is rendered`() = runTest {
        val outside = File(context.cacheDir, "elsewhere.png").apply { writeBytes(byteArrayOf(1)) }

        val result = runCatching { maker.render(outside.path, StickerCrop()) }
        val escaped = runCatching {
            maker.render(File(context.cacheDir, "${StickerMaker.DIR_NAME}/../elsewhere.png").path, StickerCrop())
        }

        assertTrue(result.exceptionOrNull() is IllegalArgumentException)
        assertTrue(escaped.exceptionOrNull() is IllegalArgumentException)
    }

    @Test
    fun `a picture too detailed for the size limit is an error, not an empty sticker`() = runTest {
        coEvery { cutout.cutOut(any()) } returns null
        every { encoder.encode(any()) } returns null
        val draft = maker.prepare(photo(100, 100).toString())

        val result = runCatching { maker.render(draft.original.path, StickerCrop()) }

        // Not an IOException, which the screen would show as a lost connection.
        assertTrue(result.exceptionOrNull() is IllegalStateException)
        assertEquals("That picture has too much detail for a sticker", result.exceptionOrNull()?.message)
    }

    @Test
    fun `a photo that cannot be read says so`() = runTest {
        val result = runCatching { maker.prepare(Uri.fromFile(File(context.cacheDir, "missing.png")).toString()) }

        assertTrue(result.exceptionOrNull() is IllegalStateException)
        assertEquals("That photo could not be read", result.exceptionOrNull()?.message)
    }

    // --- A picture from the keyboard ---

    @Test
    fun `a converted picture keeps its shape, with its long edge at the sticker's size`() = runTest {
        val encoded = captureEncoded(byteArrayOf(1, 2, 3))

        val bytes = maker.convert(photo(2000, 1000).toString())

        assertEquals(listOf<Byte>(1, 2, 3), bytes.toList())
        assertEquals(StickerGeometry.CANVAS to StickerGeometry.CANVAS / 2, encoded.single().let { it.width to it.height })
    }

    @Test
    fun `a small picture is converted at its own size`() = runTest {
        val encoded = captureEncoded(byteArrayOf(1))

        maker.convert(photo(120, 90).toString())

        assertEquals(120 to 90, encoded.single().let { it.width to it.height })
    }

    @Test
    fun `a picture that cannot be read or encoded is not converted, and says why`() = runTest {
        every { encoder.encode(any()) } returns null

        val missing = runCatching { maker.convert(Uri.fromFile(File(context.cacheDir, "missing.png")).toString()) }
        val detailed = runCatching { maker.convert(photo(100, 100).toString()) }

        assertTrue(missing.exceptionOrNull() is IllegalStateException)
        assertEquals("That picture could not be read", missing.exceptionOrNull()?.message)
        assertEquals("That picture has too much detail for a sticker", detailed.exceptionOrNull()?.message)
    }
}
