package com.firestream.chat.data.outbox

import androidx.work.BackoffPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequest
import androidx.work.WorkManager
import com.firestream.chat.data.local.dao.MessageDao
import com.firestream.chat.data.local.entity.MessageEntity
import com.firestream.chat.data.remote.source.AuthSource
import com.firestream.chat.data.worker.OutboxWorker
import com.firestream.chat.domain.model.Message
import com.firestream.chat.domain.model.MessageStatus
import com.firestream.chat.domain.model.MessageType
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.util.concurrent.TimeUnit
import javax.inject.Provider

/**
 * What the scheduler asks WorkManager for. Runs on the plain JVM, where
 * `Build.VERSION.SDK_INT` is 0 — the "below API 31" branch of the expedited rule;
 * the rule itself is a pure function, pinned for both branches below.
 */
class OutboxSchedulerTest {

    private val workManager = mockk<WorkManager>(relaxed = true)
    private val messageDao = mockk<MessageDao>(relaxed = true)
    private val authSource = mockk<AuthSource>()
    private val outboxFiles = mockk<OutboxFiles>(relaxed = true)

    private val scheduler = OutboxScheduler(Provider { workManager }, messageDao, authSource, outboxFiles)

    private val name = slot<String>()
    private val policy = slot<ExistingWorkPolicy>()
    private val request = slot<OneTimeWorkRequest>()

    @Before
    fun setUp() {
        every { authSource.currentUserId } returns "uid1"
        every { workManager.enqueueUniqueWork(capture(name), capture(policy), capture(request)) } returns mockk()
    }

    private fun row(
        id: String,
        type: MessageType = MessageType.TEXT,
        deletedAt: Long? = null,
    ) = MessageEntity.outbox(
        Message(
            id = id,
            chatId = "chat1",
            senderId = "uid1",
            content = "",
            type = type,
            status = MessageStatus.SENDING,
            timestamp = 1_000L,
            deletedAt = deletedAt,
        ),
        SendTarget.NoPeer,
    )

    @Test
    fun `enqueue names the work by the message id, keeps existing work and hands the id to the worker`() {
        scheduler.enqueue("msg1", uploads = false)

        assertEquals("outbox-msg1", name.captured)
        assertEquals(ExistingWorkPolicy.KEEP, policy.captured)
        val spec = request.captured.workSpec
        assertEquals(OutboxWorker::class.java.name, spec.workerClassName)
        assertEquals("msg1", spec.input.getString(OutboxWorker.KEY_MESSAGE_ID))
        assertEquals(NetworkType.CONNECTED, spec.constraints.requiredNetworkType)
        assertEquals(BackoffPolicy.EXPONENTIAL, spec.backoffPolicy)
        assertEquals(TimeUnit.SECONDS.toMillis(OutboxScheduler.INITIAL_BACKOFF_SECONDS), spec.backoffDelayDuration)
        assertTrue(OutboxScheduler.TAG_OUTBOX in request.captured.tags)
    }

    @Test
    fun `retryNow replaces the message's existing work`() {
        scheduler.retryNow("msg1", uploads = false)

        assertEquals("outbox-msg1", name.captured)
        assertEquals(ExistingWorkPolicy.REPLACE, policy.captured)
    }

    // Below API 31 expedited work is a foreground service with a notification;
    // only a send that uploads — and wants the foreground anyway — asks for it.
    @Test
    fun `below API 31 a send that does not upload is ordinary work and an upload is expedited`() {
        scheduler.enqueue("msg1", uploads = false)
        assertFalse(request.captured.workSpec.expedited)

        scheduler.enqueue("msg1", uploads = true)
        assertTrue(request.captured.workSpec.expedited)
    }

    @Test
    fun `from API 31 every send is expedited, since an expedited job shows no notification there`() {
        assertTrue(OutboxScheduler.runsExpedited(sdkInt = 31, uploads = false))
        assertTrue(OutboxScheduler.runsExpedited(sdkInt = 34, uploads = true))
        assertFalse(OutboxScheduler.runsExpedited(sdkInt = 30, uploads = false))
        assertTrue(OutboxScheduler.runsExpedited(sdkInt = 29, uploads = true))
    }

    @Test
    fun `requeueAll queues the current user's sendable SENDING rows, fails the others and sweeps stray files`() = runTest {
        val sendable = OutboxSender.SENDABLE_TYPES.map { it.name }
        coEvery { messageDao.getQueuedMessages("uid1", sendable) } returns
            listOf(row("a"), row("b", type = MessageType.IMAGE), row("c", deletedAt = 5_000L))
        coEvery { messageDao.failQueuedOfOtherTypes("uid1", sendable) } returns 1
        val names = mutableListOf<String>()
        val requests = mutableListOf<OneTimeWorkRequest>()
        every { workManager.enqueueUniqueWork(capture(names), any(), capture(requests)) } returns mockk()

        scheduler.requeueAll()

        assertEquals(listOf("outbox-a", "outbox-b", "outbox-c"), names)
        // The image still uploads; the text and the tombstone do not.
        assertEquals(listOf(false, true, false), requests.map { it.workSpec.expedited })
        coVerify(exactly = 1) { messageDao.failQueuedOfOtherTypes("uid1", sendable) }
        coVerify(exactly = 1) { outboxFiles.retainOnly(setOf("a", "b", "c")) }
    }

    @Test
    fun `requeueAll does nothing while nobody is signed in`() = runTest {
        every { authSource.currentUserId } returns null

        scheduler.requeueAll()

        coVerify(exactly = 0) { messageDao.getQueuedMessages(any(), any()) }
        verify(exactly = 0) { workManager.enqueueUniqueWork(any(), any(), any<OneTimeWorkRequest>()) }
    }
}
