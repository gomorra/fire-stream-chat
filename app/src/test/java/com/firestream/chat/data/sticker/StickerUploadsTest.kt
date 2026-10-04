package com.firestream.chat.data.sticker

import com.firestream.chat.data.local.dao.StickerDao
import com.firestream.chat.data.local.entity.StickerEntity
import com.firestream.chat.data.remote.source.StickerObjectSource
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import java.io.FileNotFoundException

/**
 * A sticker's file reaches the backend once, whoever asks: a send, a backup,
 * or both at the same moment.
 */
class StickerUploadsTest {

    private val id = "a".repeat(64)
    private val rows = HashMap<String, StickerEntity>()
    private val stickerDao = mockk<StickerDao>()
    private val stickerFiles = mockk<StickerFiles>()
    private val objectSource = mockk<StickerObjectSource>()
    private val file = mockk<File> { every { isFile } returns true }

    private val uploads = StickerUploads(stickerDao, stickerFiles, objectSource)

    @Before
    fun setUp() {
        rows[id] = StickerEntity(id, "WEBP", 512, 512, false, emptyList(), createdAt = 1L)
        coEvery { stickerDao.getSticker(any()) } answers { rows[firstArg()] }
        coEvery { stickerDao.setRemoteUrl(any(), any()) } answers {
            rows[firstArg()] = rows.getValue(firstArg()).copy(remoteUrl = secondArg())
        }
        every { stickerFiles.fileFor(any(), any()) } returns file
        coEvery { objectSource.ensureUploaded(any(), any(), any(), any()) } returns URL
    }

    @Test
    fun `a sticker is uploaded under its id and type, and its url is kept on the row`() = runTest {
        assertEquals(URL, uploads.ensureUploaded(id))

        coVerify(exactly = 1) { objectSource.ensureUploaded(id, "webp", "image/webp", file) }
        assertEquals(URL, rows.getValue(id).remoteUrl)
    }

    @Test
    fun `a sticker with a url asks nobody`() = runTest {
        rows[id] = rows.getValue(id).copy(remoteUrl = "https://storage.example/known.webp")

        assertEquals("https://storage.example/known.webp", uploads.ensureUploaded(id))

        coVerify(exactly = 0) { objectSource.ensureUploaded(any(), any(), any(), any()) }
    }

    // A send and the backup reach a new sticker at the same moment.
    @Test
    fun `two callers at once upload one sticker once`() = runTest {
        val gate = CompletableDeferred<Unit>()
        coEvery { objectSource.ensureUploaded(any(), any(), any(), any()) } coAnswers {
            gate.await()
            URL
        }

        val both = listOf(async { uploads.ensureUploaded(id) }, async { uploads.ensureUploaded(id) })
        testScheduler.runCurrent()
        gate.complete(Unit)

        assertEquals(listOf(URL, URL), both.awaitAll())
        coVerify(exactly = 1) { objectSource.ensureUploaded(any(), any(), any(), any()) }
    }

    @Test
    fun `a sticker the library does not hold, and one without a file, are refused before any upload`() = runTest {
        val unknown = runCatching { uploads.ensureUploaded("b".repeat(64)) }.exceptionOrNull()
        every { file.isFile } returns false
        val missing = runCatching { uploads.ensureUploaded(id) }.exceptionOrNull()

        assertTrue(unknown is IllegalStateException)
        assertTrue(missing is FileNotFoundException)
        coVerify(exactly = 0) { objectSource.ensureUploaded(any(), any(), any(), any()) }
    }

    private companion object {
        const val URL = "https://storage.example/stickers/a.webp"
    }
}
