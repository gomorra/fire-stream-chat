package com.firestream.chat.data.repository

import com.firestream.chat.data.local.dao.MessageDao
import com.firestream.chat.data.local.entity.MessageEntity
import com.firestream.chat.data.remote.source.AuthSource
import com.firestream.chat.data.remote.source.MessageSource
import com.firestream.chat.data.remote.source.RawMessage
import com.firestream.chat.data.remote.source.UserSource
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
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import kotlin.time.Duration.Companion.seconds

/**
 * Regression cover for the snapshot-reconcile loop in
 * [MessageRepositoryImpl.getMessages].
 *
 * A backend snapshot carries the whole message collection, and one arrives for
 * every write anyone makes in the chat — including each delivery/read receipt.
 * Two properties matter and both used to be violated:
 *
 *  1. A reconcile pass must run to completion. It used to be driven by
 *     `collectLatest`, so a newer snapshot cancelled the pass and restarted it
 *     at index 0. Under a burst of receipt writes the tail of the list — the
 *     message that just arrived — could be cancelled before its Room insert on
 *     every single pass, so it never appeared until the burst died down.
 *  2. Messages that did not change must not be re-reconciled. Every pass used
 *     to hit Room once per message in the chat, so the cost of one incoming
 *     message grew with the length of the conversation.
 */
class MessageRepositorySnapshotTest {

    private val messageDao = mockk<MessageDao>(relaxed = true)
    private val messageSource = mockk<MessageSource>()
    private val authSource = mockk<AuthSource>()
    private val chatRepository = mockk<dagger.Lazy<ChatRepository>>()
    private val listRepository = mockk<dagger.Lazy<ListRepository>>()
    private val userSource = mockk<UserSource>(relaxed = true)

    private lateinit var repository: MessageRepositoryImpl

    /** Room work is not free; give it a cost so an interrupted pass is observable. */
    private val daoReadCostMs = 20L

    @Before
    fun setUp() {
        every { authSource.currentUserId } returns SELF
        coEvery { userSource.getBlockedUserIds(any()) } returns emptySet()
        coEvery { messageDao.failStuckSendingMessagesForChat(any()) } returns 0
        coEvery { messageDao.getMessagesWithoutLocalMediaForChat(any()) } returns emptyList()
        every { messageDao.getMessagesByChatId(any()) } returns flowOf(emptyList())
        coEvery { messageDao.getMessageById(any()) } coAnswers {
            delay(daoReadCostMs)
            null
        }
        coEvery { messageDao.insertMessage(any()) } just Runs
        repository = messageRepository(
            messageDao = messageDao,
            messageSource = messageSource,
            authSource = authSource,
            chatRepository = chatRepository,
            listRepository = listRepository,
            userSource = userSource,
        )
    }

    @Test
    fun `reconciles the whole snapshot while further snapshots keep arriving`() = runTest {
        val snapshot = (1..MESSAGE_COUNT).map { incoming("m$it", timestamp = it.toLong()) }
        // One pass costs MESSAGE_COUNT * daoReadCostMs = 120ms; snapshots land
        // every 30ms, exactly the receipt-burst shape that used to starve the tail.
        every { messageSource.observeMessages(CHAT) } returns flow {
            while (true) {
                emit(snapshot)
                delay(30)
            }
        }

        val job = launch { repository.getMessages(CHAT).collect { } }
        advanceTimeBy(2.seconds)

        // The last message in the snapshot must reach Room, not just the first few.
        coVerify(atLeast = 1) { messageDao.insertMessage(match { it.id == "m$MESSAGE_COUNT" }) }
        job.cancel()
    }

    @Test
    fun `re-emitting an unchanged snapshot does not re-read Room`() = runTest {
        val snapshot = (1..MESSAGE_COUNT).map { incoming("m$it", timestamp = it.toLong()) }
        every { messageSource.observeMessages(CHAT) } returns flow {
            emit(snapshot)
            // Far enough apart that the first pass has finished — the second pass
            // must still be a no-op because nothing in the snapshot changed.
            delay(1_000)
            emit(snapshot)
        }

        val job = launch { repository.getMessages(CHAT).collect { } }
        advanceUntilIdle()

        (1..MESSAGE_COUNT).forEach { i ->
            coVerify(exactly = 1) { messageDao.getMessageById("m$i") }
            coVerify(exactly = 1) { messageDao.insertMessage(match { it.id == "m$i" }) }
        }
        job.cancel()
    }

    // The outbox columns are not on Message, so a send's attempt-count and
    // ciphertext writes re-emit an identical list from Room. Passing it on would
    // re-run the chat's whole message pipeline for nothing.
    @Test
    fun `a Room write that changes no field of Message is not emitted again`() = runTest {
        val row = MessageEntity.fromDomain(
            Message(
                id = "own1",
                chatId = CHAT,
                senderId = SELF,
                content = "hi",
                type = MessageType.TEXT,
                status = MessageStatus.SENDING,
                timestamp = 1L,
            )
        )
        every { messageSource.observeMessages(CHAT) } returns flowOf(emptyList())
        every { messageDao.getMessagesByChatId(CHAT) } returns flow {
            emit(listOf(row))
            // Real milliseconds — this runs on Dispatchers.Default, upstream of
            // flowOn — so conflate() cannot be what drops the middle emission.
            delay(50)
            emit(listOf(row.copy(outboxAttempts = 1, outboxCiphertext = "cipher-1", outboxSignalType = 3)))
            delay(50)
            emit(listOf(row.copy(status = MessageStatus.SENT.name)))
        }

        val emitted = repository.getMessages(CHAT).toList()

        assertEquals(listOf(MessageStatus.SENDING, MessageStatus.SENT), emitted.map { it.single().status })
    }

    @Test
    fun `a changed message in a re-emitted snapshot is reconciled again`() = runTest {
        val first = (1..MESSAGE_COUNT).map { incoming("m$it", timestamp = it.toLong()) }
        val edited = first.map { if (it.id == "m2") it.copy(content = "edited", editedAt = 99L) else it }
        every { messageSource.observeMessages(CHAT) } returns flow {
            emit(first)
            delay(1_000)
            emit(edited)
        }

        val job = launch { repository.getMessages(CHAT).collect { } }
        advanceUntilIdle()

        coVerify(exactly = 2) { messageDao.getMessageById("m2") }
        coVerify(exactly = 1) { messageDao.getMessageById("m3") }
        job.cancel()
    }

    @Test
    fun `the block list is fetched once per burst, not once per snapshot`() = runTest {
        val snapshot = listOf(incoming("m1", timestamp = 1L))
        every { messageSource.observeMessages(CHAT) } returns flow {
            repeat(5) {
                emit(snapshot)
                delay(100)
            }
        }

        val job = launch { repository.getMessages(CHAT).collect { } }
        advanceUntilIdle()

        coVerify(exactly = 1) { userSource.getBlockedUserIds(SELF) }
        job.cancel()
    }

    // ── Own-message echoes ──────────────────────────────────────────────────
    //
    // The message id is set by the client, so Firestore's latency-compensated
    // echo of our own write carries the row's id — and the payload's
    // `status = SENT` — before anything has reached the server. Only an
    // acknowledged snapshot may move the local status.

    @Test
    fun `a pending echo of our own write leaves the local SENDING row alone`() = runTest {
        coEvery { messageDao.getMessageById("own1") } returns ownRow("own1", MessageStatus.SENDING)
        every { messageSource.observeMessages(CHAT) } returns
            flowOf(listOf(ownEcho("own1", hasPendingWrites = true)))

        val job = launch { repository.getMessages(CHAT).collect { } }
        advanceUntilIdle()

        coVerify(exactly = 0) { messageDao.updateMessageStatus("own1", any()) }
        job.cancel()
    }

    @Test
    fun `an acknowledged echo moves a FAILED row to SENT`() = runTest {
        // A send whose await died (user left the chat) but whose write landed:
        // the row was flipped FAILED locally, yet the message *is* on the server.
        coEvery { messageDao.getMessageById("own1") } returns ownRow("own1", MessageStatus.FAILED)
        every { messageSource.observeMessages(CHAT) } returns
            flowOf(listOf(ownEcho("own1", hasPendingWrites = false)))

        val job = launch { repository.getMessages(CHAT).collect { } }
        advanceUntilIdle()

        coVerify(exactly = 1) { messageDao.updateMessageStatus("own1", MessageStatus.SENT.name) }
        job.cancel()
    }

    private fun ownRow(id: String, status: MessageStatus) = MessageEntity.fromDomain(
        Message(
            id = id,
            chatId = CHAT,
            senderId = SELF,
            content = "hi",
            type = MessageType.TEXT,
            status = status,
            timestamp = 1L,
        )
    )

    private fun ownEcho(id: String, hasPendingWrites: Boolean) =
        incoming(id, timestamp = 1L).copy(senderId = SELF, hasPendingWrites = hasPendingWrites)

    private fun incoming(id: String, timestamp: Long) = RawMessage(
        id = id,
        chatId = CHAT,
        senderId = PEER,
        content = "hello $id",
        ciphertext = null,
        signalType = null,
        type = MessageType.TEXT.name,
        mediaUrl = null,
        mediaThumbnailUrl = null,
        status = MessageStatus.SENT.name,
        replyToId = null,
        timestamp = timestamp,
        editedAt = null,
    )

    private companion object {
        const val CHAT = "chat1"
        const val SELF = "uid1"
        const val PEER = "peer1"
        const val MESSAGE_COUNT = 6
    }
}
