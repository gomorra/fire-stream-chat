package com.firestream.chat.data.repository

import com.firestream.chat.data.local.dao.MessageDao
import com.firestream.chat.data.local.entity.MessageEntity
import com.firestream.chat.data.outbox.MessageWriter
import com.firestream.chat.data.remote.source.AuthSource
import com.firestream.chat.domain.model.Message
import com.firestream.chat.domain.model.MessageStatus
import com.firestream.chat.domain.model.MessageType
import io.mockk.Runs
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.slot
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * `forwardMessage` writes the row it inserts, through [MessageWriter.send].
 *
 * Regression: the write used to carry only the text, the media link and its
 * size, so a forwarded video arrived without its thumbnail or length, a voice
 * note without its length and a location without its coordinates — while the
 * sender's own copy showed all of them.
 */
class MessageRepositoryForwardTest {

    private val messageDao = mockk<MessageDao>(relaxed = true)
    private val authSource = mockk<AuthSource>()
    private val messageWriter = mockk<MessageWriter>()

    private val inserted = slot<MessageEntity>()
    private val written = slot<Message>()

    private lateinit var repository: MessageRepositoryImpl

    @Before
    fun setUp() {
        every { authSource.currentUserId } returns "uid1"
        coEvery { messageDao.insertMessage(capture(inserted)) } just Runs
        coEvery { messageWriter.send(capture(written), any()) } answers { firstArg<Message>().id }

        repository = messageRepository(
            messageDao = messageDao,
            authSource = authSource,
            messageWriter = messageWriter,
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
    fun `a forwarded video is written with its thumbnail, size, length and HD flag, to the target chat`() = runTest {
        val video = source(MessageType.VIDEO).copy(
            mediaUrl = "https://storage.example/v",
            mediaThumbnailUrl = "https://storage.example/v_thumb",
            mediaWidth = 1280,
            mediaHeight = 720,
            duration = 12,
            isHd = true,
        )

        val result = repository.forwardMessage(video, targetChatId = "chat2", recipientId = "recipient1")

        assertTrue("forward should succeed: ${result.exceptionOrNull()}", result.isSuccess)
        coVerify(exactly = 1) { messageWriter.send(any(), "recipient1") }
        with(written.captured) {
            assertEquals(inserted.captured.id, id)
            assertEquals("chat2", chatId)
            assertEquals("uid1", senderId)
            assertTrue(isForwarded)
            assertEquals("https://storage.example/v", mediaUrl)
            assertEquals("https://storage.example/v_thumb", mediaThumbnailUrl)
            assertEquals(1280, mediaWidth)
            assertEquals(720, mediaHeight)
            assertEquals(12, duration)
            assertTrue(isHd)
        }
    }

    // Regression: the forward row recorded no recipient, so once a forward whose
    // write never finished was flipped FAILED, OutboxSender refused every retry.
    @Test
    fun `a forward records its recipient and counts its write, so a retry resumes if-absent`() = runTest {
        repository.forwardMessage(source(MessageType.TEXT), targetChatId = "chat2", recipientId = "recipient1")

        assertEquals("recipient1", inserted.captured.outboxRecipientId)
        assertEquals(1, inserted.captured.outboxAttempts)
    }

    @Test
    fun `a forwarded location is written with its coordinates`() = runTest {
        val location = source(MessageType.LOCATION).copy(latitude = 52.52, longitude = 13.40)

        repository.forwardMessage(location, targetChatId = "chat2", recipientId = "recipient1")

        assertEquals(52.52, written.captured.latitude!!, 0.0)
        assertEquals(13.40, written.captured.longitude!!, 0.0)
    }

    @Test
    fun `a forward drops the source chat's mentions, reactions and reply, in the row and on the wire`() = runTest {
        val text = source(MessageType.TEXT).copy(
            mentions = listOf("uid7"),
            reactions = mapOf("uid7" to "👍"),
            replyToId = "earlier",
        )

        repository.forwardMessage(text, targetChatId = "chat2", recipientId = "recipient1")

        with(written.captured) {
            assertTrue(mentions.isEmpty())
            assertTrue(reactions.isEmpty())
            assertNull(replyToId)
        }
        assertTrue(inserted.captured.mentions.isEmpty())
    }
}
