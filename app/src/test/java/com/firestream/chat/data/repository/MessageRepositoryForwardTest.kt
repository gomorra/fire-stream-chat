package com.firestream.chat.data.repository

import com.firestream.chat.data.local.dao.MessageDao
import com.firestream.chat.data.local.dao.StickerDao
import com.firestream.chat.data.local.entity.MessageEntity
import com.firestream.chat.data.local.entity.StickerEntity
import com.firestream.chat.data.outbox.MessageWriter
import com.firestream.chat.data.outbox.OutboxFiles
import com.firestream.chat.data.outbox.OutboxScheduler
import com.firestream.chat.data.remote.source.AuthSource
import com.firestream.chat.data.remote.source.MessageSource
import com.firestream.chat.data.sticker.StickerFiles
import com.firestream.chat.domain.model.ChatType
import com.firestream.chat.domain.model.Message
import com.firestream.chat.domain.model.MessageStatus
import com.firestream.chat.domain.model.MessageType
import com.firestream.chat.domain.model.StickerFormat
import io.mockk.Called
import io.mockk.Runs
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File

/**
 * A forward is a queued send: the source message, re-stamped for the target
 * chat, is inserted through the outbox factory and handed to [OutboxScheduler]
 * with attempt count 0 — the worker's write is its first attempt. Its media is
 * already uploaded, so the worker's pipeline goes straight to the write with the
 * row as inserted, which is why the row must carry everything the recipient's
 * copy should show.
 */
class MessageRepositoryForwardTest {

    private val messageDao = mockk<MessageDao>(relaxed = true)
    private val messageSource = mockk<MessageSource>()
    private val authSource = mockk<AuthSource>()
    private val messageWriter = mockk<MessageWriter>()
    private val outboxScheduler = mockk<OutboxScheduler>(relaxed = true)
    private val outboxFiles = mockk<OutboxFiles>(relaxed = true)
    private val stickerDao = mockk<StickerDao>(relaxed = true) { coEvery { getSticker(any()) } returns null }
    private val stickerFiles = mockk<StickerFiles>()

    private val inserted = slot<MessageEntity>()

    private lateinit var repository: MessageRepositoryImpl

    @Before
    fun setUp() {
        every { authSource.currentUserId } returns "uid1"
        coEvery { messageDao.insertOutbox(capture(inserted)) } just Runs
        coEvery { outboxFiles.stage(any(), any(), any()) } returns null

        repository = messageRepository(
            messageDao = messageDao,
            chats = listOf(
                testChat("chat2"),
                testChat("group1", ChatType.GROUP.name, listOf("uid1", "member1", "member2")),
            ),
            messageSource = messageSource,
            authSource = authSource,
            messageWriter = messageWriter,
            outboxScheduler = outboxScheduler,
            outboxFiles = outboxFiles,
            stickerDao = stickerDao,
            stickerFiles = stickerFiles,
        )
    }

    private fun source(type: MessageType) = Message(
        id = "src1",
        chatId = "chat1",
        senderId = "uid9",
        content = "look",
        type = type,
        status = MessageStatus.READ,
        timestamp = 1_000L,
    )

    @Test
    fun `a forwarded video is queued for the target chat with its thumbnail, size, length and HD flag`() = runTest {
        val video = source(MessageType.VIDEO).copy(
            mediaUrl = "https://storage.example/v",
            mediaThumbnailUrl = "https://storage.example/v_thumb",
            mediaWidth = 1280,
            mediaHeight = 720,
            duration = 12,
            isHd = true,
        )

        val result = repository.forwardMessage(video, targetChatId = "chat2")

        assertTrue("forward should queue: ${result.exceptionOrNull()}", result.isSuccess)
        assertEquals(MessageStatus.SENDING, result.getOrThrow().status)
        with(inserted.captured) {
            assertEquals("chat2", chatId)
            assertEquals("uid1", senderId)
            assertEquals(MessageStatus.SENDING.name, status)
            assertTrue(isForwarded)
            assertEquals("https://storage.example/v", mediaUrl)
            assertEquals("https://storage.example/v_thumb", mediaThumbnailUrl)
            assertEquals(1280, mediaWidth)
            assertEquals(720, mediaHeight)
            assertEquals(12, duration)
            assertTrue(isHd)
        }
        // Its media is already uploaded, so the work is not an upload.
        verify(exactly = 1) { outboxScheduler.enqueue(inserted.captured.id, uploads = false) }
        // No direct write any more: the worker writes the row.
        verify { messageWriter wasNot Called }
        verify { messageSource wasNot Called }
    }

    // The url a sticker was received with is its first sender's choice. This device
    // shows its own checked file, so a forward must not hand that url on: the next
    // chat could be shown a different picture under this user's name.
    @Test
    fun `a forwarded sticker the library holds goes out with the library's url and file, not the received url`() = runTest {
        val stickerId = "d".repeat(64)
        val libraryFile = File.createTempFile("sticker", ".webp").apply { deleteOnExit() }
        val known = StickerEntity(
            id = stickerId, format = StickerFormat.WEBP.name, width = 512, height = 512, isAnimated = false,
            emojis = emptyList(), createdAt = 1L, remoteUrl = "https://storage.example/stickers/$stickerId.webp",
        )
        val notUploadedYet = known.copy(id = "f".repeat(64), remoteUrl = null)
        coEvery { stickerDao.getSticker(known.id) } returns known
        coEvery { stickerDao.getSticker(notUploadedYet.id) } returns notUploadedYet
        every { stickerFiles.fileFor(any(), any()) } returns libraryFile
        val received = source(MessageType.STICKER).copy(mediaUrl = "https://elsewhere.example/whatever.webp", localUri = null)

        repository.forwardMessage(received.copy(stickerId = known.id), targetChatId = "chat2").getOrThrow()
        val first = inserted.captured
        repository.forwardMessage(received.copy(stickerId = notUploadedYet.id), targetChatId = "chat2").getOrThrow()
        val second = inserted.captured

        assertEquals("https://storage.example/stickers/$stickerId.webp", first.mediaUrl)
        assertEquals(libraryFile.absolutePath, first.localUri)
        // No url yet: OutboxSender looks the shared object up, and uploads it if it is missing.
        assertNull(second.mediaUrl)
        assertEquals(libraryFile.absolutePath, second.localUri)
    }

    // A forwarded sticker is the same sticker: what it points at travels with the
    // row, and its file is neither staged nor uploaded again. This one is not in
    // the library, so it is forwarded as it was received.
    @Test
    fun `a forwarded sticker keeps its id, pack and url, and stages nothing`() = runTest {
        val stickerId = "d".repeat(64)
        val sticker = source(MessageType.STICKER).copy(
            content = "😺",
            mediaUrl = "https://storage.example/stickers/$stickerId.webp",
            localUri = "/data/files/stickers/$stickerId.webp",
            mediaWidth = 512,
            mediaHeight = 512,
            mimeType = "image/webp",
            stickerId = stickerId,
            stickerPackId = "pack1",
        )

        val result = repository.forwardMessage(sticker, targetChatId = "chat2")

        assertTrue("forward should queue: ${result.exceptionOrNull()}", result.isSuccess)
        with(inserted.captured) {
            assertEquals(MessageType.STICKER.name, type)
            assertEquals(stickerId, this.stickerId)
            assertEquals("pack1", stickerPackId)
            assertEquals("https://storage.example/stickers/$stickerId.webp", mediaUrl)
            assertEquals("image/webp", mimeType)
            assertTrue(isForwarded)
        }
        coVerify(exactly = 0) { outboxFiles.stage(any(), any(), any()) }
        verify(exactly = 1) { outboxScheduler.enqueue(inserted.captured.id, uploads = false) }
    }

    @Test
    fun `a forwarded GIF is queued with its url and type, and is not an upload`() = runTest {
        val gif = source(MessageType.GIF).copy(
            mediaUrl = "https://storage.example/g.gif",
            mediaWidth = 320,
            mediaHeight = 240,
            mimeType = "image/gif",
        )

        repository.forwardMessage(gif, targetChatId = "chat2").getOrThrow()

        with(inserted.captured) {
            assertEquals(MessageType.GIF.name, type)
            assertEquals("https://storage.example/g.gif", mediaUrl)
            assertEquals("image/gif", mimeType)
            assertEquals(320, mediaWidth)
        }
        verify(exactly = 1) { outboxScheduler.enqueue(inserted.captured.id, uploads = false) }
    }

    // Regression: the forward once recorded no recipient, so a forward whose
    // write never finished could not be retried; then it wrote directly with
    // attempt count 1. Now the worker's write is the first attempt.
    @Test
    fun `a forward records its target and starts with no attempts, so the worker's write is the first one`() = runTest {
        repository.forwardMessage(source(MessageType.TEXT), targetChatId = "chat2")

        assertEquals("recipient1", inserted.captured.outboxRecipientId)
        assertEquals(0, inserted.captured.outboxAttempts)
    }

    // Regression: the forward dialog once named one member of the target group
    // as the recipient, and the row recorded that member as the peer to encrypt
    // for. The target comes from the target chat's row, not from the caller.
    @Test
    fun `a forward into a group records no peer`() = runTest {
        repository.forwardMessage(source(MessageType.TEXT), targetChatId = "group1")

        assertEquals("", inserted.captured.outboxRecipientId)
    }

    @Test
    fun `a forward's media is already uploaded, so nothing is staged`() = runTest {
        val photo = source(MessageType.IMAGE).copy(mediaUrl = "https://storage.example/p", localUri = "/media/src1.jpg")

        repository.forwardMessage(photo, targetChatId = "chat2")

        coVerify(exactly = 0) { outboxFiles.stage(any(), any(), any()) }
        assertEquals("/media/src1.jpg", inserted.captured.localUri)
    }

    @Test
    fun `a forwarded location keeps its coordinates on the row`() = runTest {
        val location = source(MessageType.LOCATION).copy(latitude = 52.52, longitude = 13.40)

        repository.forwardMessage(location, targetChatId = "chat2")

        assertEquals(52.52, inserted.captured.latitude!!, 0.0)
        assertEquals(13.40, inserted.captured.longitude!!, 0.0)
    }

    @Test
    fun `a forward drops the source chat's mentions, reactions and reply`() = runTest {
        val text = source(MessageType.TEXT).copy(
            mentions = listOf("uid7"),
            reactions = mapOf("uid7" to "👍"),
            replyToId = "earlier",
        )

        repository.forwardMessage(text, targetChatId = "chat2")

        with(inserted.captured) {
            assertTrue(mentions.isEmpty())
            assertTrue(reactions.isEmpty())
            assertNull(replyToId)
        }
    }
}
