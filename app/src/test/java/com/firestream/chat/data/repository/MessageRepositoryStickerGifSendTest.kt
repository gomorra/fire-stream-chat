package com.firestream.chat.data.repository

import com.firestream.chat.data.local.dao.MessageDao
import com.firestream.chat.data.local.dao.StickerDao
import com.firestream.chat.data.local.entity.MessageEntity
import com.firestream.chat.data.local.entity.StickerEntity
import com.firestream.chat.data.local.entity.StickerPackEntity
import com.firestream.chat.data.outbox.OutboxFiles
import com.firestream.chat.data.outbox.OutboxScheduler
import com.firestream.chat.data.remote.source.AuthSource
import com.firestream.chat.data.sticker.StickerFiles
import com.firestream.chat.data.util.DocumentFiles
import com.firestream.chat.data.util.DocumentInfo
import com.firestream.chat.domain.model.MediaLimitException
import com.firestream.chat.domain.model.MessageStatus
import com.firestream.chat.domain.model.MessageType
import com.firestream.chat.domain.model.StickerFormat
import com.firestream.chat.domain.model.StickerPackKind
import com.firestream.chat.domain.util.MAX_GIF_BYTES
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import java.io.RandomAccessFile

/**
 * What the repository queues for a sticker and for a GIF. A sticker row is
 * built from the library and points at a file the app already keeps. A GIF row
 * is built from a picked uri and is refused over its size limit.
 */
class MessageRepositoryStickerGifSendTest {

    private val messageDao = mockk<MessageDao>(relaxed = true)
    private val authSource = mockk<AuthSource>()
    private val stickerDao = mockk<StickerDao>()
    private val stickerFiles = mockk<StickerFiles>()
    private val documentFiles = mockk<DocumentFiles>()
    private val outboxFiles = mockk<OutboxFiles>(relaxed = true)
    private val outboxScheduler = mockk<OutboxScheduler>(relaxed = true)
    private val inserted = mutableListOf<MessageEntity>()
    private val stickerFile = File.createTempFile("sticker", ".webp")

    private lateinit var repository: MessageRepositoryImpl

    @Before
    fun setUp() {
        every { authSource.currentUserId } returns "uid1"
        coEvery { messageDao.insertOutbox(any()) } answers { inserted += firstArg<MessageEntity>() }
        coEvery { outboxFiles.stage(any(), any(), any()) } returns null
        coEvery { stickerDao.getSticker(any()) } returns null
        coEvery { stickerDao.getPack(any()) } returns null
        every { stickerFiles.fileFor(any(), any()) } returns stickerFile
        coEvery { documentFiles.describe(any()) } returns DocumentInfo("funny.gif", 2_000_000L)
        repository = messageRepository(
            messageDao = messageDao,
            authSource = authSource,
            outboxFiles = outboxFiles,
            outboxScheduler = outboxScheduler,
            documentFiles = documentFiles,
            stickerDao = stickerDao,
            stickerFiles = stickerFiles,
        )
    }

    @After
    fun tearDown() {
        stickerFile.delete()
    }

    private fun librarySticker(remoteUrl: String? = null) {
        coEvery { stickerDao.getSticker(STICKER_ID) } returns StickerEntity(
            id = STICKER_ID, format = StickerFormat.WEBP.name, width = 512, height = 384, isAnimated = true,
            emojis = listOf("😺", "🐈"), createdAt = 1L, remoteUrl = remoteUrl,
        )
    }

    private fun pack(id: String, kind: StickerPackKind) {
        coEvery { stickerDao.getPack(id) } returns StickerPackEntity(
            id = id, name = "Cats", publisher = null, kind = kind.name, originPackId = null,
            importKey = null, sortOrder = 0, createdAt = 1L, updatedAt = 1L,
        )
    }

    // ── stickers ────────────────────────────────────────────────────────────

    @Test
    fun `a sticker is queued from its library row, pointing at the library file`() = runTest {
        librarySticker()
        pack("pack1", StickerPackKind.USER)

        val result = repository.sendStickerMessage("chat1", STICKER_ID, "pack1")

        assertTrue("queued: ${result.exceptionOrNull()}", result.isSuccess)
        val row = inserted.single().toDomain()
        assertEquals(MessageType.STICKER, row.type)
        assertEquals(MessageStatus.SENDING, row.status)
        assertEquals(STICKER_ID, row.stickerId)
        assertEquals("pack1", row.stickerPackId)
        assertEquals("😺", row.content)
        assertEquals(512, row.mediaWidth)
        assertEquals(384, row.mediaHeight)
        assertEquals("image/webp", row.mimeType)
        assertEquals(stickerFile.absolutePath, row.localUri)
        assertNull("nobody has uploaded it yet", row.mediaUrl)
        // Not an upload job: at most 1 MB, and only the first time anyone sends it.
        coVerify(exactly = 1) { outboxScheduler.enqueue(row.id, false) }
    }

    @Test
    fun `a sticker uploaded before is queued with its url, so the send uploads nothing`() = runTest {
        librarySticker(remoteUrl = "https://storage.example/stickers/$STICKER_ID.webp")
        // Its file may be gone: the url is all a send needs.
        stickerFile.delete()

        val result = repository.sendStickerMessage("chat1", STICKER_ID, null)

        assertTrue("queued: ${result.exceptionOrNull()}", result.isSuccess)
        assertEquals("https://storage.example/stickers/$STICKER_ID.webp", inserted.single().mediaUrl)
        coVerify(exactly = 0) { outboxFiles.stage(any(), any(), any()) }
    }

    @Test
    fun `a sticker the library does not hold is refused before any row is written`() = runTest {
        val unknown = repository.sendStickerMessage("chat1", STICKER_ID, null)
        val notAnId = repository.sendStickerMessage("chat1", "../../etc/passwd", null)

        assertTrue(unknown.exceptionOrNull() is NoSuchElementException)
        assertTrue(notAnId.exceptionOrNull() is NoSuchElementException)
        assertTrue(inserted.isEmpty())
        // An id that is not a hash never reaches the database or a path.
        coVerify(exactly = 0) { stickerDao.getSticker("../../etc/passwd") }
    }

    @Test
    fun `a sticker with neither a file nor a url is refused before any row is written`() = runTest {
        librarySticker()
        stickerFile.delete()

        val result = repository.sendStickerMessage("chat1", STICKER_ID, null)

        assertTrue(result.isFailure)
        assertTrue(inserted.isEmpty())
    }

    @Test
    fun `only a pack the user made, imported or installed is named to the recipient`() = runTest {
        librarySticker()
        pack("mine", StickerPackKind.USER)
        pack("installed", StickerPackKind.INSTALLED)
        pack("favourites", StickerPackKind.FAVOURITES)
        pack("saved", StickerPackKind.SAVED)

        listOf("mine", "installed", "favourites", "saved", "gone").forEach {
            repository.sendStickerMessage("chat1", STICKER_ID, it).getOrThrow()
        }

        assertEquals(listOf("mine", "installed", null, null, null), inserted.map { it.stickerPackId })
    }

    // ── GIFs ────────────────────────────────────────────────────────────────

    @Test
    fun `a GIF is queued as it was picked, with its type and caption`() = runTest {
        val result = repository.sendGifMessage("chat1", "content://pick/funny", "image/gif", caption = "look")

        assertTrue("queued: ${result.exceptionOrNull()}", result.isSuccess)
        val row = inserted.single().toDomain()
        assertEquals(MessageType.GIF, row.type)
        assertEquals("look", row.content)
        assertEquals("image/gif", row.mimeType)
        assertEquals("content://pick/funny", row.localUri)
        assertNull(row.stickerId)
        // The staged copy's extension carries the type the upload goes out under.
        coVerify(exactly = 1) { outboxFiles.stage(row.id, "content://pick/funny", "image/gif") }
        coVerify(exactly = 1) { outboxScheduler.enqueue(row.id, true) }
    }

    @Test
    fun `a GIF over the size limit is refused before any row is written`() = runTest {
        coEvery { documentFiles.describe("content://pick/huge") } returns DocumentInfo("huge.gif", MAX_GIF_BYTES + 1)

        val result = repository.sendGifMessage("chat1", "content://pick/huge", "image/gif")

        assertTrue(result.exceptionOrNull() is MediaLimitException)
        assertEquals("GIFs over 8 MB can't be sent", result.exceptionOrNull()?.message)
        assertTrue(inserted.isEmpty())
    }

    @Test
    fun `a GIF at the size limit is sent`() = runTest {
        coEvery { documentFiles.describe("content://pick/big") } returns DocumentInfo("big.gif", MAX_GIF_BYTES)

        assertTrue(repository.sendGifMessage("chat1", "content://pick/big", "image/gif").isSuccess)
    }

    // A provider that reports no size gets past the pre-insert check; the staged
    // copy's real length catches it. Sparse, so the test writes nothing real.
    @Test
    fun `a GIF of unreported size is failed once its copy proves too large`() = runTest {
        val huge = File.createTempFile("huge", ".gif").apply {
            RandomAccessFile(this, "rw").use { it.setLength(MAX_GIF_BYTES + 1) }
        }
        try {
            coEvery { documentFiles.describe(huge.absolutePath) } returns DocumentInfo("huge.gif", null)

            val result = repository.sendGifMessage("chat1", huge.absolutePath, "image/gif")

            assertTrue(result.exceptionOrNull() is MediaLimitException)
            val id = inserted.single().id
            coVerify { messageDao.updateMessageStatus(id, MessageStatus.FAILED.name) }
            coVerify { outboxFiles.delete(id) }
        } finally {
            huge.delete()
        }
    }

    @Test
    fun `a GIF send of something that is not an image is refused`() = runTest {
        val result = repository.sendGifMessage("chat1", "content://pick/clip", "video/mp4")

        assertTrue(result.exceptionOrNull() is IllegalArgumentException)
        assertTrue(inserted.isEmpty())
    }

    private companion object {
        val STICKER_ID = "c".repeat(64)
    }
}
