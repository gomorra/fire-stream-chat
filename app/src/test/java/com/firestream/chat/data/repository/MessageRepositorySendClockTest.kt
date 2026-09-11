package com.firestream.chat.data.repository

import com.firestream.chat.data.local.dao.MessageDao
import com.firestream.chat.data.local.entity.MessageEntity
import com.firestream.chat.data.outbox.SendClock
import com.firestream.chat.data.remote.source.AuthSource
import io.mockk.Runs
import io.mockk.coEvery
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test

/**
 * Every send stamps its row from one [SendClock], so messages composed within
 * one millisecond still get distinct, increasing timestamps. Regression: a
 * multi-attachment send stamped each row with `System.currentTimeMillis()`, so
 * rows could share a timestamp, and their order — in the chat and in the
 * newer-only chat preview — fell to whichever send finished last.
 */
class MessageRepositorySendClockTest {

    private val messageDao = mockk<MessageDao>(relaxed = true)
    private val authSource = mockk<AuthSource>()
    private val inserted = mutableListOf<MessageEntity>()

    /** The wall clock the [SendClock] reads; frozen unless a test moves it. */
    private var now = 5_000L

    private lateinit var repository: MessageRepositoryImpl

    @Before
    fun setUp() {
        every { authSource.currentUserId } returns "uid1"
        coEvery { messageDao.insertMessage(capture(inserted)) } just Runs
        repository = messageRepository(
            messageDao = messageDao,
            authSource = authSource,
            sendClock = SendClock(nowMs = { now }),
        )
    }

    @Test
    fun `a batch sent within one millisecond gets strictly increasing timestamps`() = runTest {
        repeat(3) {
            repository.sendMediaMessage("chat1", "content://docs/$it.pdf", "application/pdf", "", "", null)
        }
        repository.sendMessage("chat1", "all three attached", "")

        assertEquals(listOf(5_000L, 5_001L, 5_002L, 5_003L), inserted.map { it.timestamp })
    }

    @Test
    fun `a wall clock set back does not stamp the next send before the last one`() = runTest {
        repository.sendMessage("chat1", "first", "")
        now = 4_000L
        repository.sendMessage("chat1", "second", "")

        assertEquals(listOf(5_000L, 5_001L), inserted.map { it.timestamp })
    }
}
