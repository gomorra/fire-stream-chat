package com.firestream.chat.data.repository

import android.net.ConnectivityManager
import com.firestream.chat.data.crypto.SignalManager
import com.firestream.chat.data.local.AutoDownloadOption
import com.firestream.chat.data.local.PreferencesDataStore
import com.firestream.chat.data.local.dao.MessageDao
import com.firestream.chat.data.local.entity.MessageEntity
import com.firestream.chat.data.remote.firebase.FirebaseAuthSource
import com.firestream.chat.data.remote.firebase.FirebaseStorageSource
import com.firestream.chat.data.remote.firebase.FirestoreMessageSource
import com.firestream.chat.data.remote.firebase.RawFirestoreMessage
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
import io.mockk.slot
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class MessageRepositoryLocalUriTest {

    private val testDispatcher = StandardTestDispatcher()

    private val messageDao = mockk<MessageDao>()
    private val messageSource = mockk<FirestoreMessageSource>()
    private val authSource = mockk<FirebaseAuthSource>()
    private val signalManager = mockk<SignalManager>(relaxed = true)
    private val storageSource = mockk<FirebaseStorageSource>()
    private val chatRepository = mockk<dagger.Lazy<ChatRepository>>()
    private val mediaFileManager = mockk<MediaFileManager>(relaxed = true)
    private val imageCompressor = mockk<ImageCompressor>(relaxed = true)
    private val preferencesDataStore = mockk<PreferencesDataStore>(relaxed = true)
    private val connectivityManager = mockk<ConnectivityManager>(relaxed = true)
    private val listRepository = mockk<dagger.Lazy<ListRepository>>()

    private val firestoreFlow = MutableSharedFlow<List<RawFirestoreMessage>>(extraBufferCapacity = 1)
    private val roomFlow = MutableSharedFlow<List<MessageEntity>>(replay = 1)
    private val insertSlot = slot<MessageEntity>()

    private lateinit var repository: MessageRepositoryImpl

    @Before
    fun setUp() {
        Dispatchers.setMain(testDispatcher)
        every { authSource.currentUserId } returns "uid1"
        every { messageSource.observeMessages("chat1") } returns firestoreFlow
        every { messageDao.getMessagesByChatId("chat1") } returns roomFlow
        coEvery { signalManager.ensureInitialized() } just Runs
        coEvery { messageDao.insertMessage(capture(insertSlot)) } just Runs
        coEvery { messageDao.getMessagesWithoutLocalMediaForChat("chat1") } returns emptyList()
        coEvery { messageDao.updateReactions(any(), any()) } just Runs
        every { preferencesDataStore.autoDownloadFlow } returns flowOf(AutoDownloadOption.NEVER)

        repository = MessageRepositoryImpl(
            messageDao, messageSource, authSource, signalManager, storageSource, chatRepository,
            listRepository, mediaFileManager, imageCompressor, preferencesDataStore, connectivityManager
        )
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    // ── localUri preservation ────────────────────────────────────────────────

    @Test
    fun `localUri preserved when incoming message is re-processed after edit`() = runTest {
        val localPath = "/storage/emulated/0/Pictures/FireStream Images/msg1.jpg"
        val existingEntity = MessageEntity(
            id = "msg1", chatId = "chat1", senderId = "sender1",
            content = "Hello", type = "IMAGE",
            mediaUrl = "https://firebasestorage.example/msg1.jpg",
            mediaThumbnailUrl = null,
            localUri = localPath,
            status = "SENT", replyToId = null, timestamp = 1000L,
            editedAt = null, isStarred = true
        )
        coEvery { messageDao.getMessageById("msg1") } returns existingEntity

        val raw = RawFirestoreMessage(
            id = "msg1", chatId = "chat1", senderId = "sender1",
            content = "Hello edited", ciphertext = null, signalType = null,
            type = "IMAGE", mediaUrl = "https://firebasestorage.example/msg1.jpg",
            mediaThumbnailUrl = null, status = "SENT", replyToId = null,
            timestamp = 1000L, editedAt = 99999L // editedAt changed → bypasses skip
        )

        val job = launch(UnconfinedTestDispatcher(testDispatcher.scheduler)) {
            repository.getMessages("chat1").collect { }
        }

        firestoreFlow.emit(listOf(raw))
        advanceUntilIdle()

        coVerify { messageDao.insertMessage(any()) }
        assertEquals(localPath, insertSlot.captured.localUri)
        assertTrue(insertSlot.captured.isStarred)
        assertEquals("Hello edited", insertSlot.captured.content)

        job.cancel()
    }

    @Test
    fun `localUri is null for genuinely new incoming message`() = runTest {
        coEvery { messageDao.getMessageById("msg2") } returns null

        val raw = RawFirestoreMessage(
            id = "msg2", chatId = "chat1", senderId = "sender1",
            content = "New photo", ciphertext = null, signalType = null,
            type = "IMAGE", mediaUrl = "https://firebasestorage.example/msg2.jpg",
            mediaThumbnailUrl = null, status = "SENT", replyToId = null,
            timestamp = 2000L, editedAt = null
        )

        val job = launch(UnconfinedTestDispatcher(testDispatcher.scheduler)) {
            repository.getMessages("chat1").collect { }
        }

        firestoreFlow.emit(listOf(raw))
        advanceUntilIdle()

        coVerify { messageDao.insertMessage(any()) }
        assertNull(insertSlot.captured.localUri)

        job.cancel()
    }

    @Test
    fun `existing incoming message with unchanged editedAt is skipped`() = runTest {
        val existingEntity = MessageEntity(
            id = "msg3", chatId = "chat1", senderId = "sender1",
            content = "Hi", type = "TEXT", mediaUrl = null,
            mediaThumbnailUrl = null,
            localUri = "/storage/emulated/0/Pictures/FireStream Images/msg3.jpg",
            status = "SENT", replyToId = null, timestamp = 3000L,
            editedAt = null
        )
        coEvery { messageDao.getMessageById("msg3") } returns existingEntity

        val raw = RawFirestoreMessage(
            id = "msg3", chatId = "chat1", senderId = "sender1",
            content = "Hi", ciphertext = null, signalType = null,
            type = "TEXT", mediaUrl = null, mediaThumbnailUrl = null,
            status = "SENT", replyToId = null, timestamp = 3000L,
            editedAt = null // same as existing → should be skipped
        )

        val job = launch(UnconfinedTestDispatcher(testDispatcher.scheduler)) {
            repository.getMessages("chat1").collect { }
        }

        firestoreFlow.emit(listOf(raw))
        advanceUntilIdle()

        // insertMessage should NOT be called for this message
        coVerify(exactly = 0) { messageDao.insertMessage(any()) }

        job.cancel()
    }
}
