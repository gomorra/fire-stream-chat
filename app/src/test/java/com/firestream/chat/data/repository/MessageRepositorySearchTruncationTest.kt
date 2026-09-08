package com.firestream.chat.data.repository

import android.net.ConnectivityManager
import com.firestream.chat.data.crypto.SignalManager
import com.firestream.chat.data.local.AutoDownloadOption
import com.firestream.chat.data.local.PreferencesDataStore
import com.firestream.chat.data.local.dao.ChatDao
import com.firestream.chat.data.local.dao.MessageDao
import com.firestream.chat.data.local.entity.MessageEntity
import com.firestream.chat.data.remote.source.AuthSource
import com.firestream.chat.data.remote.source.MessageSource
import com.firestream.chat.data.remote.source.StorageSource
import com.firestream.chat.data.remote.source.UserSource
import com.firestream.chat.data.util.ImageCompressor
import com.firestream.chat.data.util.MediaFileManager
import com.firestream.chat.data.util.VideoTranscoder
import com.firestream.chat.domain.model.MessageFilterType
import com.firestream.chat.domain.model.MessageSearchFilter
import com.firestream.chat.domain.model.MessageSearchLimits
import com.firestream.chat.domain.repository.ChatRepository
import com.firestream.chat.domain.repository.ListRepository
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
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Truncation reporting for message search, in one chat and across all of them.
 *
 * The load-bearing case is `a page thinned by the word-boundary filter is still
 * reported as truncated`: SQLite's `LIMIT` runs *before* the whole-word pass, so
 * a capped query can hand back far fewer messages than the cap. Deriving
 * "there may be more" from the surviving count — which is what the UI did before
 * `MessageSearchResults` existed — silently presents a truncated page as an
 * exact total.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class MessageRepositorySearchTruncationTest {

    private val testDispatcher = StandardTestDispatcher()

    private val messageDao = mockk<MessageDao>()
    private val chatDao = mockk<ChatDao>(relaxed = true)
    private val messageSource = mockk<MessageSource>(relaxed = true)
    private val authSource = mockk<AuthSource>()
    private val signalManager = mockk<SignalManager>(relaxed = true)
    private val storageSource = mockk<StorageSource>(relaxed = true)
    private val chatRepository = mockk<dagger.Lazy<ChatRepository>>(relaxed = true)
    private val mediaFileManager = mockk<MediaFileManager>(relaxed = true)
    private val imageCompressor = mockk<ImageCompressor>(relaxed = true)
    private val videoTranscoder = mockk<VideoTranscoder>(relaxed = true)
    private val preferencesDataStore = mockk<PreferencesDataStore>(relaxed = true)
    private val connectivityManager = mockk<ConnectivityManager>(relaxed = true)
    private val listRepository = mockk<dagger.Lazy<ListRepository>>(relaxed = true)
    private val userSource = mockk<UserSource>(relaxed = true)

    private lateinit var repository: MessageRepositoryImpl

    @Before
    fun setUp() {
        Dispatchers.setMain(testDispatcher)
        every { authSource.currentUserId } returns "uid1"
        every { preferencesDataStore.autoDownloadFlow } returns flowOf(AutoDownloadOption.NEVER)

        repository = MessageRepositoryImpl(
            messageDao, chatDao, messageSource, authSource, signalManager, storageSource, chatRepository,
            listRepository, mediaFileManager, imageCompressor, videoTranscoder, preferencesDataStore,
            connectivityManager, userSource,
        )
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    private fun rows(count: Int, content: (Int) -> String) = (0 until count).map { i ->
        MessageEntity(
            id = "m$i",
            chatId = "chat1",
            senderId = "sender1",
            content = content(i),
            type = "TEXT",
            mediaUrl = null,
            mediaThumbnailUrl = null,
            status = "SENT",
            replyToId = null,
            timestamp = 1000L + i,
            editedAt = null,
        )
    }

    private fun stubSearch(returned: List<MessageEntity>) {
        coEvery {
            messageDao.searchMessages(any(), any(), any(), any(), any(), any(), any(), any())
        } returns returned
    }

    /** Stubs only the calls made with [limit], so a cap mismatch fails loudly. */
    private fun stubSearchAtLimit(limit: Int, returned: List<MessageEntity>) {
        coEvery {
            messageDao.searchMessages(any(), any(), any(), any(), any(), any(), any(), limit)
        } returns returned
    }

    @Test
    fun `a page thinned by the word-boundary filter is still reported as truncated`() = runTest {
        // A full page of substring hits ("category"), of which only two are the
        // whole word the user asked for. Eight further genuine matches, older
        // than these, were never fetched — so "2 results" would be a lie.
        val returned = rows(MessageSearchLimits.TEXT) { i ->
            if (i < 2) "the cat sat" else "category $i"
        }
        stubSearch(returned)

        val result = repository.searchMessages("chat1", "cat")

        assertEquals(2, result.messages.size)
        assertTrue("truncation must come off the raw row count", result.truncated)
    }

    @Test
    fun `a short page is not reported as truncated`() = runTest {
        stubSearch(rows(3) { "the cat sat" })

        val result = repository.searchMessages("chat1", "cat")

        assertEquals(3, result.messages.size)
        assertFalse(result.truncated)
    }

    @Test
    fun `a full browse page is reported as truncated`() = runTest {
        stubSearch(rows(MessageSearchLimits.BROWSE) { "" })

        val result = repository.searchMessages(
            "chat1",
            "",
            MessageSearchFilter(type = MessageFilterType.PHOTOS),
        )

        assertEquals(MessageSearchLimits.BROWSE, result.messages.size)
        assertTrue(result.truncated)
    }

    @Test
    fun `browse mode keeps rows the word-boundary filter would have dropped`() = runTest {
        // Media rows carry an empty content that no word regex matches; browse
        // mode must not run the filter at all.
        stubSearch(rows(4) { "" })

        val result = repository.searchMessages(
            "chat1",
            "",
            MessageSearchFilter(type = MessageFilterType.PHOTOS),
        )

        assertEquals(4, result.messages.size)
        assertFalse(result.truncated)
    }

    @Test
    fun `global text search caps at GLOBAL, not the in-chat TEXT cap`() = runTest {
        stubSearchAtLimit(
            MessageSearchLimits.GLOBAL,
            rows(MessageSearchLimits.GLOBAL) { i -> if (i < 1) "the cat sat" else "category $i" },
        )

        val result = repository.searchMessages(null, "cat")

        assertEquals(1, result.messages.size)
        assertTrue("truncation must come off the raw row count", result.truncated)
    }

    @Test
    fun `global browse mode caps at BROWSE, the same as an in-chat browse`() = runTest {
        stubSearchAtLimit(MessageSearchLimits.BROWSE, rows(MessageSearchLimits.BROWSE) { "" })

        val result = repository.searchMessages(
            null,
            "",
            MessageSearchFilter(type = MessageFilterType.PHOTOS),
        )

        assertEquals(MessageSearchLimits.BROWSE, result.messages.size)
        assertTrue(result.truncated)
    }

    @Test
    fun `a failed query reports neither results nor truncation`() = runTest {
        coEvery {
            messageDao.searchMessages(any(), any(), any(), any(), any(), any(), any(), any())
        } throws IllegalStateException("db gone")

        val result = repository.searchMessages("chat1", "cat")

        assertTrue(result.messages.isEmpty())
        assertFalse(result.truncated)
    }
}
