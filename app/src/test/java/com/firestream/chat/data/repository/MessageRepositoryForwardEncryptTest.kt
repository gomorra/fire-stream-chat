package com.firestream.chat.data.repository

import com.firestream.chat.data.local.dao.ChatDao
import com.firestream.chat.data.local.dao.MessageDao
import com.firestream.chat.data.local.entity.ChatEntity
import com.firestream.chat.data.local.entity.MessageEntity
import com.firestream.chat.data.outbox.OutboxFiles
import com.firestream.chat.data.remote.source.AuthSource
import com.firestream.chat.domain.model.Chat
import com.firestream.chat.domain.model.ChatType
import com.firestream.chat.domain.model.Message
import com.firestream.chat.domain.model.MessageStatus
import com.firestream.chat.domain.model.MessageType
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [31], manifest = Config.NONE, application = android.app.Application::class)
class MessageRepositoryForwardEncryptTest {

    @Test
    fun `forwarding an encrypted media message clears remote URLs and keys and stages the local file`() = runTest {
        val messageDao = mockk<MessageDao>(relaxed = true)
        val chatDao = mockk<ChatDao>(relaxed = true)
        val authSource = mockk<AuthSource>()
        val outboxFiles = mockk<OutboxFiles>(relaxed = true)

        every { authSource.currentUserId } returns "me"
        coEvery { chatDao.getChatById("targetChat") } returns ChatEntity.fromDomain(
            Chat(
                id = "targetChat",
                type = ChatType.INDIVIDUAL,
                participants = listOf("me", "bob"),
            )
        )
        val inserted = slot<MessageEntity>()
        coEvery { messageDao.insertOutbox(capture(inserted)) } returns Unit
        coEvery { outboxFiles.stage(any(), any(), any()) } returns "/files/outbox/staged-fwd.jpg"

        val localSrc = File.createTempFile("fwd-src-", ".jpg").apply {
            writeBytes(byteArrayOf(1, 2, 3))
        }
        try {
            val sourceMsg = Message(
                id = "orig1",
                chatId = "srcChat",
                senderId = "alice",
                content = "caption",
                type = MessageType.IMAGE,
                status = MessageStatus.SENT,
                localUri = localSrc.absolutePath,
                mediaUrl = "https://storage.example/media/srcChat/orig1.enc",
                mediaKey = "oldKey",
                mediaIv = "oldIv",
                mediaSha256 = "oldSha",
                mediaWidth = 800,
                mediaHeight = 600,
            )

            val repo = messageRepository(
                messageDao = messageDao,
                chatDao = chatDao,
                authSource = authSource,
                outboxFiles = outboxFiles,
            )
            val result = repo.forwardMessage(sourceMsg, "targetChat")

            assertTrue(result.isSuccess)
            val forwarded = inserted.captured
            assertEquals("/files/outbox/staged-fwd.jpg", forwarded.localUri)
            assertNull("Must clear mediaUrl so OutboxSender uploads a fresh blob", forwarded.mediaUrl)
            assertNull("Must clear mediaKey so keys are never shared across chats", forwarded.mediaKey)
            assertNull(forwarded.mediaIv)
            assertNull(forwarded.mediaSha256)
            assertNull(forwarded.mediaThumbnailUrl)
            assertNull(forwarded.thumbKey)
            assertNull(forwarded.localThumbUri)
            assertNull(forwarded.mediaWidth)
            assertNull(forwarded.mediaHeight)
            assertTrue(forwarded.isForwarded)
            coVerify(exactly = 1) { outboxFiles.stage(forwarded.id, any(), "image/jpeg") }
        } finally {
            localSrc.delete()
        }
    }
}
