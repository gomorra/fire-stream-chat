package com.firestream.chat.data.util

import android.content.ContentResolver
import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.provider.MediaStore
import androidx.test.core.app.ApplicationProvider
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import io.mockk.spyk
import io.mockk.unmockkAll
import io.mockk.verify
import kotlinx.coroutines.test.runTest
import com.firestream.chat.data.sticker.StickerDownloads
import com.firestream.chat.domain.model.MessageType
import okhttp3.Call
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.ByteArrayOutputStream
import java.io.File

// Robolectric so ContentValues/MediaStore resolve to real Android stubs.
// ContentResolver is spied to capture insert/update/delete without a real provider.
// Stub Application — FireStreamApp is @HiltAndroidApp and crashes Firebase init on JVM.
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [31], manifest = Config.NONE, application = android.app.Application::class)
class MediaFileManagerTest {

    private val realContext: Context = ApplicationProvider.getApplicationContext()
    private val resolver: ContentResolver = spyk(realContext.contentResolver)
    private val context = spyk(realContext) {
        every { contentResolver } returns resolver
    }
    private val httpClient = mockk<OkHttpClient>(relaxed = true)
    private val stickerDownloads = mockk<StickerDownloads>()

    private val rowUri: Uri = Uri.parse("content://media/external_primary/downloads/1")

    private lateinit var manager: MediaFileManager
    private lateinit var sourceFile: File

    @Before
    fun setUp() {
        manager = MediaFileManager(context, httpClient, DocumentFiles(context), stickerDownloads)

        // A real, readable temp file so the input stream copy works.
        sourceFile = File.createTempFile("mfm-test-", ".jpg").apply {
            writeBytes(byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte(), 0xE0.toByte()))
            deleteOnExit()
        }
    }

    @After
    fun tearDown() {
        unmockkAll()
        sourceFile.delete()
    }

    // ── documents: private file, never MediaStore ─────────────────────────

    // Regression: every DOCUMENT download went through MediaStore.Images, which
    // refuses a non-image mime type — so no document ever got a local copy.
    @Test
    fun `a document downloads into the private documents dir without touching MediaStore`() = runTest {
        val body = "hello, file".toByteArray()
        val call = mockk<Call> {
            every { execute() } returns Response.Builder()
                .request(Request.Builder().url("https://storage.example/o/doc1.txt").build())
                .protocol(Protocol.HTTP_1_1).code(200).message("OK")
                .body(body.toResponseBody())
                .build()
        }
        every { httpClient.newCall(any()) } returns call

        val file = manager.downloadFor(
            "chat1", "doc1", MessageType.DOCUMENT, "https://storage.example/o/doc1.txt", "notes.txt", "text/plain", null,
        )!!

        assertEquals(File(realContext.filesDir, "documents/doc1.txt"), file)
        assertEquals("hello, file", file.readText())
        verify(exactly = 0) { resolver.insert(any(), any()) }
        file.delete()
    }

    // A GIF saved through MediaStore would show up in the gallery, beside the user's photos.
    @Test
    fun `a GIF downloads into the private documents dir without touching MediaStore`() = runTest {
        val body = "GIF89a".toByteArray()
        val call = mockk<Call> {
            every { execute() } returns Response.Builder()
                .request(Request.Builder().url("https://storage.example/o/gif1.gif").build())
                .protocol(Protocol.HTTP_1_1).code(200).message("OK")
                .body(body.toResponseBody())
                .build()
        }
        every { httpClient.newCall(any()) } returns call

        val file = manager.downloadFor(
            "chat1", "gif1", MessageType.GIF, "https://storage.example/o/gif1.gif", null, "image/gif", null,
        )!!

        assertEquals(File(realContext.filesDir, "documents/gif1.gif"), file)
        assertEquals("GIF89a", file.readText())
        verify(exactly = 0) { resolver.insert(any(), any()) }
        file.delete()
    }

    @Test
    fun `a sticker goes to the sticker downloads with its id, and a refusal is no file`() = runTest {
        val sticker = File(realContext.filesDir, "stickers/abc.webp")
        coEvery { stickerDownloads.ensureLocal("abc", "https://storage.example/o/abc.webp") } returns sticker
        coEvery { stickerDownloads.ensureLocal("bad", any()) } returns null

        val kept = manager.downloadFor(
            "chat1", "m1", MessageType.STICKER, "https://storage.example/o/abc.webp", null, "image/webp", "abc",
        )
        val refused = manager.downloadFor(
            "chat1", "m2", MessageType.STICKER, "https://storage.example/o/abc.webp", null, "image/webp", "bad",
        )

        assertEquals(sticker, kept)
        assertNull(refused)
        verify(exactly = 0) { httpClient.newCall(any()) }
        verify(exactly = 0) { resolver.insert(any(), any()) }
    }

    // ── happy path: insert + write + clear IS_PENDING ─────────────────────

    @Test
    fun `saveToDownloads inserts into MediaStore Downloads collection`() = runTest {
        every { resolver.insert(any(), any()) } returns rowUri
        every { resolver.openOutputStream(rowUri) } returns ByteArrayOutputStream()
        every { resolver.update(rowUri, any(), any(), any()) } returns 1

        val expectedCollection = MediaStore.Downloads.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)

        manager.saveToDownloads(sourceFile, "image/jpeg")

        verify(exactly = 1) { resolver.insert(expectedCollection, any()) }
    }

    @Test
    fun `saveToDownloads sets IS_PENDING to 1 on insert`() = runTest {
        val insertValues = slot<ContentValues>()
        every { resolver.insert(any(), capture(insertValues)) } returns rowUri
        every { resolver.openOutputStream(rowUri) } returns ByteArrayOutputStream()
        every { resolver.update(rowUri, any(), any(), any()) } returns 1

        manager.saveToDownloads(sourceFile, "image/jpeg")

        assertEquals(1, insertValues.captured.getAsInteger(MediaStore.MediaColumns.IS_PENDING))
        assertEquals(sourceFile.name, insertValues.captured.getAsString(MediaStore.MediaColumns.DISPLAY_NAME))
        assertEquals("image/jpeg", insertValues.captured.getAsString(MediaStore.MediaColumns.MIME_TYPE))
    }

    @Test
    fun `saveToDownloads sets RELATIVE_PATH to Downloads`() = runTest {
        val insertValues = slot<ContentValues>()
        every { resolver.insert(any(), capture(insertValues)) } returns rowUri
        every { resolver.openOutputStream(rowUri) } returns ByteArrayOutputStream()
        every { resolver.update(rowUri, any(), any(), any()) } returns 1

        manager.saveToDownloads(sourceFile, "image/jpeg")

        // Environment.DIRECTORY_DOWNLOADS is the literal "Download" string —
        // the file appears in the user's Downloads folder.
        assertEquals(
            android.os.Environment.DIRECTORY_DOWNLOADS,
            insertValues.captured.getAsString(MediaStore.MediaColumns.RELATIVE_PATH),
        )
    }

    @Test
    fun `saveToDownloads clears IS_PENDING after write completes`() = runTest {
        every { resolver.insert(any(), any()) } returns rowUri
        every { resolver.openOutputStream(rowUri) } returns ByteArrayOutputStream()

        val updateValues = slot<ContentValues>()
        every { resolver.update(rowUri, capture(updateValues), any(), any()) } returns 1

        manager.saveToDownloads(sourceFile, "image/jpeg")

        assertEquals(0, updateValues.captured.getAsInteger(MediaStore.MediaColumns.IS_PENDING))
    }

    // ── failure path: delete URI on write failure ─────────────────────────

    @Test
    fun `saveToDownloads deletes URI when openOutputStream returns null`() = runTest {
        every { resolver.insert(any(), any()) } returns rowUri
        every { resolver.openOutputStream(rowUri) } returns null
        every { resolver.delete(rowUri, any(), any()) } returns 1

        assertTrue(runCatching { manager.saveToDownloads(sourceFile, "image/jpeg") }.isFailure)
        verify(exactly = 1) { resolver.delete(rowUri, null, null) }
    }

    @Test
    fun `saveToDownloads throws when insert returns null URI`() = runTest {
        every { resolver.insert(any(), any()) } returns null

        assertTrue(runCatching { manager.saveToDownloads(sourceFile, "image/jpeg") }.isFailure)
    }

    // ── public surface coverage for normalization rules ────────────────────

    @Test
    fun `getLocalFile normalizes jpeg extension to jpg`() {
        // jpeg → jpg keeps Coil's content-type detection happy and matches
        // the convention enforced by ImageCompressor.
        val file = manager.getLocalFile("chat1", "msg1", "jpeg")
        assertEquals("msg1.jpg", file.name)
    }

    @Test
    fun `getLocalFile normalizes tiff extension to tif`() {
        val file = manager.getLocalFile("chat1", "msg1", "tiff")
        assertEquals("msg1.tif", file.name)
    }

    @Test
    fun `getLocalFile normalizes mpeg extension to mpg`() {
        val file = manager.getLocalFile("chat1", "msg1", "mpeg")
        assertEquals("msg1.mpg", file.name)
    }

    @Test
    fun `getLocalFile lowercases extension`() {
        val file = manager.getLocalFile("chat1", "msg1", "PNG")
        assertEquals("msg1.png", file.name)
    }

    @Test
    fun `getLocalFile leaves common extensions unchanged`() {
        assertEquals("msg.png", manager.getLocalFile("c", "msg", "png").name)
        assertEquals("msg.webp", manager.getLocalFile("c", "msg", "webp").name)
        assertEquals("msg.mp4", manager.getLocalFile("c", "msg", "mp4").name)
    }
}
