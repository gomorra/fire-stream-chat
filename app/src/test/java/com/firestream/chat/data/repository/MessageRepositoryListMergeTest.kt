package com.firestream.chat.data.repository

import android.net.ConnectivityManager
import com.firestream.chat.data.crypto.SignalManager
import com.firestream.chat.data.local.PreferencesDataStore
import com.firestream.chat.data.local.dao.ChatDao
import com.firestream.chat.data.local.dao.MessageDao
import com.firestream.chat.data.local.entity.MessageEntity
import com.firestream.chat.data.remote.firebase.FirebaseAuthSource
import com.firestream.chat.data.remote.firebase.FirebaseStorageSource
import com.firestream.chat.data.remote.firebase.FirestoreMessageSource
import com.firestream.chat.data.remote.firebase.FirestoreUserSource
import com.firestream.chat.data.util.ImageCompressor
import com.firestream.chat.data.util.MediaFileManager
import com.firestream.chat.domain.model.ListDiff
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
import io.mockk.slot
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Regression tests for the list-update merge window in
 * [MessageRepositoryImpl.sendListMessage].
 *
 * A sequence of list edits should collect into a single bubble while they
 * happen close together, but once the gap exceeds 10 minutes a new bubble
 * must be started so later activity is not silently absorbed into a stale
 * bubble.
 */
class MessageRepositoryListMergeTest {

    private val messageDao = mockk<MessageDao>(relaxUnitFun = true)
    private val chatDao = mockk<ChatDao>(relaxed = true)
    private val messageSource = mockk<FirestoreMessageSource>()
    private val authSource = mockk<FirebaseAuthSource>()
    private val signalManager = mockk<SignalManager>(relaxed = true)
    private val storageSource = mockk<FirebaseStorageSource>()
    private val chatRepository = mockk<dagger.Lazy<ChatRepository>>(relaxed = true)
    private val listRepository = mockk<dagger.Lazy<ListRepository>>(relaxed = true)
    private val mediaFileManager = mockk<MediaFileManager>(relaxed = true)
    private val imageCompressor = mockk<ImageCompressor>(relaxed = true)
    private val preferencesDataStore = mockk<PreferencesDataStore>(relaxed = true)
    private val connectivityManager = mockk<ConnectivityManager>(relaxed = true)
    private val userSource = mockk<FirestoreUserSource>(relaxed = true)

    private lateinit var repository: MessageRepositoryImpl

    @Before
    fun setUp() {
        every { authSource.currentUserId } returns "uid1"
        every { messageSource.lastContentFor(any(), any()) } answers { secondArg() }
        repository = MessageRepositoryImpl(
            messageDao, chatDao, messageSource, authSource, signalManager, storageSource, chatRepository,
            listRepository, mediaFileManager, imageCompressor, preferencesDataStore, connectivityManager,
            userSource
        )
    }

    private fun existingListBubble(
        timestamp: Long,
        diff: ListDiff = ListDiff(added = listOf("Milk"))
    ): MessageEntity = MessageEntity.fromDomain(
        Message(
            id = "msg-existing",
            chatId = "chat1",
            senderId = "uid1",
            content = "\uD83D\uDCCB List updated: Groceries",
            type = MessageType.LIST,
            status = MessageStatus.SENT,
            timestamp = timestamp,
            listId = "list1",
            listDiff = diff
        )
    )

    @Test
    fun `merges into the last bubble when within the 10-minute window`() = runTest {
        val now = System.currentTimeMillis()
        // 5 minutes old — still within the merge window
        coEvery { messageDao.getLastMessageByChatId("chat1") } returns
            existingListBubble(timestamp = now - 5L * 60_000L)
        coEvery {
            messageSource.updateListMessageDiff(any(), any(), any(), any(), any())
        } just Runs

        val result = repository.sendListMessage(
            chatId = "chat1",
            listId = "list1",
            listTitle = "Groceries",
            listDiff = ListDiff(added = listOf("Eggs"))
        )

        assertTrue(result.isSuccess)
        // The existing bubble was updated in place …
        coVerify(exactly = 1) {
            messageSource.updateListMessageDiff("chat1", "msg-existing", any(), any(), any())
        }
        // … and no new Firestore document was created.
        coVerify(exactly = 0) {
            messageSource.sendListMessage(any(), any(), any(), any(), any(), any())
        }
    }

    @Test
    fun `starts a new bubble when the previous update is older than 10 minutes`() = runTest {
        val now = System.currentTimeMillis()
        // 15 minutes old — outside the merge window
        coEvery { messageDao.getLastMessageByChatId("chat1") } returns
            existingListBubble(timestamp = now - 15L * 60_000L)
        coEvery {
            messageSource.sendListMessage(any(), any(), any(), any(), any(), any())
        } returns "new-msg-id"

        val result = repository.sendListMessage(
            chatId = "chat1",
            listId = "list1",
            listTitle = "Groceries",
            listDiff = ListDiff(added = listOf("Eggs"))
        )

        assertTrue(result.isSuccess)
        // A brand-new Firestore message was created …
        coVerify(exactly = 1) {
            messageSource.sendListMessage(
                chatId = "chat1",
                senderId = "uid1",
                listId = "list1",
                content = any(),
                timestamp = any(),
                listDiff = any()
            )
        }
        // … and the stale bubble was NOT updated in place.
        coVerify(exactly = 0) {
            messageSource.updateListMessageDiff(any(), any(), any(), any(), any())
        }
    }

    @Test
    fun `new bubble only contains the latest diff, not the stale accumulated diff`() = runTest {
        val now = System.currentTimeMillis()
        val stale = ListDiff(added = listOf("Milk", "Bread"), checked = listOf("Sugar"))
        coEvery { messageDao.getLastMessageByChatId("chat1") } returns
            existingListBubble(timestamp = now - 20L * 60_000L, diff = stale)
        // Non-null slot: the production code always passes a non-null diff for
        // the case exercised here, so capture() (which requires T : Any in
        // MockK 1.13.x) works fine against the nullable parameter type.
        val capturedDiff = slot<Map<String, Any?>>()
        coEvery {
            messageSource.sendListMessage(any(), any(), any(), any(), any(), capture(capturedDiff))
        } returns "new-msg-id"

        repository.sendListMessage(
            chatId = "chat1",
            listId = "list1",
            listTitle = "Groceries",
            listDiff = ListDiff(added = listOf("Eggs"))
        )

        // The new bubble must carry only the fresh diff — the stale accumulated
        // state stays with the old bubble and is not re-merged.
        val sent = capturedDiff.captured
        assertEquals(listOf("Eggs"), sent?.get("added"))
        assertEquals(null, sent?.get("checked"))
    }

    @Test
    fun `subsequent updates after the gap collect into the new bubble`() = runTest {
        val now = System.currentTimeMillis()
        // First call: last bubble is stale (20 min old) → expect a new Firestore
        // document to be created.
        coEvery { messageDao.getLastMessageByChatId("chat1") } returnsMany listOf(
            existingListBubble(timestamp = now - 20L * 60_000L),
            // Second call: last bubble is the one we just created, fresh enough
            // to merge into.
            MessageEntity.fromDomain(
                Message(
                    id = "new-msg-id",
                    chatId = "chat1",
                    senderId = "uid1",
                    content = "\uD83D\uDCCB List updated: Groceries",
                    type = MessageType.LIST,
                    status = MessageStatus.SENT,
                    timestamp = System.currentTimeMillis(),
                    listId = "list1",
                    listDiff = ListDiff(added = listOf("Eggs"))
                )
            )
        )
        coEvery {
            messageSource.sendListMessage(any(), any(), any(), any(), any(), any())
        } returns "new-msg-id"
        coEvery {
            messageSource.updateListMessageDiff(any(), any(), any(), any(), any())
        } just Runs

        repository.sendListMessage("chat1", "list1", "Groceries", ListDiff(added = listOf("Eggs")))
        repository.sendListMessage("chat1", "list1", "Groceries", ListDiff(added = listOf("Flour")))

        // Exactly one brand-new document was created (for the first call after
        // the gap) …
        coVerify(exactly = 1) {
            messageSource.sendListMessage(any(), any(), any(), any(), any(), any())
        }
        // … and the second call merged into the freshly-created bubble.
        coVerify(exactly = 1) {
            messageSource.updateListMessageDiff("chat1", "new-msg-id", any(), any(), any())
        }
    }
}
