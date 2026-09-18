package com.firestream.chat.data.repository

import com.firestream.chat.data.local.AutoDownloadOption
import com.firestream.chat.data.local.PreferencesDataStore
import com.firestream.chat.data.local.dao.MessageDao
import com.firestream.chat.data.local.entity.MessageEntity
import com.firestream.chat.data.local.entity.MessageRecord
import com.firestream.chat.data.remote.source.AuthSource
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test

/**
 * What a typed query matches: from two characters up, any part of a word; at one
 * character, the whole word it spells.
 *
 * The DAO's `LIKE '%…%'` already returns substring hits (covered end to end in
 * `MessageDaoSearchFilterTest`), so what these tests pin down is which of those
 * rows the repository *keeps* — the half that used to drop every hit that wasn't
 * a whole word, and with it "Geschenk" inside "Geburtstagsgeschenk" and every
 * prefix of a word still being typed.
 *
 * The DAO is mocked precisely so the rows are a fixed input: a test that let
 * SQLite choose them could not tell a row the repository dropped from a row the
 * query never returned.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class MessageRepositorySearchMatchingTest {

    private val testDispatcher = StandardTestDispatcher()

    private val messageDao = mockk<MessageDao>()
    private val authSource = mockk<AuthSource>()
    private val preferencesDataStore = mockk<PreferencesDataStore>(relaxed = true)

    private lateinit var repository: MessageRepositoryImpl

    @Before
    fun setUp() {
        Dispatchers.setMain(testDispatcher)
        every { authSource.currentUserId } returns "uid1"
        every { preferencesDataStore.autoDownloadFlow } returns flowOf(AutoDownloadOption.NEVER)

        repository = messageRepository(
            messageDao = messageDao,
            authSource = authSource,
            preferencesDataStore = preferencesDataStore,
        )
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    /** One row per `id to content` pair, newest last, as the DAO would hand them over. */
    private fun stubRows(vararg rows: Pair<String, String>) {
        val entities = rows.mapIndexed { i, (id, content) ->
            MessageEntity(MessageRecord(
                id = id,
                chatId = "chat1",
                senderId = "sender1",
                content = content,
                type = "TEXT",
                mediaUrl = null,
                mediaThumbnailUrl = null,
                status = "SENT",
                replyToId = null,
                timestamp = 1000L + i,
                editedAt = null,
            ))
        }
        coEvery {
            messageDao.searchMessages(any(), any(), any(), any(), any(), any(), any(), any())
        } returns entities
    }

    @Test
    fun `a partial query matches the middle of a word`() = runTest {
        // The compound case: the word the user remembers is buried in a longer
        // one, which a whole-word match can never find.
        stubRows("hit" to "Dein Geburtstagsgeschenk ist da", "miss" to "nothing relevant")

        val result = repository.searchMessages("chat1", "geschenk")

        assertEquals(listOf("hit", "miss"), result.messages.map { it.id })
    }

    @Test
    fun `a two-letter query is already a partial match`() = runTest {
        // Two characters is the floor, so the shortest partial query there is
        // must still reach mid-word.
        stubRows("hit" to "Termine stehen")

        val result = repository.searchMessages("chat1", "in")

        assertEquals(listOf("hit"), result.messages.map { it.id })
    }

    @Test
    fun `a partial query does not re-apply case folding over the DAO's`() = runTest {
        // SQLite's LIKE already matched these rows case-insensitively. Filtering
        // them again in Kotlin could only ever throw some of them away.
        stubRows("hit" to "geburtstagsgeschenk")

        val result = repository.searchMessages("chat1", "Schenk")

        assertEquals(listOf("hit"), result.messages.map { it.id })
    }

    @Test
    fun `a single-letter query keeps only the word it spells`() = runTest {
        // A single letter as a substring is very nearly every message, so it
        // stays pinned to the whole word — otherwise "a" returns the newest page
        // of the chat and calls it a search result.
        stubRows("hit" to "a cat sat", "miss" to "category")

        val result = repository.searchMessages("chat1", "a")

        assertEquals(listOf("hit"), result.messages.map { it.id })
    }

    @Test
    fun `a single-letter query still matches its word in either case`() = runTest {
        stubRows("hit" to "a cat sat")

        val result = repository.searchMessages("chat1", "A")

        assertEquals(listOf("hit"), result.messages.map { it.id })
    }
}
