package com.firestream.chat.data.repository

import android.net.ConnectivityManager
import com.firestream.chat.data.crypto.SignalManager
import com.firestream.chat.data.local.AutoDownloadOption
import com.firestream.chat.data.local.PreferencesDataStore
import com.firestream.chat.data.local.dao.MessageDao
import com.firestream.chat.data.local.entity.MessageEntity
import com.firestream.chat.data.local.entity.MessageRecord
import com.firestream.chat.data.remote.fcm.ActiveChatTracker
import com.firestream.chat.data.remote.source.AuthSource
import com.firestream.chat.data.remote.source.MessageSource
import com.firestream.chat.data.remote.source.RawMessage
import com.firestream.chat.data.remote.source.UserSource
import com.firestream.chat.data.util.MediaFileManager
import com.firestream.chat.data.worker.MediaBackfillScheduler
import com.firestream.chat.domain.model.Message
import com.firestream.chat.domain.model.MessageStatus
import com.firestream.chat.domain.model.MessageType
import io.mockk.Runs
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.Before
import org.junit.Test
import org.signal.libsignal.protocol.message.CiphertextMessage
import java.io.File
import java.io.IOException

/**
 * A push notification names one message; [MessageRepositoryImpl.reconcileFromPush]
 * pulls that message into Room through the listener's reconcile, so a photo
 * received while the app was closed is on the phone before the chat is opened
 * (offline outbox step 8, half 1). The download runs on the repository's own IO
 * scope, so the verifications that wait on it carry a timeout.
 */
class MessageRepositoryPushReconcileTest {

    private val messageDao = mockk<MessageDao>(relaxed = true)
    private val messageSource = mockk<MessageSource>()
    private val authSource = mockk<AuthSource>()
    private val signalManager = mockk<SignalManager>(relaxed = true)
    private val mediaFileManager = mockk<MediaFileManager>()
    private val preferencesDataStore = mockk<PreferencesDataStore>()
    private val userSource = mockk<UserSource>()
    private val activeChatTracker = ActiveChatTracker()
    private val mediaBackfillScheduler = mockk<MediaBackfillScheduler>(relaxed = true)
    /** No active network at all — so "Wi-Fi only" reads as off Wi-Fi. */
    private val connectivityManager = mockk<ConnectivityManager> { every { activeNetwork } returns null }

    private lateinit var repository: MessageRepositoryImpl

    @Before
    fun setUp() {
        every { authSource.currentUserId } returns SELF
        coEvery { userSource.getBlockedUserIds(SELF) } returns emptySet()
        coEvery { messageDao.getMessageById(any()) } returns null
        every { preferencesDataStore.autoDownloadFlow } returns flowOf(AutoDownloadOption.ALWAYS)
        repository = messageRepository(
            messageDao = messageDao,
            messageSource = messageSource,
            authSource = authSource,
            signalManager = signalManager,
            mediaFileManager = mediaFileManager,
            preferencesDataStore = preferencesDataStore,
            userSource = userSource,
            connectivityManager = connectivityManager,
            activeChatTracker = activeChatTracker,
            mediaBackfillScheduler = mediaBackfillScheduler,
        )
    }

    @Test
    fun `a pushed photo is written to Room and downloaded without the chat being open`() = runTest {
        coEvery { messageSource.fetchMessage(CHAT, "m1") } returns photo("m1")
        val saved = File("/storage/emulated/0/Pictures/FireStream Images/m1.jpg")
        coEvery { mediaFileManager.downloadAndSave(CHAT, "m1", photoUrl("m1")) } returns saved

        repository.reconcileFromPush(CHAT, "m1")

        coVerify(exactly = 1) { messageDao.upsertRecord(match { it.id == "m1" && it.mediaUrl == photoUrl("m1") }) }
        coVerify(timeout = 2_000) { mediaFileManager.downloadAndSave(CHAT, "m1", photoUrl("m1")) }
        coVerify(timeout = 2_000) { messageDao.updateLocalUri("m1", saved.absolutePath) }
    }

    @Test
    fun `the open chat is left to its listener`() = runTest {
        activeChatTracker.setActive(CHAT)

        repository.reconcileFromPush(CHAT, "m1")

        coVerify(exactly = 0) { messageSource.fetchMessage(any(), any()) }
        coVerify(exactly = 0) { messageDao.upsertRecord(any()) }
    }

    @Test
    fun `another open chat does not stop the reconcile`() = runTest {
        activeChatTracker.setActive("other-chat")
        coEvery { messageSource.fetchMessage(CHAT, "m1") } returns photo("m1").copy(mediaUrl = null, type = "TEXT")

        repository.reconcileFromPush(CHAT, "m1")

        coVerify(exactly = 1) { messageDao.upsertRecord(match { it.id == "m1" }) }
    }

    @Test
    fun `a message from a blocked sender is not written`() = runTest {
        coEvery { userSource.getBlockedUserIds(SELF) } returns setOf(PEER)
        coEvery { messageSource.fetchMessage(CHAT, "m1") } returns photo("m1")

        repository.reconcileFromPush(CHAT, "m1")

        coVerify(exactly = 0) { messageDao.upsertRecord(any()) }
    }

    @Test
    fun `a message the backend no longer holds writes nothing`() = runTest {
        coEvery { messageSource.fetchMessage(CHAT, "gone") } returns null

        repository.reconcileFromPush(CHAT, "gone")

        coVerify(exactly = 0) { messageDao.upsertRecord(any()) }
    }

    @Test
    fun `a fetch that keeps failing is swallowed, not thrown at the push handler`() = runTest {
        coEvery { messageSource.fetchMessage(CHAT, "m1") } throws IOException("no network yet")

        repository.reconcileFromPush(CHAT, "m1")

        coVerify(exactly = 3) { messageSource.fetchMessage(CHAT, "m1") }
        coVerify(exactly = 0) { messageDao.upsertRecord(any()) }
    }

    // FCM's socket comes back before Firestore's: the first read on the
    // reconnect instant fails while the connection is seconds away.
    @Test
    fun `a fetch that fails on the reconnect instant is tried again`() = runTest {
        coEvery { messageSource.fetchMessage(CHAT, "m1") } throws IOException("backend not connected yet") andThen
            photo("m1").copy(mediaUrl = null, type = "TEXT")

        repository.reconcileFromPush(CHAT, "m1")

        coVerify(exactly = 2) { messageSource.fetchMessage(CHAT, "m1") }
        coVerify(exactly = 1) { messageDao.upsertRecord(match { it.id == "m1" }) }
    }

    @Test
    fun `a missing document is not tried again`() = runTest {
        coEvery { messageSource.fetchMessage(CHAT, "gone") } returns null

        repository.reconcileFromPush(CHAT, "gone")

        coVerify(exactly = 1) { messageSource.fetchMessage(CHAT, "gone") }
    }

    // The push reconcile goes through the listener's reconcile, so it carries
    // the own-echo guard: a get() of an own queued send merges this client's
    // pending write, with the payload's SENT, and must not move the row.
    @Test
    fun `a pending echo of an own queued send does not move its status`() = runTest {
        coEvery { messageDao.getMessageById("own1") } returns ownRow("own1", MessageStatus.SENDING)
        coEvery { messageSource.fetchMessage(CHAT, "own1") } returns
            photo("own1").copy(senderId = SELF, hasPendingWrites = true)

        repository.reconcileFromPush(CHAT, "own1")

        coVerify(exactly = 0) { messageDao.acknowledge("own1", any()) }
        coVerify(exactly = 0) { messageDao.updateMessageStatus("own1", any()) }
        coVerify(exactly = 0) { messageDao.upsertRecord(any()) }
    }

    @Test
    fun `an encrypted message is decrypted under the initialised Signal store`() = runTest {
        coEvery { messageSource.fetchMessage(CHAT, "m1") } returns photo("m1").copy(
            content = null, ciphertext = "cipher-1", signalType = CiphertextMessage.WHISPER_TYPE,
            mediaUrl = null, type = "TEXT",
        )
        coEvery { signalManager.decrypt(PEER, any()) } returns "hello"

        repository.reconcileFromPush(CHAT, "m1")

        coVerify(exactly = 1) { signalManager.ensureInitialized() }
        coVerify(exactly = 1) { messageDao.upsertRecord(match { it.id == "m1" && it.content == "hello" }) }
    }

    @Test
    fun `a plaintext message does not touch the Signal store`() = runTest {
        coEvery { messageSource.fetchMessage(CHAT, "m1") } returns photo("m1").copy(mediaUrl = null, type = "TEXT")

        repository.reconcileFromPush(CHAT, "m1")

        coVerify(exactly = 0) { signalManager.ensureInitialized() }
    }

    // ── Half 2: a failed download is handed to a network-constrained run ─────

    @Test
    fun `a failed auto-download queues the retry`() = runTest {
        coEvery { messageSource.fetchMessage(CHAT, "m1") } returns photo("m1")
        coEvery { mediaFileManager.downloadAndSave(CHAT, "m1", photoUrl("m1")) } throws IOException("network went away")

        repository.reconcileFromPush(CHAT, "m1")

        coVerify(timeout = 2_000, exactly = 1) { mediaBackfillScheduler.retryDownloads() }
        coVerify(exactly = 0) { messageDao.updateLocalUri("m1", any()) }
    }

    @Test
    fun `a successful auto-download queues nothing`() = runTest {
        coEvery { messageSource.fetchMessage(CHAT, "m1") } returns photo("m1")
        coEvery { mediaFileManager.downloadAndSave(CHAT, "m1", photoUrl("m1")) } returns File("/tmp/m1.jpg")

        repository.reconcileFromPush(CHAT, "m1")

        coVerify(timeout = 2_000) { messageDao.updateLocalUri("m1", any()) }
        coVerify(exactly = 0) { mediaBackfillScheduler.retryDownloads() }
    }

    // "Wi-Fi only" off Wi-Fi is the case the UNMETERED constraint exists for:
    // declining the download now must still leave a run waiting for Wi-Fi.
    @Test
    fun `Wi-Fi only on mobile data queues the retry instead of downloading`() = runTest {
        every { preferencesDataStore.autoDownloadFlow } returns flowOf(AutoDownloadOption.WIFI_ONLY)
        coEvery { messageSource.fetchMessage(CHAT, "m1") } returns photo("m1")

        repository.reconcileFromPush(CHAT, "m1")

        coVerify(timeout = 2_000, exactly = 1) { mediaBackfillScheduler.retryDownloads() }
        coVerify(exactly = 0) { mediaFileManager.downloadAndSave(any(), any(), any()) }
    }

    @Test
    fun `opening a chat with missing media on Wi-Fi only off Wi-Fi queues the retry`() = runTest {
        every { preferencesDataStore.autoDownloadFlow } returns flowOf(AutoDownloadOption.WIFI_ONLY)
        coEvery { messageDao.getMessagesWithoutLocalMediaForChat(CHAT) } returns listOf(pendingRow("a"))
        every { messageDao.getMessagesByChatId(CHAT) } returns flowOf(emptyList())
        every { messageSource.observeMessages(CHAT) } returns flowOf(emptyList())

        repository.getMessages(CHAT).first()

        coVerify(timeout = 2_000, exactly = 1) { mediaBackfillScheduler.retryDownloads() }
        coVerify(exactly = 0) { mediaFileManager.downloadAndSave(any(), any(), any()) }
    }

    @Test
    fun `opening a chat with nothing missing queues no retry`() = runTest {
        every { preferencesDataStore.autoDownloadFlow } returns flowOf(AutoDownloadOption.WIFI_ONLY)
        coEvery { messageDao.getMessagesWithoutLocalMediaForChat(CHAT) } returns emptyList()
        every { messageDao.getMessagesByChatId(CHAT) } returns flowOf(emptyList())
        every { messageSource.observeMessages(CHAT) } returns flowOf(emptyList())

        repository.getMessages(CHAT).first()

        coVerify(exactly = 0) { mediaBackfillScheduler.retryDownloads() }
    }

    @Test
    fun `a per-chat scan with failures queues the retry once`() = runTest {
        val rows = listOf(pendingRow("a"), pendingRow("b"))
        coEvery { messageDao.getMessagesWithoutLocalMediaForChat(CHAT) } returns rows
        coEvery { mediaFileManager.downloadAndSave(CHAT, any(), any()) } throws IOException("network went away")
        coEvery { messageDao.updateLocalUri(any(), any()) } just Runs

        repository.ensureLocalCopiesForChat(CHAT)

        coVerify(exactly = 1) { mediaBackfillScheduler.retryDownloads() }
    }

    // ensureLocalCopiesForChat wraps the scan itself, so the observable part is
    // that the retry was asked for and the call still returned normally.
    @Test
    fun `a scheduler failure does not fail the scan`() = runTest {
        coEvery { messageDao.getMessagesWithoutLocalMediaForChat(CHAT) } returns listOf(pendingRow("a"))
        coEvery { mediaFileManager.downloadAndSave(CHAT, any(), any()) } throws IOException("network went away")
        coEvery { mediaBackfillScheduler.retryDownloads() } throws IllegalStateException("WorkManager not initialised")

        repository.ensureLocalCopiesForChat(CHAT)

        coVerify(exactly = 1) { mediaBackfillScheduler.retryDownloads() }
    }

    private fun pendingRow(id: String) = MessageEntity(
        MessageRecord(
            id = id, chatId = CHAT, senderId = PEER, content = "", type = "IMAGE",
            mediaUrl = photoUrl(id), mediaThumbnailUrl = null,
            status = "SENT", replyToId = null, timestamp = 1L, editedAt = null,
        )
    )

    private fun ownRow(id: String, status: MessageStatus) = MessageEntity.fromDomain(
        Message(id = id, chatId = CHAT, senderId = SELF, content = "hi", type = MessageType.TEXT, status = status, timestamp = 1L)
    )

    private fun photoUrl(id: String) = "https://firebasestorage.example/$id.jpg"

    private fun photo(id: String) = RawMessage(
        id = id,
        chatId = CHAT,
        senderId = PEER,
        content = "",
        ciphertext = null,
        signalType = null,
        type = MessageType.IMAGE.name,
        mediaUrl = photoUrl(id),
        mediaThumbnailUrl = null,
        status = MessageStatus.SENT.name,
        replyToId = null,
        timestamp = 1_000L,
        editedAt = null,
    )

    private companion object {
        const val CHAT = "chat1"
        const val SELF = "uid1"
        const val PEER = "peer1"
    }
}
