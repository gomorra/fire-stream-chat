package com.firestream.chat.data.util

import android.content.Context
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.async
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
    fun `concurrent downloads for same id are deduplicated`() = runTest {
        var callCount = 0
        val imageBytes = "image".toByteArray()

        every { httpClient.newCall(any()) } answers {
            callCount++
            val call = mockk<Call>()
            val response = Response.Builder()
                .code(200)
                .message("OK")
                .protocol(Protocol.HTTP_1_1)
                .request(firstArg<Request>())
                .body(imageBytes.toResponseBody())
                .build()
            every { call.execute() } returns response
            call
        }

        val deferred1 = async { manager.downloadAvatar("user1", "https://example.com/a.jpg") }
        val deferred2 = async { manager.downloadAvatar("user1", "https://example.com/a.jpg") }

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
