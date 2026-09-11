package com.firestream.chat.data.repository

import android.net.ConnectivityManager
import android.net.Uri
import com.firestream.chat.data.crypto.SignalManager
import com.firestream.chat.data.local.PreferencesDataStore
import com.firestream.chat.data.local.dao.ChatDao
import com.firestream.chat.data.local.dao.MessageDao
import com.firestream.chat.data.local.entity.MessageEntity
import com.firestream.chat.data.remote.source.AuthSource
import com.firestream.chat.data.remote.source.StorageSource
import com.firestream.chat.data.remote.source.MessageSource
import com.firestream.chat.data.remote.source.UserSource
import com.firestream.chat.data.util.ImageCompressor
import com.firestream.chat.data.util.VideoTranscoder
import com.firestream.chat.data.util.MediaFileManager
import com.firestream.chat.domain.model.MessageStatus
import com.firestream.chat.domain.repository.ChatRepository
import com.firestream.chat.domain.repository.ListRepository
import io.mockk.Runs
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.slot
import io.mockk.unmockkStatic
import io.mockk.verify
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class MessageRepositoryBlockTest {

    private val messageDao = mockk<MessageDao>()
    private val chatDao = mockk<ChatDao>(relaxed = true)
    private val messageSource = mockk<MessageSource>()
    private val authSource = mockk<AuthSource>()
    private val signalManager = mockk<SignalManager>(relaxed = true)
    private val storageSource = mockk<StorageSource>()
    private val chatRepository = mockk<dagger.Lazy<ChatRepository>>()
    private val listRepository = mockk<dagger.Lazy<ListRepository>>()
    private val mediaFileManager = mockk<MediaFileManager>(relaxed = true)
    private val imageCompressor = mockk<ImageCompressor>(relaxed = true)
    private val videoTranscoder = mockk<VideoTranscoder>(relaxed = true)
    private val preferencesDataStore = mockk<PreferencesDataStore>(relaxed = true)
    private val connectivityManager = mockk<ConnectivityManager>(relaxed = true)
    private val userSource = mockk<UserSource>(relaxed = true)

    private lateinit var repository: MessageRepositoryImpl

    private val inserted = slot<MessageEntity>()

    @Before
    fun setUp() {
        // Uri.parse is an Android stub; voice and media sends now reach it before the block check.
        mockkStatic(Uri::class)
        every { Uri.parse(any()) } answers { mockk<Uri>(relaxed = true) }
        every { authSource.currentUserId } returns "uid1"
        every { messageSource.lastContentFor(any(), any()) } answers { secondArg() }
        repository = MessageRepositoryImpl(
            messageDao, chatDao, messageSource, authSource, signalManager, storageSource, chatRepository,
            listRepository, mediaFileManager, imageCompressor, videoTranscoder, preferencesDataStore, connectivityManager,
            userSource
        )
    }

    @After
    fun tearDown() {
        unmockkStatic(Uri::class)
    }

    private fun stubOptimisticRow() {
        coEvery { messageDao.insertMessage(capture(inserted)) } just Runs
        coEvery { messageDao.updateMessageStatus(any(), any()) } just Runs
    }

    /** The send left a visible, retryable bubble and wrote nothing remotely. */
    private fun assertRowInsertedThenFailed() {
        assertEquals(MessageStatus.SENDING.name, inserted.captured.status)
        coVerify(exactly = 1) {
            messageDao.updateMessageStatus(inserted.captured.id, MessageStatus.FAILED.name)
        }
        verify { messageSource wasNot io.mockk.Called }
        verify { storageSource wasNot io.mockk.Called }
    }

    // ── block check cannot be answered (offline cache miss) ─────────────────
    // Regression: the check ran before the optimistic insert, so a throwing
    // fetch dropped the message with no bubble and nothing to retry.

    private fun blockCheckThrows() {
        coEvery { userSource.isUserBlocked("uid1", "recipient1") } throws
            IllegalStateException("Failed to get document because the client is offline.")
    }

    @Test
    fun `sendMessage keeps a FAILED row when the block check throws`() = runTest {
        blockCheckThrows()
        stubOptimisticRow()

        val result = repository.sendMessage("chat1", "hello", "recipient1")

        assertTrue(result.isFailure)
        assertEquals("hello", inserted.captured.content)
        assertRowInsertedThenFailed()
    }

    @Test
    fun `sendMediaMessage keeps a FAILED row when the block check throws`() = runTest {
        blockCheckThrows()
        stubOptimisticRow()

        val result = repository.sendMediaMessage(
            "chat1", "content://docs/report.pdf", "application/pdf", "recipient1", "caption", null
        )

        assertTrue(result.isFailure)
        assertEquals("content://docs/report.pdf", inserted.captured.localUri)
        assertRowInsertedThenFailed()
    }

    @Test
    fun `sendVoiceMessage keeps a FAILED row when the block check throws`() = runTest {
        blockCheckThrows()
        stubOptimisticRow()

        val result = repository.sendVoiceMessage("chat1", "file:///tmp/v.aac", "recipient1", 5)

        assertTrue(result.isFailure)
        assertRowInsertedThenFailed()
    }

    @Test
    fun `sendLocationMessage keeps a FAILED row when the block check throws`() = runTest {
        blockCheckThrows()
        stubOptimisticRow()

        val result = repository.sendLocationMessage("chat1", 1.0, 2.0, "recipient1", "")

        assertTrue(result.isFailure)
        assertRowInsertedThenFailed()
    }

    @Test
    fun `sendTimerMessage keeps a FAILED row when the block check throws`() = runTest {
        blockCheckThrows()
        stubOptimisticRow()

        val result = repository.sendTimerMessage("chat1", 30_000L, null, "recipient1")

        assertTrue(result.isFailure)
        assertRowInsertedThenFailed()
    }

    // ── sendMessage blocked ─────────────────────────────────────────────────

    @Test
    fun `sendMessage fails when recipient is blocked by sender`() = runTest {
        coEvery { userSource.isUserBlocked("uid1", "recipient1") } returns true
        stubOptimisticRow()

        val result = repository.sendMessage("chat1", "hello", "recipient1")

        assertTrue(result.isFailure)
        assertEquals("Cannot send messages to a blocked user", result.exceptionOrNull()?.message)
        assertRowInsertedThenFailed()
    }

    @Test
    fun `sendMessage succeeds when recipient is not blocked`() = runTest {
        coEvery { userSource.isUserBlocked("uid1", "recipient1") } returns false
        coEvery { messageDao.insertMessage(any()) } just Runs
        coEvery { messageSource.sendPlainMessage(any(), any(), any(), any(), any(), any(), any(), any(), any()) } returns "remoteId1"
        coEvery { messageDao.replaceMessage(any(), any()) } just Runs

        val result = repository.sendMessage("chat1", "hello", "recipient1")

        assertTrue(result.isSuccess)
        coVerify(exactly = 1) { messageDao.insertMessage(any()) }
    }

    @Test
    fun `sendMessage skips block check for empty recipientId (group chats)`() = runTest {
        coEvery { messageDao.insertMessage(any()) } just Runs
        coEvery { messageSource.sendPlainMessage(any(), any(), any(), any(), any(), any(), any(), any(), any()) } returns "remoteId1"
        coEvery { messageDao.replaceMessage(any(), any()) } just Runs

        val result = repository.sendMessage("chat1", "hello", "")

        assertTrue(result.isSuccess)
        coVerify(exactly = 0) { userSource.isUserBlocked(any(), any()) }
    }

    // ── forwardMessage blocked ──────────────────────────────────────────────

    @Test
    fun `forwardMessage fails when recipient is blocked by sender`() = runTest {
        coEvery { userSource.isUserBlocked("uid1", "recipient1") } returns true

        val message = com.firestream.chat.domain.model.Message(
            id = "m1", chatId = "chat1", senderId = "uid1", content = "hi",
            type = com.firestream.chat.domain.model.MessageType.TEXT,
            status = com.firestream.chat.domain.model.MessageStatus.SENT,
            timestamp = 1000L
        )
        val result = repository.forwardMessage(message, "chat2", "recipient1")

        assertTrue(result.isFailure)
        assertEquals("Cannot send messages to a blocked user", result.exceptionOrNull()?.message)
        coVerify(exactly = 0) { messageDao.insertMessage(any()) }
    }

    // ── sendVoiceMessage blocked ────────────────────────────────────────────

    @Test
    fun `sendVoiceMessage fails when recipient is blocked by sender`() = runTest {
        coEvery { userSource.isUserBlocked("uid1", "recipient1") } returns true
        stubOptimisticRow()

        val result = repository.sendVoiceMessage("chat1", "file:///tmp/v.aac", "recipient1", 5)

        assertTrue(result.isFailure)
        assertEquals("Cannot send messages to a blocked user", result.exceptionOrNull()?.message)
        assertRowInsertedThenFailed()
    }

    // ── sendMediaMessage blocked ────────────────────────────────────────────

    @Test
    fun `sendMediaMessage fails when recipient is blocked by sender`() = runTest {
        coEvery { userSource.isUserBlocked("uid1", "recipient1") } returns true
        stubOptimisticRow()

        val result = repository.sendMediaMessage(
            "chat1", "content://docs/report.pdf", "application/pdf", "recipient1", "caption", null
        )

        assertTrue(result.isFailure)
        assertEquals("Cannot send messages to a blocked user", result.exceptionOrNull()?.message)
        assertRowInsertedThenFailed()
    }
}
