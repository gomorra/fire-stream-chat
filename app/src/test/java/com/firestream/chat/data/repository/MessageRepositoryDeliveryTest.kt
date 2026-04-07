package com.firestream.chat.data.repository

import android.net.ConnectivityManager
import com.firestream.chat.data.crypto.SignalManager
import com.firestream.chat.data.local.PreferencesDataStore
import com.firestream.chat.data.local.dao.ChatDao
import com.firestream.chat.data.local.dao.MessageDao
import com.firestream.chat.data.remote.firebase.FirebaseAuthSource
import com.firestream.chat.data.remote.firebase.FirebaseStorageSource
import com.firestream.chat.data.remote.firebase.FirestoreMessageSource
import com.firestream.chat.data.remote.firebase.FirestoreUserSource
import com.firestream.chat.data.util.ImageCompressor
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
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class MessageRepositoryDeliveryTest {

    private val chatDao = mockk<ChatDao>(relaxed = true)
    private val messageDao = mockk<MessageDao>()
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
        coEvery { messageDao.updateMessageStatusBatch(any(), any()) } just Runs
        repository = MessageRepositoryImpl(
            chatDao, messageDao, messageSource, authSource, signalManager, storageSource, chatRepository,
            listRepository, mediaFileManager, imageCompressor, preferencesDataStore, connectivityManager,
            userSource
        )
    }

    // ── markMessagesAsDelivered ───────────────────────────────────────────────

    @Test
    fun `markMessagesAsDelivered writes Firestore and batch-updates Room`() = runTest {
        val ids = listOf("m1", "m2", "m3")
        coEvery { messageSource.markDelivered("chat1", any(), any(), any()) } just Runs

        val result = repository.markMessagesAsDelivered("chat1", ids)

        assertTrue(result.isSuccess)
        ids.forEach { id ->
            coVerify(exactly = 1) { messageSource.markDelivered("chat1", id, "uid1", any()) }
        }
        coVerify(exactly = 1) {
            messageDao.updateMessageStatusBatch(ids, MessageStatus.DELIVERED.name)
        }
    }

    @Test
    fun `markMessagesAsDelivered continues on individual Firestore failure`() = runTest {
        val ids = listOf("m1", "m2", "m3")
        // m2 throws, m1 and m3 succeed
        coEvery { messageSource.markDelivered("chat1", "m1", any(), any()) } just Runs
        coEvery { messageSource.markDelivered("chat1", "m2", any(), any()) } throws RuntimeException("network")
        coEvery { messageSource.markDelivered("chat1", "m3", any(), any()) } just Runs

        val result = repository.markMessagesAsDelivered("chat1", ids)

        // Overall result is success — individual failures are swallowed
        assertTrue(result.isSuccess)
        coVerify(exactly = 1) { messageSource.markDelivered("chat1", "m1", any(), any()) }
        coVerify(exactly = 1) { messageSource.markDelivered("chat1", "m3", any(), any()) }
        // Room batch still runs for all IDs
        coVerify(exactly = 1) {
            messageDao.updateMessageStatusBatch(ids, MessageStatus.DELIVERED.name)
        }
    }

    @Test
    fun `markMessagesAsDelivered fails when not authenticated`() = runTest {
        every { authSource.currentUserId } returns null

        val result = repository.markMessagesAsDelivered("chat1", listOf("m1"))

        assertTrue(result.isFailure)
        coVerify(exactly = 0) { messageSource.markDelivered(any(), any(), any(), any()) }
        coVerify(exactly = 0) { messageDao.updateMessageStatusBatch(any(), any()) }
    }

    // ── markMessagesAsRead ────────────────────────────────────────────────────

    @Test
    fun `markMessagesAsRead writes Firestore and batch-updates Room`() = runTest {
        val ids = listOf("m1", "m2")
        coEvery { messageSource.markRead("chat1", any(), any(), any()) } just Runs

        val result = repository.markMessagesAsRead("chat1", ids)

        assertTrue(result.isSuccess)
        ids.forEach { id ->
            coVerify(exactly = 1) { messageSource.markRead("chat1", id, "uid1", any()) }
        }
        coVerify(exactly = 1) {
            messageDao.updateMessageStatusBatch(ids, MessageStatus.READ.name)
        }
    }

    @Test
    fun `markMessagesAsRead continues on individual Firestore failure`() = runTest {
        val ids = listOf("m1", "m2")
        coEvery { messageSource.markRead("chat1", "m1", any(), any()) } throws RuntimeException("timeout")
        coEvery { messageSource.markRead("chat1", "m2", any(), any()) } just Runs

        val result = repository.markMessagesAsRead("chat1", ids)

        assertTrue(result.isSuccess)
        coVerify(exactly = 1) { messageSource.markRead("chat1", "m2", any(), any()) }
        coVerify(exactly = 1) {
            messageDao.updateMessageStatusBatch(ids, MessageStatus.READ.name)
        }
    }
}
