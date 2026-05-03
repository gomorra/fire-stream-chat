package com.firestream.chat.data.repository

import android.net.ConnectivityManager
import com.firestream.chat.data.crypto.SignalManager
import com.firestream.chat.data.local.PreferencesDataStore
import com.firestream.chat.data.local.dao.ChatDao
import com.firestream.chat.data.local.dao.MessageDao
import com.firestream.chat.data.local.entity.MessageEntity
import com.firestream.chat.data.remote.source.AuthSource
import com.firestream.chat.data.remote.source.MessageSource
import com.firestream.chat.data.remote.source.StorageSource
import com.firestream.chat.data.remote.source.TimerSendResult
import com.firestream.chat.data.remote.source.UserSource
import com.firestream.chat.data.util.ImageCompressor
import com.firestream.chat.data.util.MediaFileManager
import com.firestream.chat.domain.model.MessageType
import com.firestream.chat.domain.model.TimerState
import com.firestream.chat.domain.repository.ChatRepository
import com.firestream.chat.domain.repository.ListRepository
import io.mockk.Runs
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.slot
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class MessageRepositoryTimerTest {

    private val messageDao = mockk<MessageDao>(relaxed = true)
    private val chatDao = mockk<ChatDao>(relaxed = true)
    private val messageSource = mockk<MessageSource>()
    private val authSource = mockk<AuthSource>()
    private val signalManager = mockk<SignalManager>(relaxed = true)
    private val storageSource = mockk<StorageSource>()
    private val chatRepository = mockk<dagger.Lazy<ChatRepository>>()
    private val listRepository = mockk<dagger.Lazy<ListRepository>>()
    private val mediaFileManager = mockk<MediaFileManager>(relaxed = true)
    private val imageCompressor = mockk<ImageCompressor>(relaxed = true)
    private val preferencesDataStore = mockk<PreferencesDataStore>(relaxed = true)
    private val connectivityManager = mockk<ConnectivityManager>(relaxed = true)
    private val userSource = mockk<UserSource>(relaxed = true)

    private lateinit var repository: MessageRepositoryImpl

    @Before
    fun setUp() {
        every { authSource.currentUserId } returns "uid1"
        every { messageSource.lastContentFor(any(), any()) } answers { "preview" }
        repository = MessageRepositoryImpl(
            messageDao, chatDao, messageSource, authSource, signalManager, storageSource, chatRepository,
            listRepository, mediaFileManager, imageCompressor, preferencesDataStore, connectivityManager,
            userSource,
        )
    }

    // ── sendTimerMessage ────────────────────────────────────────────────────

    @Test
    fun `sendTimerMessage inserts optimistic RUNNING then replaces with server-resolved time`() = runTest {
        coEvery { userSource.isUserBlocked(any(), any()) } returns false
        coEvery {
            messageSource.sendTimerMessage("chat1", "uid1", 30_000L, "Pizza", any())
        } returns TimerSendResult(messageId = "remoteId", startedAtMs = 1_700_000_000_000L)

        val optimisticSlot = slot<MessageEntity>()
        val replacedSlot = slot<MessageEntity>()
        coEvery { messageDao.insertMessage(capture(optimisticSlot)) } just Runs
        coEvery { messageDao.replaceMessage(any(), capture(replacedSlot)) } just Runs

        val result = repository.sendTimerMessage("chat1", 30_000L, "Pizza", "recipient1")

        assertTrue(result.isSuccess)
        val sent = result.getOrThrow()
        assertEquals("remoteId", sent.id)
        assertEquals(MessageType.TIMER, sent.type)
        assertEquals(TimerState.RUNNING, sent.timerState)
        assertEquals(1_700_000_000_000L, sent.timerStartedAtMs)
        assertEquals(30_000L, sent.timerDurationMs)
        assertEquals("Pizza", sent.content)

        // Optimistic insert was RUNNING with the System.currentTimeMillis()-style
        // timestamp; replacement carries the server-resolved value.
        assertEquals("RUNNING", optimisticSlot.captured.timerState)
        assertEquals(1_700_000_000_000L, replacedSlot.captured.timerStartedAtMs)
    }

    @Test
    fun `sendTimerMessage rejects non-positive duration`() = runTest {
        coEvery { userSource.isUserBlocked(any(), any()) } returns false

        val result = repository.sendTimerMessage("chat1", 0L, null, "recipient1")

        assertTrue(result.isFailure)
        coVerify(exactly = 0) { messageSource.sendTimerMessage(any(), any(), any(), any(), any()) }
    }

    @Test
    fun `sendTimerMessage fails when recipient is blocked`() = runTest {
        coEvery { userSource.isUserBlocked("uid1", "recipient1") } returns true

        val result = repository.sendTimerMessage("chat1", 30_000L, null, "recipient1")

        assertTrue(result.isFailure)
        assertEquals("Cannot send messages to a blocked user", result.exceptionOrNull()?.message)
        coVerify(exactly = 0) { messageSource.sendTimerMessage(any(), any(), any(), any(), any()) }
    }

    @Test
    fun `sendTimerMessage skips block check for empty recipient (group chats)`() = runTest {
        coEvery {
            messageSource.sendTimerMessage("chat1", "uid1", 1_000L, null, any())
        } returns TimerSendResult("remoteId", 1L)

        val result = repository.sendTimerMessage("chat1", 1_000L, null, recipientId = "")

        assertTrue(result.isSuccess)
        coVerify(exactly = 0) { userSource.isUserBlocked(any(), any()) }
    }

    // ── cancelTimer ─────────────────────────────────────────────────────────

    @Test
    fun `cancelTimer flips state to CANCELLED in remote and local stores`() = runTest {
        coEvery { messageSource.updateTimerState("chat1", "msg1", "CANCELLED") } just Runs
        val existing = MessageEntity(
            id = "msg1", chatId = "chat1", senderId = "uid1", content = "",
            type = MessageType.TIMER.name, mediaUrl = null, mediaThumbnailUrl = null,
            status = "SENT", replyToId = null, timestamp = 0L, editedAt = null,
            timerDurationMs = 30_000L, timerStartedAtMs = 0L, timerState = "RUNNING",
        )
        coEvery { messageDao.getMessageById("msg1") } returns existing
        val updated = slot<MessageEntity>()
        coEvery { messageDao.insertMessage(capture(updated)) } just Runs

        val result = repository.cancelTimer("chat1", "msg1")

        assertTrue(result.isSuccess)
        coVerify(exactly = 1) { messageSource.updateTimerState("chat1", "msg1", "CANCELLED") }
        assertEquals("CANCELLED", updated.captured.timerState)
    }

    @Test
    fun `cancelTimer leaves local untouched when row is missing`() = runTest {
        coEvery { messageSource.updateTimerState("chat1", "msg1", "CANCELLED") } just Runs
        coEvery { messageDao.getMessageById("msg1") } returns null

        val result = repository.cancelTimer("chat1", "msg1")

        assertTrue(result.isSuccess)
        coVerify(exactly = 0) { messageDao.insertMessage(any()) }
    }

    // ── markTimerCompleted ──────────────────────────────────────────────────

    @Test
    fun `markTimerCompleted flips state to COMPLETED in remote and local stores`() = runTest {
        coEvery { messageSource.updateTimerState("chat1", "msg1", "COMPLETED") } just Runs
        val existing = MessageEntity(
            id = "msg1", chatId = "chat1", senderId = "uid1", content = "",
            type = MessageType.TIMER.name, mediaUrl = null, mediaThumbnailUrl = null,
            status = "SENT", replyToId = null, timestamp = 0L, editedAt = null,
            timerDurationMs = 30_000L, timerStartedAtMs = 0L, timerState = "RUNNING",
        )
        coEvery { messageDao.getMessageById("msg1") } returns existing
        val updated = slot<MessageEntity>()
        coEvery { messageDao.insertMessage(capture(updated)) } just Runs

        val result = repository.markTimerCompleted("chat1", "msg1")

        assertTrue(result.isSuccess)
        coVerify(exactly = 1) { messageSource.updateTimerState("chat1", "msg1", "COMPLETED") }
        assertEquals("COMPLETED", updated.captured.timerState)
    }
}
