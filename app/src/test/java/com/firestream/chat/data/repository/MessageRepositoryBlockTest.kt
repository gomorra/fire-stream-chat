package com.firestream.chat.data.repository

import android.net.ConnectivityManager
import com.firestream.chat.data.crypto.SignalManager
import com.firestream.chat.data.local.PreferencesDataStore
import com.firestream.chat.data.local.dao.ChatDao
import com.firestream.chat.data.local.dao.MessageDao
import com.firestream.chat.data.local.entity.MessageEntity
import com.firestream.chat.data.remote.firebase.FirebaseAuthSource
import com.firestream.chat.data.remote.firebase.FirebaseStorageSource
import com.firestream.chat.data.remote.firebase.FirestoreMessageSource
import com.firestream.chat.data.remote.firebase.FirestoreUserSource
import com.firestream.chat.data.util.ImageCompressor
import com.firestream.chat.data.util.MediaFileManager
import com.firestream.chat.domain.repository.ChatRepository
import com.firestream.chat.domain.repository.ListRepository
import io.mockk.Runs
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class MessageRepositoryBlockTest {

    private val messageDao = mockk<MessageDao>()
    private val chatDao = mockk<ChatDao>(relaxed = true)
    private val messageSource = mockk<FirestoreMessageSource>()
    private val authSource = mockk<FirebaseAuthSource>()
    private val signalManager = mockk<SignalManager>(relaxed = true)
    private val storageSource = mockk<FirebaseStorageSource>()
    private val chatRepository = mockk<dagger.Lazy<ChatRepository>>()
    private val listRepository = mockk<dagger.Lazy<ListRepository>>()
    private val mediaFileManager = mockk<MediaFileManager>(relaxed = true)
    private val imageCompressor = mockk<ImageCompressor>(relaxed = true)
    private val preferencesDataStore = mockk<PreferencesDataStore>(relaxed = true)
    private val connectivityManager = mockk<ConnectivityManager>(relaxed = true)
    private val userSource = mockk<FirestoreUserSource>(relaxed = true)

    private lateinit var repository: MessageRepositoryImpl

    @Before
    fun setUp() {
        every { authSource.currentUserId } returns "uid1"
        repository = MessageRepositoryImpl(
            messageDao, chatDao, messageSource, authSource, signalManager, storageSource, chatRepository,
            listRepository, mediaFileManager, imageCompressor, preferencesDataStore, connectivityManager,
            userSource
        )
    }

    // ── sendMessage blocked ─────────────────────────────────────────────────

    @Test
    fun `sendMessage fails when recipient is blocked by sender`() = runTest {
        coEvery { userSource.isUserBlocked("uid1", "recipient1") } returns true

        val result = repository.sendMessage("chat1", "hello", "recipient1")

        assertTrue(result.isFailure)
        assertEquals("Cannot send messages to a blocked user", result.exceptionOrNull()?.message)
        coVerify(exactly = 0) { messageSource.sendPlainMessage(any(), any(), any(), any(), any(), any()) }
        coVerify(exactly = 0) { messageDao.insertMessage(any()) }
    }

    @Test
    fun `sendMessage succeeds when recipient is not blocked`() = runTest {
        coEvery { userSource.isUserBlocked("uid1", "recipient1") } returns false
        coEvery { messageDao.insertMessage(any()) } just Runs
        coEvery { messageSource.sendPlainMessage(any(), any(), any(), any(), any(), any(), any(), any()) } returns "remoteId1"
        coEvery { messageDao.replaceMessage(any(), any()) } just Runs

        val result = repository.sendMessage("chat1", "hello", "recipient1")

        assertTrue(result.isSuccess)
        coVerify(exactly = 1) { messageDao.insertMessage(any()) }
    }

    @Test
    fun `sendMessage skips block check for empty recipientId (group chats)`() = runTest {
        coEvery { messageDao.insertMessage(any()) } just Runs
        coEvery { messageSource.sendPlainMessage(any(), any(), any(), any(), any(), any(), any(), any()) } returns "remoteId1"
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

        val result = repository.sendVoiceMessage("chat1", "file:///tmp/v.aac", "recipient1", 5)

        assertTrue(result.isFailure)
        assertEquals("Cannot send messages to a blocked user", result.exceptionOrNull()?.message)
        coVerify(exactly = 0) { messageDao.insertMessage(any()) }
    }

    // ── sendMediaMessage blocked ────────────────────────────────────────────

    @Test
    fun `sendMediaMessage fails when recipient is blocked by sender`() = runTest {
        coEvery { userSource.isUserBlocked("uid1", "recipient1") } returns true

        val result = repository.sendMediaMessage("chat1", "file:///tmp/img.jpg", "image/jpeg", "recipient1", "caption")

        assertTrue(result.isFailure)
        assertEquals("Cannot send messages to a blocked user", result.exceptionOrNull()?.message)
        coVerify(exactly = 0) { messageDao.insertMessage(any()) }
    }
}
