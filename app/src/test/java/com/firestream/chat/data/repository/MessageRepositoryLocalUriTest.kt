package com.firestream.chat.data.repository

import com.firestream.chat.data.crypto.SignalManager
import com.firestream.chat.data.local.AutoDownloadOption
import com.firestream.chat.data.local.PreferencesDataStore
import com.firestream.chat.data.local.dao.MessageDao
import com.firestream.chat.data.local.entity.MessageEntity
import com.firestream.chat.data.local.entity.MessageRecord
import com.firestream.chat.data.remote.source.AuthSource
import com.firestream.chat.data.remote.source.MessageSource
import com.firestream.chat.data.remote.source.RawMessage
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
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class MessageRepositoryLocalUriTest {

    private val testDispatcher = StandardTestDispatcher()

    private val messageDao = mockk<MessageDao>()
    private val messageSource = mockk<MessageSource>()
    private val authSource = mockk<AuthSource>()
    private val signalManager = mockk<SignalManager>(relaxed = true)
    private val chatRepository = mockk<dagger.Lazy<ChatRepository>>()
    private val mediaFileManager = mockk<MediaFileManager>(relaxed = true)
    private val preferencesDataStore = mockk<PreferencesDataStore>(relaxed = true)
    private val listRepository = mockk<dagger.Lazy<ListRepository>>()

    private val firestoreFlow = MutableSharedFlow<List<RawMessage>>(extraBufferCapacity = 1)
    private val roomFlow = MutableSharedFlow<List<MessageEntity>>(replay = 1)
    private val upsertSlot = slot<MessageRecord>()
    private val updateSlot = slot<MessageRecord>()

    private lateinit var repository: MessageRepositoryImpl

    @Before
    fun setUp() {
        Dispatchers.setMain(testDispatcher)
        every { authSource.currentUserId } returns "uid1"
        every { messageSource.observeMessages("chat1") } returns firestoreFlow
        every { messageDao.getMessagesByChatId("chat1") } returns roomFlow
        coEvery { signalManager.ensureInitialized() } just Runs
        coEvery { messageDao.upsertRecord(capture(upsertSlot)) } just Runs
        coEvery { messageDao.updateRecord(capture(updateSlot)) } just Runs
        coEvery { messageDao.getMessagesWithoutLocalMediaForChat("chat1") } returns emptyList()
        coEvery { messageDao.updateReactions(any(), any()) } just Runs
        every { preferencesDataStore.autoDownloadFlow } returns flowOf(AutoDownloadOption.NEVER)

        repository = messageRepository(
            messageDao = messageDao,
            messageSource = messageSource,
            authSource = authSource,
            signalManager = signalManager,
            chatRepository = chatRepository,
            listRepository = listRepository,
            mediaFileManager = mediaFileManager,
            preferencesDataStore = preferencesDataStore,
        )
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    // ── local columns on a re-processed message ──────────────────────────────
    // An edit echo of a received message is written as a MessageRecord update,
    // which cannot touch localUri or the star (MessageDaoOutboxColumnsTest pins
    // the DAO side). The repository's part: write the record, and nothing else.

    @Test
    fun `an edited incoming message is written as a record update, not a whole-row replace`() = runTest {
        val localPath = "/storage/emulated/0/Pictures/FireStream Images/msg1.jpg"
        val existingEntity = MessageEntity(
            MessageRecord(
                id = "msg1", chatId = "chat1", senderId = "sender1",
                content = "Hello", type = "IMAGE",
                mediaUrl = "https://firebasestorage.example/msg1.jpg",
                mediaThumbnailUrl = null,
                status = "SENT", replyToId = null, timestamp = 1000L,
                editedAt = null,
            ),
            localUri = localPath,
            isStarred = true,
        )
        coEvery { messageDao.getMessageById("msg1") } returns existingEntity

        val raw = RawMessage(
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

        coVerify(exactly = 1) { messageDao.updateRecord(any()) }
        coVerify(exactly = 0) { messageDao.upsertRecord(any()) }
        coVerify(exactly = 0) { messageDao.insertOutbox(any()) }
        coVerify(exactly = 0) { messageDao.updateLocalUri(any(), any()) }
        assertEquals("Hello edited", updateSlot.captured.content)
        assertEquals(99999L, updateSlot.captured.editedAt)
        // The row already has its file; the edit must not queue a second download.
        coVerify(exactly = 0) { mediaFileManager.downloadAndSave(any(), any(), any()) }

        job.cancel()
    }

    @Test
    fun `a new incoming message is written as a record and downloads its media`() = runTest {
        coEvery { messageDao.getMessageById("msg2") } returns null

        val raw = RawMessage(
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

        coVerify { messageDao.upsertRecord(any()) }
        assertEquals("msg2", upsertSlot.captured.id)
        assertEquals("https://firebasestorage.example/msg2.jpg", upsertSlot.captured.mediaUrl)

        job.cancel()
    }

    // ── ensureLocalCopiesForChat (Shared Media view backfill) ────────────────

    @Test
    fun `ensureLocalCopiesForChat downloads pending media even when auto-download is NEVER`() = runTest {
        // Preference is NEVER (set in setUp) — the explicit gallery view must
        // still persist local copies, unlike the auto-download path.
        val pending = MessageEntity(MessageRecord(
            id = "msgA", chatId = "chat1", senderId = "sender1",
            content = "", type = "IMAGE",
            mediaUrl = "https://firebasestorage.example/msgA.jpg",
            mediaThumbnailUrl = null,
            status = "SENT", replyToId = null, timestamp = 1000L, editedAt = null,
        ))
        coEvery { messageDao.getMessagesWithoutLocalMediaForChat("chat1") } returns listOf(pending)
        val savedFile = java.io.File("/storage/emulated/0/Pictures/FireStream Images/msgA.jpg")
        coEvery {
            mediaFileManager.downloadAndSave("chat1", "msgA", "https://firebasestorage.example/msgA.jpg")
        } returns savedFile
        coEvery { messageDao.updateLocalUri(any(), any()) } just Runs

        repository.ensureLocalCopiesForChat("chat1")
        advanceUntilIdle()

        coVerify { mediaFileManager.downloadAndSave("chat1", "msgA", "https://firebasestorage.example/msgA.jpg") }
        coVerify { messageDao.updateLocalUri("msgA", savedFile.absolutePath) }
    }

    @Test
    fun `ensureLocalCopiesForChat continues past a failed download`() = runTest {
        val failing = MessageEntity(MessageRecord(
            id = "bad", chatId = "chat1", senderId = "s", content = "", type = "IMAGE",
            mediaUrl = "https://firebasestorage.example/bad.jpg", mediaThumbnailUrl = null,
            status = "SENT", replyToId = null, timestamp = 1L, editedAt = null,
        ))
        val ok = MessageEntity(MessageRecord(
            id = "good", chatId = "chat1", senderId = "s", content = "", type = "IMAGE",
            mediaUrl = "https://firebasestorage.example/good.jpg", mediaThumbnailUrl = null,
            status = "SENT", replyToId = null, timestamp = 2L, editedAt = null,
        ))
        coEvery { messageDao.getMessagesWithoutLocalMediaForChat("chat1") } returns listOf(failing, ok)
        coEvery {
            mediaFileManager.downloadAndSave("chat1", "bad", any())
        } throws java.io.IOException("network down")
        val goodFile = java.io.File("/storage/emulated/0/Pictures/FireStream Images/good.jpg")
        coEvery { mediaFileManager.downloadAndSave("chat1", "good", any()) } returns goodFile
        coEvery { messageDao.updateLocalUri(any(), any()) } just Runs

        repository.ensureLocalCopiesForChat("chat1")
        advanceUntilIdle()

        coVerify(exactly = 0) { messageDao.updateLocalUri("bad", any()) }
        coVerify { messageDao.updateLocalUri("good", goodFile.absolutePath) }
    }

    @Test
    fun `existing incoming message with unchanged editedAt is skipped`() = runTest {
        val existingEntity = MessageEntity(
            MessageRecord(
                id = "msg3", chatId = "chat1", senderId = "sender1",
                content = "Hi", type = "TEXT", mediaUrl = null,
                mediaThumbnailUrl = null,
                status = "SENT", replyToId = null, timestamp = 3000L,
                editedAt = null
            ),
            localUri = "/storage/emulated/0/Pictures/FireStream Images/msg3.jpg",
        )
        coEvery { messageDao.getMessageById("msg3") } returns existingEntity

        val raw = RawMessage(
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

        // No write at all for an unchanged message.
        coVerify(exactly = 0) { messageDao.upsertRecord(any()) }
        coVerify(exactly = 0) { messageDao.updateRecord(any()) }

        job.cancel()
    }
}
