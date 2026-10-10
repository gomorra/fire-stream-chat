package com.firestream.chat.data.repository

import com.firestream.chat.data.local.dao.MessageDao
import com.firestream.chat.data.local.dao.MessageSyncStateDao
import com.firestream.chat.data.local.entity.MessageSyncStateEntity
import com.firestream.chat.data.remote.source.AuthSource
import com.firestream.chat.data.remote.source.MessageSource
import com.firestream.chat.data.remote.source.RawMessage
import com.firestream.chat.domain.util.MessageSyncPlan
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap

/**
 * The chat-list sync asks each chat for what Room lacks. A chat is fetched whole
 * once, and its state row then holds the cursor the next sync asks from. The
 * cursor moves only on documents a fetch returned.
 */
class MessageRepositorySyncTest {

    private val messageDao = mockk<MessageDao>(relaxed = true)
    private val messageSource = mockk<MessageSource>(relaxed = true)
    private val authSource = mockk<AuthSource>()
    private val syncStateDao = InMemorySyncStateDao()

    private lateinit var repository: MessageRepositoryImpl

    @Before
    fun setUp() {
        every { authSource.currentUserId } returns SELF
        coEvery { messageDao.getMessageById(any()) } returns null
        repository = messageRepository(
            messageDao = messageDao,
            messageSource = messageSource,
            authSource = authSource,
            syncStateDao = syncStateDao,
        )
    }

    @Test
    fun `the first sync fetches the whole chat and writes its state row`() = runTest {
        coEvery { messageSource.fetchMessages(CHAT) } returns listOf(incoming("m1", DAY), incoming("m2", 10 * DAY))

        repository.syncAllChatMessages(listOf(CHAT))

        assertEquals(restored(CHAT, cursorMs = 10 * DAY), syncStateDao.rows[CHAT])
        coVerify(exactly = 0) { messageSource.fetchMessagesAfter(any(), any()) }
        coVerify { messageDao.upsertRecord(match { it.id == "m2" }) }
    }

    @Test
    fun `the second sync asks for the tail after the cursor less the overlap`() = runTest {
        coEvery { messageSource.fetchMessages(CHAT) } returns listOf(incoming("m1", 10 * DAY))
        coEvery { messageSource.fetchMessagesAfter(CHAT, any()) } returns listOf(incoming("m2", 11 * DAY))
        repository.syncAllChatMessages(listOf(CHAT))

        repository.syncAllChatMessages(listOf(CHAT))

        coVerify(exactly = 1) { messageSource.fetchMessages(CHAT) }
        coVerify(exactly = 1) { messageSource.fetchMessagesAfter(CHAT, 10 * DAY - MessageSyncPlan.TAIL_OVERLAP_MS) }
        assertEquals(restored(CHAT, cursorMs = 11 * DAY), syncStateDao.rows[CHAT])
        coVerify { messageDao.upsertRecord(match { it.id == "m2" }) }
    }

    @Test
    fun `a chat restored without a message is asked for everything after zero`() = runTest {
        coEvery { messageSource.fetchMessages(CHAT) } returns emptyList()
        repository.syncAllChatMessages(listOf(CHAT))
        assertEquals(restored(CHAT, cursorMs = 0L), syncStateDao.rows[CHAT])

        repository.syncAllChatMessages(listOf(CHAT))

        coVerify(exactly = 1) { messageSource.fetchMessagesAfter(CHAT, 0L) }
    }

    @Test
    fun `a row of an older generation leads to a whole fetch`() = runTest {
        syncStateDao.rows[CHAT] = MessageSyncStateEntity(CHAT, MessageSyncPlan.RESTORE_GENERATION - 1, 10 * DAY)
        coEvery { messageSource.fetchMessages(CHAT) } returns listOf(incoming("m1", 4 * DAY))

        repository.syncAllChatMessages(listOf(CHAT))

        coVerify(exactly = 0) { messageSource.fetchMessagesAfter(any(), any()) }
        assertEquals(restored(CHAT, cursorMs = 4 * DAY), syncStateDao.rows[CHAT])
    }

    @Test
    fun `a restore that throws writes no row`() = runTest {
        coEvery { messageSource.fetchMessages(CHAT) } throws IOException("offline")

        repository.syncAllChatMessages(listOf(CHAT))

        assertNull(syncStateDao.rows[CHAT])
    }

    @Test
    fun `a tail fetch that throws leaves the row as it was`() = runTest {
        syncStateDao.rows[CHAT] = restored(CHAT, cursorMs = 10 * DAY)
        coEvery { messageSource.fetchMessagesAfter(CHAT, any()) } throws IOException("offline")

        repository.syncAllChatMessages(listOf(CHAT))

        assertEquals(restored(CHAT, cursorMs = 10 * DAY), syncStateDao.rows[CHAT])
        coVerify(exactly = 0) { messageSource.fetchMessages(any()) }
    }

    // The state is written after the reconcile. A fetched message that never
    // reached Room must be fetched again, so its chat's cursor may not pass it.
    @Test
    fun `a reconcile that throws leaves the row as it was`() = runTest {
        syncStateDao.rows[CHAT] = restored(CHAT, cursorMs = 10 * DAY)
        coEvery { messageSource.fetchMessagesAfter(CHAT, any()) } returns listOf(incoming("m2", 11 * DAY))
        coEvery { messageDao.getMessageById("m2") } throws IllegalStateException("database is closed")

        repository.syncAllChatMessages(listOf(CHAT))

        assertEquals(restored(CHAT, cursorMs = 10 * DAY), syncStateDao.rows[CHAT])
    }

    // The insert of a message runs under NonCancellable, so a sync cancelled
    // during the last message still leaves the loop normally.
    @Test
    fun `a sync cancelled halfway writes no state`() = runTest {
        coEvery { messageSource.fetchMessages(CHAT) } returns listOf(incoming("m1", 10 * DAY))
        lateinit var sync: Job
        coEvery { messageDao.getMessageById("m1") } coAnswers {
            sync.cancel()
            null
        }

        sync = launch { repository.syncAllChatMessages(listOf(CHAT)) }
        sync.join()

        assertNull(syncStateDao.rows[CHAT])
    }

    // A message carries its sender's clock. A clock that runs weeks ahead must
    // not put the cursor past what everyone else writes meanwhile.
    @Test
    fun `a message from the future does not take the cursor past this phone's clock`() = runTest {
        val farFuture = System.currentTimeMillis() + 30 * DAY
        coEvery { messageSource.fetchMessages(CHAT) } returns listOf(incoming("m1", farFuture))

        repository.syncAllChatMessages(listOf(CHAT))

        assertTrue(syncStateDao.rows.getValue(CHAT).cursorMs <= System.currentTimeMillis())
    }

    @Test
    fun `an empty answer leaves the cursor`() = runTest {
        syncStateDao.rows[CHAT] = restored(CHAT, cursorMs = 10 * DAY)
        coEvery { messageSource.fetchMessagesAfter(CHAT, any()) } returns emptyList()

        repository.syncAllChatMessages(listOf(CHAT))

        assertEquals(restored(CHAT, cursorMs = 10 * DAY), syncStateDao.rows[CHAT])
    }

    // The overlap fetches messages older than the cursor again on every sync.
    @Test
    fun `an answer older than the cursor does not move it back`() = runTest {
        syncStateDao.rows[CHAT] = restored(CHAT, cursorMs = 10 * DAY)
        coEvery { messageSource.fetchMessagesAfter(CHAT, any()) } returns listOf(incoming("m1", 8 * DAY))

        repository.syncAllChatMessages(listOf(CHAT))

        assertEquals(restored(CHAT, cursorMs = 10 * DAY), syncStateDao.rows[CHAT])
    }

    // A queued send is the newest row in Room. A cursor read from Room would pass
    // a message of the other person that lands before it.
    @Test
    fun `a queued own message does not move the cursor`() = runTest {
        syncStateDao.rows[CHAT] = restored(CHAT, cursorMs = 10 * DAY)
        coEvery { messageSource.fetchMessagesAfter(CHAT, any()) } returns listOf(
            incoming("m2", 11 * DAY),
            // This phone's own write, laid over the server's answer by the SDK.
            incoming("own1", 12 * DAY).copy(senderId = SELF, hasPendingWrites = true),
        )

        repository.syncAllChatMessages(listOf(CHAT))

        assertEquals(restored(CHAT, cursorMs = 11 * DAY), syncStateDao.rows[CHAT])
        coVerify(exactly = 0) { messageDao.getMessagesByChatId(any()) }
    }

    @Test
    fun `one chat's failure does not stop the others`() = runTest {
        coEvery { messageSource.fetchMessages("bad") } throws IOException("permission denied")
        coEvery { messageSource.fetchMessages(CHAT) } returns listOf(incoming("m1", 10 * DAY))

        repository.syncAllChatMessages(listOf("bad", CHAT))

        assertNull(syncStateDao.rows["bad"])
        assertEquals(restored(CHAT, cursorMs = 10 * DAY), syncStateDao.rows[CHAT])
    }

    private fun restored(chatId: String, cursorMs: Long) =
        MessageSyncStateEntity(chatId, MessageSyncPlan.RESTORE_GENERATION, cursorMs)

    private fun incoming(id: String, timestamp: Long) = RawMessage(
        id = id,
        chatId = CHAT,
        senderId = "peer1",
        content = "hello",
        ciphertext = null,
        signalType = null,
        type = "TEXT",
        mediaUrl = null,
        mediaThumbnailUrl = null,
        status = "SENT",
        replyToId = null,
        timestamp = timestamp,
        editedAt = null,
    )

    /** The table's rules, as `MessageSyncStateDaoTest` pins them on Room. */
    private class InMemorySyncStateDao : MessageSyncStateDao {
        val rows = ConcurrentHashMap<String, MessageSyncStateEntity>()

        override suspend fun getState(chatId: String) = rows[chatId]

        override suspend fun writeRestored(chatId: String, generation: Int, cursorMs: Long) {
            rows.compute(chatId) { _, row ->
                val kept = row?.takeIf { it.restoreGeneration == generation }?.cursorMs ?: cursorMs
                MessageSyncStateEntity(chatId, generation, maxOf(kept, cursorMs))
            }
        }

        override suspend fun raiseCursor(chatId: String, generation: Int, cursorMs: Long) {
            rows.computeIfPresent(chatId) { _, row ->
                if (row.restoreGeneration == generation) row.copy(cursorMs = maxOf(row.cursorMs, cursorMs)) else row
            }
        }

        override suspend fun deleteAll() = rows.clear()
    }

    private companion object {
        const val SELF = "uid1"
        const val CHAT = "chat1"
        const val DAY = 24L * 60 * 60 * 1000
    }
}
