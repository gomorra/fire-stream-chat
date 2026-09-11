package com.firestream.chat.data.repository

import com.firestream.chat.data.local.PreferencesDataStore
import com.firestream.chat.data.local.dao.MessageDao
import com.firestream.chat.data.local.entity.MessageEntity
import com.firestream.chat.data.outbox.OutboxSender
import com.firestream.chat.data.remote.source.AuthSource
import com.firestream.chat.domain.repository.ChatRepository
import com.firestream.chat.domain.repository.ListRepository
import io.mockk.Runs
import io.mockk.coEvery
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test

/**
 * Per-image HD beats the global preference; a null follows it
 * (`.claude/plans/image-editor.md` §2.5).
 *
 * Both halves matter. The override is the feature; the fall-through is the
 * promise that nothing changes for anyone who never touches the toggle — every
 * caller that does not offer the choice (the share sheet, a retry) omits the
 * argument entirely and must keep behaving as it did.
 *
 * Asserted on the optimistic Room row's `isHd`, which renders the HD badge.
 * [OutboxSender] reads the compressor's quality flag off that same row
 * (`OutboxSenderTest`), so badge and bytes have one source and cannot disagree.
 */
class MessageRepositoryHdPrecedenceTest {

    private val messageDao = mockk<MessageDao>(relaxed = true)
    private val authSource = mockk<AuthSource>()
    private val chatRepository = mockk<dagger.Lazy<ChatRepository>>()
    private val listRepository = mockk<dagger.Lazy<ListRepository>>()
    private val preferencesDataStore = mockk<PreferencesDataStore>(relaxed = true)

    private val insertedEntities = mutableListOf<MessageEntity>()

    private lateinit var repository: MessageRepositoryImpl

    @Before
    fun setUp() {
        every { authSource.currentUserId } returns "uid1"

        coEvery { messageDao.insertMessage(any()) } answers {
            insertedEntities += firstArg<MessageEntity>()
        }
        coEvery { messageDao.updateMessageStatus(any(), any()) } just Runs

        repository = messageRepository(
            messageDao = messageDao,
            authSource = authSource,
            chatRepository = chatRepository,
            listRepository = listRepository,
            preferencesDataStore = preferencesDataStore,
        )
    }

    private suspend fun send(isHd: Boolean?) {
        repository.sendMediaMessage(
            chatId = "chat1",
            uri = "content://pick/1",
            mimeType = "image/jpeg",
            recipientId = "recipient1",
            caption = "",
            isHd = isHd,
        )
    }

    @Test
    fun `per-item true wins over a global preference of false`() = runTest {
        every { preferencesDataStore.sendImagesFullQualityFlow } returns flowOf(false)

        send(isHd = true)

        assertEquals(true, insertedEntities.single().isHd)
    }

    @Test
    fun `per-item false wins over a global preference of true`() = runTest {
        every { preferencesDataStore.sendImagesFullQualityFlow } returns flowOf(true)

        send(isHd = false)

        assertEquals(false, insertedEntities.single().isHd)
    }

    @Test
    fun `a null override falls through to a global preference of true`() = runTest {
        every { preferencesDataStore.sendImagesFullQualityFlow } returns flowOf(true)

        send(isHd = null)

        assertEquals(true, insertedEntities.single().isHd)
    }

    @Test
    fun `a null override falls through to a global preference of false`() = runTest {
        every { preferencesDataStore.sendImagesFullQualityFlow } returns flowOf(false)

        send(isHd = null)

        assertEquals(false, insertedEntities.single().isHd)
    }

    @Test
    fun `a caller that omits the argument entirely still follows the preference`() = runTest {
        // The default is what keeps every pre-existing call site — the share
        // sheet among them — compiling and behaving exactly as before.
        every { preferencesDataStore.sendImagesFullQualityFlow } returns flowOf(true)

        repository.sendMediaMessage("chat1", "content://pick/1", "image/jpeg", "recipient1")

        assertEquals(true, insertedEntities.single().isHd)
    }
}
