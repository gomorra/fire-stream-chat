package com.firestream.chat.data.worker

import android.content.Context
import androidx.concurrent.futures.CallbackToFutureAdapter
import androidx.test.core.app.ApplicationProvider
import androidx.work.ForegroundInfo
import androidx.work.ListenableWorker
import androidx.work.ListenableWorker.Result
import androidx.work.WorkerFactory
import androidx.work.WorkerParameters
import androidx.work.testing.TestListenableWorkerBuilder
import androidx.work.workDataOf
import com.firestream.chat.data.local.dao.MessageDao
import com.firestream.chat.data.local.entity.MessageEntity
import com.firestream.chat.data.outbox.BlockCheck
import com.firestream.chat.data.outbox.OutboxSender
import com.firestream.chat.data.outbox.SendTarget
import com.firestream.chat.data.remote.source.SendErrorClassifier
import com.firestream.chat.data.remote.source.SendFailure
import com.firestream.chat.data.remote.source.UserSource
import com.firestream.chat.domain.model.Message
import com.firestream.chat.domain.model.MessageStatus
import com.firestream.chat.domain.model.MessageType
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.IOException

/**
 * The worker's policy around one [OutboxSender] run: what it refuses to run,
 * how it sorts a failure, when it gives up. What the run itself does to the row
 * is `OutboxSenderTest`'s.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29], manifest = Config.NONE, application = android.app.Application::class)
class OutboxWorkerTest {

    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val messageDao = mockk<MessageDao>(relaxed = true)
    private val outboxSender = mockk<OutboxSender>()
    private val userSource = mockk<UserSource>()

    /** The neutral rules only — no backend here. */
    private val classifier = object : SendErrorClassifier {
        override fun classifyBackendError(error: Throwable): SendFailure? = null
    }

    private var promotedTo: ForegroundInfo? = null

    @Before
    fun setUp() {
        coEvery { messageDao.getMessageById("msg1") } returns queued()
        coEvery { userSource.isUserBlocked(any(), any()) } returns false
        coEvery { outboxSender.send("msg1") } returns sent()
    }

    private fun worker(): OutboxWorker =
        TestListenableWorkerBuilder<OutboxWorker>(context)
            .setInputData(workDataOf(OutboxWorker.KEY_MESSAGE_ID to "msg1"))
            .setWorkerFactory(object : WorkerFactory() {
                override fun createWorker(
                    appContext: Context,
                    workerClassName: String,
                    workerParameters: WorkerParameters,
                ): ListenableWorker = OutboxWorker(appContext, workerParameters, messageDao, outboxSender, BlockCheck(userSource), classifier)
            })
            .setForegroundUpdater { _, _, info ->
                promotedTo = info
                CallbackToFutureAdapter.getFuture<Void> { it.set(null); "promoted" }
            }
            .build()

    private fun queued(
        attempts: Int = 0,
        type: MessageType = MessageType.TEXT,
        target: SendTarget = SendTarget.NoPeer,
        status: MessageStatus = MessageStatus.SENDING,
        deletedAt: Long? = null,
        mediaUrl: String? = null,
    ) = MessageEntity.outbox(
        Message(
            id = "msg1",
            chatId = "chat1",
            senderId = "uid1",
            content = "hi",
            type = type,
            status = status,
            timestamp = 1_000L,
            deletedAt = deletedAt,
            mediaUrl = mediaUrl,
            localUri = if (type == MessageType.TEXT) null else "/data/outbox/msg1.jpg",
        ),
        target,
    ).copy(outboxAttempts = attempts)

    private fun sent() = queued().toDomain().copy(status = MessageStatus.SENT)

    private fun assertLeftSending() {
        coVerify(exactly = 0) { messageDao.failQueued(any()) }
        coVerify(exactly = 0) { messageDao.updateMessageStatus(any(), any()) }
    }

    // Conditional on the row still being queued: an echo may have healed it meanwhile.
    private fun assertMarkedFailed() = coVerify(exactly = 1) { messageDao.failQueued("msg1") }

    // ── outcomes ────────────────────────────────────────────────────────────

    @Test
    fun `a run that completes succeeds`() = runTest {
        assertEquals(Result.success(), worker().doWork())

        coVerify(exactly = 1) { outboxSender.send("msg1") }
        assertLeftSending()
    }

    @Test
    fun `a transient failure asks for a retry and leaves the row SENDING`() = runTest {
        coEvery { outboxSender.send("msg1") } throws IOException("socket closed")

        assertEquals(Result.retry(), worker().doWork())

        assertLeftSending()
    }

    @Test
    fun `a lost acknowledgement is transient`() = runTest {
        coEvery { outboxSender.send("msg1") } throws IOException("Message msg1 not acknowledged within 30000 ms — offline?")

        assertEquals(Result.retry(), worker().doWork())

        assertLeftSending()
    }

    @Test
    fun `a permanent failure marks the row FAILED`() = runTest {
        coEvery { outboxSender.send("msg1") } throws IllegalStateException("Cannot send IMAGE message msg1: local file is missing")

        assertEquals(Result.failure(), worker().doWork())

        assertMarkedFailed()
    }

    // ── the attempt budget ──────────────────────────────────────────────────

    @Test
    fun `the eighth executed attempt that fails transiently gives up instead of retrying`() = runTest {
        // The row before the run, then after OutboxSender counted this attempt.
        coEvery { messageDao.getMessageById("msg1") } returnsMany listOf(queued(attempts = 7), queued(attempts = 8))
        coEvery { outboxSender.send("msg1") } throws IOException("still no network")

        assertEquals(Result.failure(), worker().doWork())

        coVerify(exactly = 1) { outboxSender.send("msg1") }
        assertMarkedFailed()
    }

    @Test
    fun `the seventh attempt that fails transiently is still retried`() = runTest {
        coEvery { messageDao.getMessageById("msg1") } returnsMany listOf(queued(attempts = 6), queued(attempts = 7))
        coEvery { outboxSender.send("msg1") } throws IOException("still no network")

        assertEquals(Result.retry(), worker().doWork())

        assertLeftSending()
    }

    @Test
    fun `a row already past the budget is failed without running`() = runTest {
        coEvery { messageDao.getMessageById("msg1") } returns queued(attempts = OutboxWorker.MAX_ATTEMPTS)

        assertEquals(Result.failure(), worker().doWork())

        coVerify(exactly = 0) { outboxSender.send(any()) }
        assertMarkedFailed()
    }

    // ── the authoritative block check ───────────────────────────────────────

    @Test
    fun `a peer who is blocked fails the send for good before anything runs`() = runTest {
        coEvery { messageDao.getMessageById("msg1") } returns queued(target = SendTarget.Peer("peer1"))
        coEvery { userSource.isUserBlocked("uid1", "peer1") } returns true

        assertEquals(Result.failure(), worker().doWork())

        coVerify(exactly = 0) { outboxSender.send(any()) }
        assertMarkedFailed()
    }

    @Test
    fun `a block check the backend cannot answer is retried`() = runTest {
        coEvery { messageDao.getMessageById("msg1") } returns queued(target = SendTarget.Peer("peer1"))
        coEvery { userSource.isUserBlocked("uid1", "peer1") } throws IOException("no route")

        assertEquals(Result.retry(), worker().doWork())

        coVerify(exactly = 0) { outboxSender.send(any()) }
        assertLeftSending()
    }

    @Test
    fun `a group message asks no block list`() = runTest {
        assertEquals(Result.success(), worker().doWork())

        coVerify(exactly = 0) { userSource.isUserBlocked(any(), any()) }
    }

    // ── rows that are not the worker's to send ──────────────────────────────

    @Test
    fun `a row the backend acknowledged meanwhile is left alone`() = runTest {
        coEvery { messageDao.getMessageById("msg1") } returns queued(status = MessageStatus.SENT)

        assertEquals(Result.success(), worker().doWork())

        coVerify(exactly = 0) { outboxSender.send(any()) }
        assertLeftSending()
    }

    @Test
    fun `a row already failed is left alone`() = runTest {
        coEvery { messageDao.getMessageById("msg1") } returns queued(status = MessageStatus.FAILED)

        assertEquals(Result.success(), worker().doWork())

        coVerify(exactly = 0) { outboxSender.send(any()) }
    }

    // A given-up row that is then deleted is a tombstone job without any status flip.
    @Test
    fun `a deleted row that had been failed still runs its tombstone`() = runTest {
        coEvery { messageDao.getMessageById("msg1") } returns
            queued(attempts = 8, status = MessageStatus.FAILED, deletedAt = 5_000L)

        assertEquals(Result.success(), worker().doWork())

        coVerify(exactly = 1) { outboxSender.send("msg1") }
        assertLeftSending()
    }

    @Test
    fun `a row that is gone is nothing to do`() = runTest {
        coEvery { messageDao.getMessageById("msg1") } returns null

        assertEquals(Result.success(), worker().doWork())

        coVerify(exactly = 0) { outboxSender.send(any()) }
    }

    // ── a message deleted while queued ──────────────────────────────────────

    @Test
    fun `a deleted row runs its tombstone regardless of the budget and without a block check`() = runTest {
        coEvery { messageDao.getMessageById("msg1") } returns
            queued(attempts = 9, target = SendTarget.Peer("peer1"), deletedAt = 5_000L)

        assertEquals(Result.success(), worker().doWork())

        coVerify(exactly = 1) { outboxSender.send("msg1") }
        coVerify(exactly = 0) { userSource.isUserBlocked(any(), any()) }
        assertLeftSending()
    }

    @Test
    fun `a tombstone the backend did not acknowledge is retried past the budget`() = runTest {
        coEvery { messageDao.getMessageById("msg1") } returns queued(attempts = 9, deletedAt = 5_000L)
        coEvery { outboxSender.send("msg1") } throws IOException("Tombstone of message msg1 not acknowledged")

        assertEquals(Result.retry(), worker().doWork())

        assertLeftSending()
    }

    // ── the foreground ──────────────────────────────────────────────────────

    @Test
    fun `an upload promotes the run to the foreground, a text send does not`() = runTest {
        coEvery { messageDao.getMessageById("msg1") } returns queued(type = MessageType.IMAGE)
        worker().doWork()
        assertNotNull("an upload wants the foreground", promotedTo)

        promotedTo = null
        coEvery { messageDao.getMessageById("msg1") } returns queued(type = MessageType.TEXT)
        worker().doWork()
        assertNull(promotedTo)
    }

    @Test
    fun `a forward whose media is already uploaded does not ask for the foreground`() = runTest {
        coEvery { messageDao.getMessageById("msg1") } returns
            queued(type = MessageType.IMAGE, mediaUrl = "https://storage.example/src")

        worker().doWork()

        assertNull(promotedTo)
    }

    @Test
    fun `the foreground info an expedited run needs is available`() = runTest {
        val info = worker().getForegroundInfo()

        assertNotNull(info.notification)
    }
}
