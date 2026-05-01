package com.firestream.chat.data.util

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.firestream.chat.domain.model.AppUpdate
import com.firestream.chat.domain.repository.DownloadProgress
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.SocketPolicy
import okio.Buffer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import java.security.MessageDigest

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29], manifest = Config.NONE, application = android.app.Application::class)
class ApkDownloaderTest {

    private val context: Context = ApplicationProvider.getApplicationContext()
    private val client = OkHttpClient.Builder().build()

    private lateinit var server: MockWebServer
    private lateinit var downloader: ApkDownloader
    private lateinit var apkBytes: ByteArray
    private lateinit var apkSha: String

    @Before
    fun setUp() {
        server = MockWebServer().apply { start() }
        downloader = ApkDownloader(context, client)
        // Deterministic 256 KiB payload — enough to span several emit-buckets.
        apkBytes = ByteArray(256 * 1024) { (it and 0xFF).toByte() }
        apkSha = sha256Hex(apkBytes)
        File(context.cacheDir, "apk_updates").deleteRecursively()
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    private fun update(sha: String = apkSha) = AppUpdate(
        versionCode = 1,
        versionName = "1.0",
        apkUrl = server.url("/firestream.apk").toString(),
        sha256 = sha,
        minSupportedVersionCode = 0,
        releaseNotes = "",
        publishedAt = "",
        mandatory = false
    )

    private fun apkFile(): File =
        File(context.cacheDir, "apk_updates/firestream-v1.0-1.apk")

    private fun sha256Hex(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes)
            .joinToString("") { "%02x".format(it) }

    private fun fullBodyResponse(): MockResponse =
        MockResponse()
            .setResponseCode(200)
            .setHeader("Content-Length", apkBytes.size.toString())
            .setBody(Buffer().write(apkBytes))

    @Test
    fun `fresh download succeeds and writes full file`() = runTest {
        server.enqueue(fullBodyResponse())

        val emissions = downloader.download(update()).toList()

        assertTrue(emissions.last() is DownloadProgress.Done)
        assertEquals(apkBytes.size.toLong(), apkFile().length())
        assertEquals(apkSha, sha256Hex(apkFile().readBytes()))
    }

    @Test
    fun `206 Partial Content resumes from existing prefix`() = runTest {
        // Simulate half-downloaded file from a previous attempt.
        val prefixLen = apkBytes.size / 2
        apkFile().parentFile!!.mkdirs()
        apkFile().writeBytes(apkBytes.copyOfRange(0, prefixLen))

        val tail = apkBytes.copyOfRange(prefixLen, apkBytes.size)
        server.enqueue(
            MockResponse()
                .setResponseCode(206)
                .setHeader("Content-Range", "bytes $prefixLen-${apkBytes.size - 1}/${apkBytes.size}")
                .setHeader("Content-Length", tail.size.toString())
                .setBody(Buffer().write(tail))
        )

        val emissions = downloader.download(update()).toList()

        assertTrue("Last emission was ${emissions.last()}", emissions.last() is DownloadProgress.Done)
        // Server must have received our Range header.
        val recorded = server.takeRequest()
        assertEquals("bytes=$prefixLen-", recorded.getHeader("Range"))
        assertEquals(apkBytes.size.toLong(), apkFile().length())
        assertEquals(apkSha, sha256Hex(apkFile().readBytes()))
    }

    @Test
    fun `200 OK despite Range header truncates and starts over`() = runTest {
        // Half-present partial file.
        val prefixLen = apkBytes.size / 2
        apkFile().parentFile!!.mkdirs()
        apkFile().writeBytes(apkBytes.copyOfRange(0, prefixLen))

        // Server ignores Range and returns full body with 200.
        server.enqueue(fullBodyResponse())

        val emissions = downloader.download(update()).toList()

        assertTrue(emissions.last() is DownloadProgress.Done)
        assertEquals(apkBytes.size.toLong(), apkFile().length())
        assertEquals(apkSha, sha256Hex(apkFile().readBytes()))
    }

    @Test
    fun `416 deletes partial and emits Failed`() = runTest {
        val prefixLen = apkBytes.size / 2
        apkFile().parentFile!!.mkdirs()
        apkFile().writeBytes(apkBytes.copyOfRange(0, prefixLen))

        server.enqueue(MockResponse().setResponseCode(416))

        val emissions = downloader.download(update()).toList()

        assertTrue(emissions.last() is DownloadProgress.Failed)
        assertFalse("Partial file should have been deleted", apkFile().exists())
    }

    @Test
    fun `SHA-256 mismatch deletes file and emits Failed`() = runTest {
        server.enqueue(fullBodyResponse())

        val emissions = downloader.download(update(sha = "00".repeat(32))).toList()

        val terminal = emissions.last()
        assertTrue(terminal is DownloadProgress.Failed)
        assertTrue((terminal as DownloadProgress.Failed).message.contains("Checksum"))
        assertFalse("Bad checksum file must be deleted", apkFile().exists())
    }

    @Test
    fun `unresolvable host maps to friendly No internet connection message`() = runTest {
        // Hijack the apkUrl to point at a TLD that won't resolve.
        val unresolvable = AppUpdate(
            versionCode = 1,
            versionName = "1.0",
            apkUrl = "https://this-host-does-not-exist.invalid/firestream.apk",
            sha256 = apkSha,
            minSupportedVersionCode = 0,
            releaseNotes = "",
            publishedAt = "",
            mandatory = false
        )

        val emissions = downloader.download(unresolvable).toList()

        val terminal = emissions.last()
        assertTrue("Expected Failed but got $terminal", terminal is DownloadProgress.Failed)
        assertEquals("No internet connection", (terminal as DownloadProgress.Failed).message)
    }

    @Test
    fun `IOException mid-stream keeps the partial file for resume`() = runTest {
        // Server promises a full body but disconnects mid-stream — OkHttp's read
        // throws IOException; the downloader must preserve whatever was already
        // written so the next attempt can resume.
        server.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setHeader("Content-Length", apkBytes.size.toString())
                .setBody(Buffer().write(apkBytes))
                .setSocketPolicy(SocketPolicy.DISCONNECT_DURING_RESPONSE_BODY)
                .setBodyDelay(50, java.util.concurrent.TimeUnit.MILLISECONDS)
        )

        val emissions = downloader.download(update()).toList()

        val terminal = emissions.last()
        assertTrue("Expected Failed but got $terminal", terminal is DownloadProgress.Failed)
        assertTrue("Partial file must be preserved for resume", apkFile().exists())
        assertTrue("Partial file should be smaller than full", apkFile().length() < apkBytes.size)
    }
}
