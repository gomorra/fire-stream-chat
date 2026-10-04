package com.firestream.chat.data.sticker

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.firestream.chat.data.local.AppDatabase
import com.firestream.chat.data.repository.newStickerRepository
import com.firestream.chat.domain.model.StickerFormat
import com.firestream.chat.test.LottieFixtures.animation
import com.firestream.chat.test.LottieFixtures.tgs
import com.firestream.chat.test.LottieFixtures.waProps
import com.firestream.chat.test.WebpFixtures.sticker
import com.firestream.chat.test.WebpFixtures.waJson
import com.firestream.chat.test.WebpFixtures.zip
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import io.mockk.spyk
import io.mockk.verify
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.Call
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import java.io.IOException

/**
 * A received sticker against a real sticker directory and a real in-memory
 * database. The id and the url both come from the sender, so every test here is
 * about what is refused, and what is never fetched twice.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [31], manifest = Config.NONE, application = android.app.Application::class)
class StickerDownloadsTest {

    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val httpClient = mockk<OkHttpClient>()
    private lateinit var db: AppDatabase
    private lateinit var files: StickerFiles
    private lateinit var downloads: StickerDownloads

    private val bytes = sticker(1, waJson("com.cats", "Cats", "Ana", listOf("😺")))
    private val id = StickerFiles.sha256Hex(bytes)

    @Before
    fun setUp() {
        db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        files = StickerFiles(context)
        downloads = StickerDownloads(files, db.stickerDao(), httpClient)
    }

    @After
    fun tearDown() {
        db.close()
        context.filesDir.deleteRecursively()
        context.cacheDir.deleteRecursively()
    }

    /** Makes every request answer [code] with [body]. */
    private fun serve(body: ByteArray, code: Int = 200) {
        every { httpClient.newCall(any()) } answers {
            mockk<Call> {
                every { execute() } returns Response.Builder()
                    .request(firstArg<Request>())
                    .protocol(Protocol.HTTP_1_1).code(code).message("status $code")
                    .body(body.toResponseBody())
                    .build()
            }
        }
    }

    private fun storedFiles(): List<String> = files.dir.list()?.sorted().orEmpty()

    @Test
    fun `a received sticker is stored under its id with its row, and a second receive downloads nothing`() = runTest {
        serve(bytes)

        val first = downloads.ensureLocal(id, URL)
        // The same sticker from someone else, at another url.
        val second = downloads.ensureLocal(id, "https://storage.example/elsewhere.webp")

        assertEquals(files.fileFor(id, StickerFormat.WEBP), first)
        assertEquals(first, second)
        assertTrue(first!!.readBytes().contentEquals(bytes))
        verify(exactly = 1) { httpClient.newCall(any()) }
        val row = db.stickerDao().getSticker(id)!!
        assertEquals(listOf("😺"), row.emojis)
        // The sender's url is not this device's to hand on. A send looks the object up itself.
        assertNull(row.remoteUrl)
    }

    @Test
    fun `a received Lottie sticker is stored as a Lottie file with its row and its emojis`() = runTest {
        // What a sender uploads: the library's own file, the compressed animation.
        val lottie = tgs(animation(1, customProps = waProps("SchoolDays", listOf("🚌"))))
        val lottieId = StickerFiles.sha256Hex(lottie)
        serve(lottie)

        val file = downloads.ensureLocal(lottieId, URL)

        assertEquals(files.fileFor(lottieId, StickerFormat.LOTTIE), file)
        assertTrue(file!!.readBytes().contentEquals(lottie))
        val row = db.stickerDao().getSticker(lottieId)!!
        assertEquals(StickerFormat.LOTTIE.name, row.format)
        assertTrue(row.isAnimated)
        assertEquals(listOf("🚌"), row.emojis)
        // Held now: a second message with this sticker downloads nothing.
        assertEquals(file, downloads.ensureLocal(lottieId, "https://storage.example/elsewhere.tgs"))
        verify(exactly = 1) { httpClient.newCall(any()) }
    }

    @Test
    fun `an animation sent as bare JSON is refused, because the library would keep it under another id`() = runTest {
        // The bytes hash to the id the message claims. Stored, they would be compressed, and no longer do.
        val json = animation(2)
        serve(json)

        assertNull(downloads.ensureLocal(StickerFiles.sha256Hex(json), URL))

        assertEquals(emptyList<String>(), storedFiles())
    }

    @Test
    fun `bytes that do not hash to the id the message claims are refused, and nothing is stored`() = runTest {
        serve(sticker(2))

        assertNull(downloads.ensureLocal(id, URL))

        assertTrue(storedFiles().isEmpty())
        assertNull(db.stickerDao().getSticker(id))
        assertNull(db.stickerDao().getSticker(StickerFiles.sha256Hex(sticker(2))))
    }

    @Test
    fun `a refused sticker is not fetched again from the same url`() = runTest {
        serve(sticker(2))

        downloads.ensureLocal(id, URL)
        assertNull(downloads.ensureLocal(id, URL))

        verify(exactly = 1) { httpClient.newCall(any()) }
    }

    @Test
    fun `an id that is not a hash is refused without a download`() = runTest {
        assertNull(downloads.ensureLocal("../../databases/fire_stream_chat", URL))
        assertNull(downloads.ensureLocal(id.uppercase(), URL))
        assertNull(downloads.ensureLocal(null, URL))

        verify(exactly = 0) { httpClient.newCall(any()) }
        assertTrue(storedFiles().isEmpty())
    }

    @Test
    fun `bytes that hash to the id but are not a sticker are refused`() = runTest {
        val notWebp = "<html>not a sticker</html>".toByteArray()
        serve(notWebp)

        assertNull(downloads.ensureLocal(StickerFiles.sha256Hex(notWebp), URL))

        assertTrue(storedFiles().isEmpty())
    }

    @Test
    fun `a body over the size cap is refused`() = runTest {
        serve(ByteArray(StickerFiles.MAX_BYTES + 1))

        assertNull(downloads.ensureLocal(id, URL))

        assertTrue(storedFiles().isEmpty())
    }

    @Test
    fun `a url that is no url is refused, not thrown`() = runTest {
        assertNull(downloads.ensureLocal(id, "not a url"))
    }

    @Test
    fun `a failed download throws and is tried again next time`() = runTest {
        serve(ByteArray(0), code = 503)
        assertTrue(runCatching { downloads.ensureLocal(id, URL) }.exceptionOrNull() is IOException)

        serve(bytes)
        assertNotNull(downloads.ensureLocal(id, URL))
    }

    // A destructive database bump drops the rows and leaves the files.
    @Test
    fun `a file left without its row is checked and taken back without a download`() = runTest {
        files.store(bytes)

        val file = downloads.ensureLocal(id, URL)

        assertEquals(files.fileFor(id, StickerFormat.WEBP), file)
        assertNotNull(db.stickerDao().getSticker(id))
        verify(exactly = 0) { httpClient.newCall(any()) }
    }

    // The undo of a refused archive deletes the files it wrote that no row names.
    // A sticker received while that runs found the file already there, and would
    // lose it. The receive is started from inside the undo's own read of the rows,
    // which is the latest point it can still be missed. With the lock it waits,
    // and stores the file again once the undo is through.
    @Test
    fun `a sticker received while a refused archive is undone keeps its file`() = runTest {
        val dao = spyk(db.stickerDao())
        val downloads = StickerDownloads(files, dao, httpClient)
        val repository = newStickerRepository(dao, files)
        var receive: Deferred<File?>? = null
        coroutineScope {
            coEvery { dao.getStickers(any()) } coAnswers {
                if (receive == null) {
                    receive = async(Dispatchers.IO) { downloads.ensureLocal(id, URL) }
                    // Long enough for an unlocked receive to finish before the delete below it.
                    withTimeoutOrNull(300) { receive!!.await() }
                }
                callOriginal()
            }
            // 201 entries break the archive's cap, after 200 of them were stored. The first is the sticker.
            val entries = (listOf("0.webp" to bytes) + (2..201).map { "$it.webp" to sticker(it) }).toTypedArray()
            val archive = File(context.cacheDir, "bomb.wastickers").apply { writeBytes(zip(*entries)) }

            val result = repository.importFrom(listOf(archive.absolutePath)).getOrThrow()

            assertEquals(1, result.rejected)
            assertEquals(files.fileFor(id, StickerFormat.WEBP), receive!!.await())
        }

        assertEquals(listOf("$id.webp"), storedFiles())
        assertNotNull(db.stickerDao().getSticker(id))
    }

    private companion object {
        const val URL = "https://storage.example/stickers/sticker.webp"
    }
}
