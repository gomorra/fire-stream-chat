package com.firestream.chat.data.util

import android.content.ContentResolver
import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.provider.MediaStore
import androidx.test.core.app.ApplicationProvider
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import io.mockk.spyk
import io.mockk.unmockkAll
import io.mockk.verify
import kotlinx.coroutines.test.runTest
import okhttp3.OkHttpClient
import org.junit.After
import org.junit.Assert.assertEquals
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
@Config(sdk = [29], manifest = Config.NONE, application = android.app.Application::class)
class MediaFileManagerTest {

    private val realContext: Context = ApplicationProvider.getApplicationContext()
    private val resolver: ContentResolver = spyk(realContext.contentResolver)
    private val context = spyk(realContext) {
        every { contentResolver } returns resolver
    }
    private val httpClient = mockk<OkHttpClient>(relaxed = true)

    private val rowUri: Uri = Uri.parse("content://media/external_primary/downloads/1")

    private lateinit var manager: MediaFileManager
    private lateinit var sourceFile: File

    @Before
    fun setUp() {
        manager = MediaFileManager(context, httpClient)

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
