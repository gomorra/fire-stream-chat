package com.firestream.chat.data.repository

import android.net.ConnectivityManager
import android.net.Uri
import com.firestream.chat.data.crypto.SignalManager
import com.firestream.chat.data.local.PreferencesDataStore
import com.firestream.chat.data.local.dao.ChatDao
import com.firestream.chat.data.local.dao.MessageDao
import com.firestream.chat.data.local.entity.ChatEntity
import com.firestream.chat.data.local.entity.MessageEntity
import com.firestream.chat.data.remote.source.AuthSource
import com.firestream.chat.data.remote.source.MessageSource
import com.firestream.chat.data.remote.source.StorageSource
import com.firestream.chat.data.remote.source.UserSource
import com.firestream.chat.data.util.ImageCompressor
import com.firestream.chat.data.util.MediaFileManager
import com.firestream.chat.domain.model.Message
import com.firestream.chat.domain.model.MessageStatus
import com.firestream.chat.domain.model.MessageType
import com.firestream.chat.domain.repository.ChatRepository
import com.firestream.chat.domain.repository.ListRepository
import io.mockk.Runs
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.unmockkStatic
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class MessageRepositoryRetryTest {

    private val testDispatcher = StandardTestDispatcher()

    private val messageDao = mockk<MessageDao>(relaxed = true)
    private val chatDao = mockk<ChatDao>(relaxed = true)
    private val messageSource = mockk<MessageSource>(relaxed = true)
    private val authSource = mockk<AuthSource>()
    private val signalManager = mockk<SignalManager>(relaxed = true)
    private val storageSource = mockk<StorageSource>(relaxed = true)
    private val chatRepository = mockk<dagger.Lazy<ChatRepository>>()
    private val mediaFileManager = mockk<MediaFileManager>(relaxed = true)
    private val imageCompressor = mockk<ImageCompressor>()
    private val preferencesDataStore = mockk<PreferencesDataStore>(relaxed = true)
    private val connectivityManager = mockk<ConnectivityManager>(relaxed = true)
    private val listRepository = mockk<dagger.Lazy<ListRepository>>()
    private val userSource = mockk<UserSource>(relaxed = true)

    private val replaceArgs = mutableListOf<Pair<String, MessageEntity>>()
    private val statusUpdates = mutableListOf<Pair<String, String>>()

    private lateinit var repository: MessageRepositoryImpl

    @Before
    fun setUp() {
        Dispatchers.setMain(testDispatcher)

        mockkStatic(Uri::class)
        every { Uri.parse(any()) } answers { mockk(relaxed = true) }
        every { Uri.fromFile(any()) } answers { mockk(relaxed = true) }

        every { authSource.currentUserId } returns "uid1"
        every { preferencesDataStore.sendImagesFullQualityFlow } returns flowOf(false)
        every { preferencesDataStore.e2eEncryptionEnabledFlow } returns flowOf(false)

        coEvery { messageDao.replaceMessage(any(), any()) } answers {
            replaceArgs += (firstArg<String>() to secondArg())
        }
        coEvery { messageDao.updateMessageStatus(any(), any()) } answers {
            statusUpdates += (firstArg<String>() to secondArg())
        }
        coEvery { messageDao.updateLocalUri(any(), any()) } just Runs

        repository = MessageRepositoryImpl(
            messageDao, chatDao, messageSource, authSource, signalManager, storageSource, chatRepository,
            listRepository, mediaFileManager, imageCompressor, preferencesDataStore, connectivityManager,
            userSource
        )
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
        unmockkStatic(Uri::class)
    }

    private fun failedTextMessage(): Message = Message(
        id = "failed-msg-1",
        chatId = "chat1",
        senderId = "uid1",
        content = "hi there",
        type = MessageType.TEXT,
        status = MessageStatus.FAILED,
        timestamp = 1_000L,
    )

    private fun stubExistingFailed(message: Message) {
        coEvery { messageDao.getMessageById(message.id) } returns MessageEntity.fromDomain(message)
    }

    private fun stubChatLastMessageId(chatId: String, lastId: String?) {
        val chatEntity = ChatEntity(
            id = chatId,
            type = "INDIVIDUAL",
            name = null,
            avatarUrl = null,
            participants = listOf("uid1", "uid2"),
            unreadCount = 0,
            createdAt = 0L,
            createdBy = "uid1",
            admins = emptyList(),
            lastMessageId = lastId,
            lastMessageContent = null,
            lastMessageTimestamp = null,
        )
        coEvery { chatDao.getChatById(chatId) } returns chatEntity
    }

    @Test
    fun `text retry succeeds and replaces row in place with SENT`() = runTest {
        val original = failedTextMessage()
        stubExistingFailed(original)
        stubChatLastMessageId(original.chatId, original.id)
        coEvery {
            messageSource.sendPlainMessage(
                chatId = any(), senderId = any(), content = any(), type = any(),
                replyToId = any(), timestamp = any(), mediaUrl = any(),
                isForwarded = any(), duration = any(), mentions = any(),
                emojiSizes = any(), mediaWidth = any(), mediaHeight = any(),
                latitude = any(), longitude = any(), isHd = any()
            )
        } returns "remote-id-after-retry"

        val result = repository.retryFailedMessage(original.id, recipientId = "")

        assertTrue("retry should succeed: ${result.exceptionOrNull()}", result.isSuccess)
        assertEquals("remote-id-after-retry", result.getOrThrow().id)
        assertEquals(MessageStatus.SENT, result.getOrThrow().status)

        // Row was flipped to SENDING first, then replaced with the SENT remote-id row.
        assertEquals(MessageStatus.SENDING.name, statusUpdates.first().second)
        val (oldId, replacement) = replaceArgs.single()
        assertEquals(original.id, oldId)
        assertEquals("remote-id-after-retry", replacement.id)
        assertEquals(MessageStatus.SENT.name, replacement.status)

        // Chat preview was rebound from the deleted old id to the new remote id.
        coVerify {
            chatDao.updateLastMessage(original.chatId, "remote-id-after-retry", any(), original.timestamp)
        }
    }

    @Test
    fun `retry that fails again reverts row to FAILED`() = runTest {
        val original = failedTextMessage()
        stubExistingFailed(original)
        stubChatLastMessageId(original.chatId, lastId = null)
        coEvery {
            messageSource.sendPlainMessage(
                chatId = any(), senderId = any(), content = any(), type = any(),
                replyToId = any(), timestamp = any(), mediaUrl = any(),
                isForwarded = any(), duration = any(), mentions = any(),
                emojiSizes = any(), mediaWidth = any(), mediaHeight = any(),
                latitude = any(), longitude = any(), isHd = any()
            )
        } throws RuntimeException("still offline")

        val result = repository.retryFailedMessage(original.id, recipientId = "")

        assertTrue(result.isFailure)
        // Two updateMessageStatus calls: SENDING (start) then FAILED (revert).
        assertEquals(MessageStatus.SENDING.name, statusUpdates[0].second)
        assertEquals(MessageStatus.FAILED.name, statusUpdates[1].second)
        assertEquals(original.id, statusUpdates[1].first)
        assertTrue(replaceArgs.isEmpty())
    }

    @Test
    fun `retry of non-FAILED message returns failure without IO`() = runTest {
        val notFailed = failedTextMessage().copy(status = MessageStatus.SENT)
        stubExistingFailed(notFailed)

        val result = repository.retryFailedMessage(notFailed.id, recipientId = "")

        assertTrue(result.isFailure)
        // No status flip, no replace, no remote send.
        assertTrue(statusUpdates.isEmpty())
        assertTrue(replaceArgs.isEmpty())
        coVerify(exactly = 0) {
            messageSource.sendPlainMessage(
                chatId = any(), senderId = any(), content = any(), type = any(),
                replyToId = any(), timestamp = any(), mediaUrl = any(),
                isForwarded = any(), duration = any(), mentions = any(),
                emojiSizes = any(), mediaWidth = any(), mediaHeight = any(),
                latitude = any(), longitude = any(), isHd = any()
            )
        }
    }

    @Test
    fun `image retry skips re-compression when dimensions are already known`() = runTest {
        val failed = Message(
            id = "failed-img-1",
            chatId = "chat1",
            senderId = "uid1",
            content = "look",
            type = MessageType.IMAGE,
            status = MessageStatus.FAILED,
            timestamp = 1_000L,
            localUri = "/storage/local/failed-img-1.jpg",
            mediaWidth = 1024,
            mediaHeight = 768,
        )
        stubExistingFailed(failed)
        stubChatLastMessageId(failed.chatId, lastId = null)
        coEvery { storageSource.uploadMedia(any(), any(), any(), any(), any()) } returns
            "https://example/firebase/img.jpg"
        coEvery {
            messageSource.sendPlainMessage(
                chatId = any(), senderId = any(), content = any(), type = any(),
                replyToId = any(), timestamp = any(), mediaUrl = any(),
                isForwarded = any(), duration = any(), mentions = any(),
                emojiSizes = any(), mediaWidth = any(), mediaHeight = any(),
                latitude = any(), longitude = any(), isHd = any()
            )
        } returns "remote-img-id"

        val result = repository.retryFailedMessage(failed.id, recipientId = "")

        assertTrue("retry should succeed: ${result.exceptionOrNull()}", result.isSuccess)
        // imageCompressor.processImage must NOT have been called — saving us a round
        // of quality loss when compression already succeeded on the first attempt.
        coVerify(exactly = 0) { imageCompressor.processImage(any(), any()) }
    }

    @Test
    fun `retry of unknown message id returns failure`() = runTest {
        coEvery { messageDao.getMessageById("ghost") } returns null

        val result = repository.retryFailedMessage("ghost", recipientId = "")

        assertTrue(result.isFailure)
        assertTrue(statusUpdates.isEmpty())
        assertTrue(replaceArgs.isEmpty())
    }
}
