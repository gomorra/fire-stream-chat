package com.firestream.chat.data.util

import android.content.Context
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import okhttp3.Call
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class ProfileImageManagerTest {

    @get:Rule
    val tempDir = TemporaryFolder()

    private val context = mockk<Context>()
    private val httpClient = mockk<OkHttpClient>()

    private lateinit var profileDir: File
    private lateinit var manager: ProfileImageManager

    @Before
    fun setUp() {
        profileDir = tempDir.newFolder("profile_pictures")
        // externalMediaDirs returns our temp dir's parent so profileDir = parent/profile_pictures
        every { context.externalMediaDirs } returns arrayOf(tempDir.root)

        manager = ProfileImageManager(context, httpClient)
    }

    @Test
    fun `getLocalFile returns file in profile directory`() {
        val file = manager.getLocalFile("user123")
        assertTrue(file.absolutePath.endsWith("profile_pictures/user123.jpg"))
    }

    @Test
    fun `fileExists returns false when file does not exist`() {
        assertFalse(manager.fileExists("nonexistent"))
    }

    @Test
    fun `fileExists returns true when file exists`() {
        val file = manager.getLocalFile("user123")
        file.parentFile?.mkdirs()
        file.writeText("image data")

        assertTrue(manager.fileExists("user123"))
    }

    @Test
    fun `downloadAvatar writes file on success`() = runTest {
        val imageBytes = "fake-image-data".toByteArray()
        stubHttpResponse(200, imageBytes)

        val result = manager.downloadAvatar("user1", "https://example.com/avatar.jpg")

        assertTrue(result.exists())
        assertEquals("fake-image-data", result.readText())
    }

    @Test
    fun `downloadAvatar deletes partial file on failure`() = runTest {
        stubHttpResponse(500, ByteArray(0))

        try {
            manager.downloadAvatar("user1", "https://example.com/avatar.jpg")
        } catch (_: Exception) { }

        assertFalse(manager.getLocalFile("user1").exists())
    }

    @Test
    fun `concurrent downloads for same id are deduplicated`() = runBlocking {
        // Hold coroutine 1's mocked download open via `releaseDownload`, so the
        // dedup slot in inFlightDownloads is still occupied when coroutine 2
        // calls putIfAbsent. Without this, the mocked download completes in
        // microseconds and coroutine 1 may finish (and clear its slot) before
        // coroutine 2 is dispatched — racing the dedup check.
        var callCount = 0
        val imageBytes = "image".toByteArray()
        val downloadStarted = CompletableDeferred<Unit>()
        val releaseDownload = CompletableDeferred<Unit>()

        every { httpClient.newCall(any()) } answers {
            callCount++
            val request = firstArg<Request>()
            val call = mockk<Call>()
            every { call.execute() } answers {
                downloadStarted.complete(Unit)
                runBlocking { releaseDownload.await() }
                Response.Builder()
                    .code(200)
                    .message("OK")
                    .protocol(Protocol.HTTP_1_1)
                    .request(request)
                    .body(imageBytes.toResponseBody())
                    .build()
            }
            call
        }

        val deferred1 = async { manager.downloadAvatar("user1", "https://example.com/a.jpg") }
        downloadStarted.await()
        // Coroutine 1 is now blocked inside execute() with the slot held.
        val deferred2 = async { manager.downloadAvatar("user1", "https://example.com/a.jpg") }
        // Give coroutine 2 time to dispatch and reach putIfAbsent — it's a
        // microsecond op; 50ms is generous on any runner. After this, it's
        // suspended on existing.await() and the dedup is observable.
        Thread.sleep(50)
        releaseDownload.complete(Unit)

        deferred1.await()
        deferred2.await()

        // ConcurrentHashMap.putIfAbsent guarantees at most one winner starts the download;
        // the second coroutine joins via the existing CompletableDeferred.
        assertEquals(1, callCount)
    }

    @Test
    fun `deleteAvatar removes file`() {
        val file = manager.getLocalFile("user1")
        file.parentFile?.mkdirs()
        file.writeText("data")
        assertTrue(file.exists())

        manager.deleteAvatar("user1")

        assertFalse(file.exists())
    }

    private fun stubHttpResponse(code: Int, body: ByteArray) {
        val call = mockk<Call>()
        every { httpClient.newCall(any()) } returns call
        val response = Response.Builder()
            .code(code)
            .message(if (code == 200) "OK" else "Error")
            .protocol(Protocol.HTTP_1_1)
            .request(Request.Builder().url("https://example.com/test").build())
            .body(body.toResponseBody())
            .build()
        every { call.execute() } returns response
    }
}
