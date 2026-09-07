package com.firestream.chat.data.local.dao

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.firestream.chat.data.local.AppDatabase
import com.firestream.chat.data.local.entity.MessageEntity
import com.firestream.chat.domain.model.MessageStatus
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Covers the prefiltered in-chat search query behind the search chips.
 *
 * The load-bearing case is `photos browse excludes a soft-deleted image`:
 * [MessageDao.softDeleteMessage] blanks `content` but leaves `mediaUrl`
 * intact, so a tombstoned image is invisible to any non-blank content LIKE —
 * but a filter-only query has no content predicate at all and would return it
 * and render its thumbnail. `deletedAt IS NULL` is what stops that.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29], manifest = Config.NONE, application = android.app.Application::class)
class MessageDaoSearchFilterTest {

    private lateinit var db: AppDatabase
    private lateinit var dao: MessageDao

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        dao = db.messageDao()
    }

    @After
    fun tearDown() {
        db.close()
    }

    /** Calls the query the way the repository does, with everything off by default. */
    private suspend fun search(
        chatId: String = "c1",
        query: String = "",
        type: String? = null,
        requireLink: Boolean = false,
        starredOnly: Boolean = false,
        from: Long? = null,
        to: Long? = null,
        limit: Int = 200,
    ) = dao.searchMessagesInChat(chatId, query, type, requireLink, starredOnly, from, to, limit)

    @Test
    fun `text search matches content substrings within the chat`() = runTest {
        dao.insertMessages(listOf(
            msg(id = "hit", content = "meet me at the harbour"),
            msg(id = "miss", content = "nothing relevant"),
            msg(id = "other-chat", chatId = "c2", content = "harbour again"),
        ))

        val results = search(query = "harbour", limit = 50)

        assertEquals(listOf("hit"), results.map { it.id })
    }

    @Test
    fun `blank query with no filter returns the whole chat`() = runTest {
        dao.insertMessages(listOf(
            msg(id = "a", content = "one"),
            msg(id = "b", content = ""),
        ))

        assertEquals(2, search().size)
    }

    @Test
    fun `photos browse returns images with no content predicate`() = runTest {
        dao.insertMessages(listOf(
            msg(id = "img", type = "IMAGE", content = "", mediaUrl = "https://x/1.jpg"),
            msg(id = "vid", type = "VIDEO", content = "", mediaUrl = "https://x/1.mp4"),
            msg(id = "txt", type = "TEXT", content = "hello"),
        ))

        val results = search(type = "IMAGE")

        assertEquals(listOf("img"), results.map { it.id })
    }

    @Test
    fun `photos browse excludes a soft-deleted image`() = runTest {
        // The regression this feature would otherwise introduce: softDeleteMessage
        // blanks `content` but keeps `mediaUrl`, so only `deletedAt IS NULL`
        // keeps the tombstone out of a filter-only browse.
        dao.insertMessages(listOf(
            msg(id = "live", type = "IMAGE", content = "", mediaUrl = "https://x/1.jpg"),
            msg(id = "gone", type = "IMAGE", content = "caption", mediaUrl = "https://x/2.jpg"),
        ))
        dao.softDeleteMessage("gone", deletedAt = 4242L)

        val results = search(type = "IMAGE")

        assertEquals(listOf("live"), results.map { it.id })
    }

    @Test
    fun `text search also excludes a soft-deleted message`() = runTest {
        dao.insertMessage(msg(id = "gone", content = "harbour"))
        dao.softDeleteMessage("gone", deletedAt = 1L)

        assertTrue(search(query = "harbour", limit = 50).isEmpty())
    }

    @Test
    fun `links filter matches a TEXT row rather than a message type`() = runTest {
        dao.insertMessages(listOf(
            msg(id = "link", type = "TEXT", content = "see https://example.com/x"),
            msg(id = "plain", type = "TEXT", content = "no url here"),
            msg(id = "img", type = "IMAGE", content = "", mediaUrl = "https://x/1.jpg"),
        ))

        val results = search(requireLink = true)

        assertEquals(listOf("link"), results.map { it.id })
    }

    @Test
    fun `starred filter keeps only starred rows`() = runTest {
        dao.insertMessages(listOf(
            msg(id = "star", content = "important"),
            msg(id = "plain", content = "important too"),
        ))
        dao.setStarred("star", true)

        assertEquals(listOf("star"), search(starredOnly = true).map { it.id })
    }

    @Test
    fun `date range bounds are inclusive on both ends`() = runTest {
        dao.insertMessages(listOf(
            msg(id = "before", timestamp = 99L),
            msg(id = "lower", timestamp = 100L),
            msg(id = "inside", timestamp = 150L),
            msg(id = "upper", timestamp = 200L),
            msg(id = "after", timestamp = 201L),
        ))

        val results = search(from = 100L, to = 200L)

        assertEquals(listOf("upper", "inside", "lower"), results.map { it.id })
    }

    @Test
    fun `photos plus a date range combine as AND`() = runTest {
        dao.insertMessages(listOf(
            msg(id = "in-range", type = "IMAGE", content = "", mediaUrl = "https://x/1.jpg", timestamp = 150L),
            msg(id = "out-of-range", type = "IMAGE", content = "", mediaUrl = "https://x/2.jpg", timestamp = 900L),
            msg(id = "text-in-range", type = "TEXT", content = "hi", timestamp = 150L),
        ))

        val results = search(type = "IMAGE", from = 100L, to = 200L)

        assertEquals(listOf("in-range"), results.map { it.id })
    }

    @Test
    fun `a text query narrows an active type filter`() = runTest {
        dao.insertMessages(listOf(
            msg(id = "doc-hit", type = "DOCUMENT", content = "quarterly-report.pdf", mediaUrl = "https://x/a.pdf"),
            msg(id = "doc-miss", type = "DOCUMENT", content = "invoice.pdf", mediaUrl = "https://x/b.pdf"),
        ))

        val results = search(query = "report", type = "DOCUMENT", limit = 50)

        assertEquals(listOf("doc-hit"), results.map { it.id })
    }

    @Test
    fun `results are newest-first and capped by the limit`() = runTest {
        dao.insertMessages((1..5).map { msg(id = "m$it", timestamp = it * 100L) })

        val results = search(limit = 3)

        assertEquals(listOf("m5", "m4", "m3"), results.map { it.id })
    }

    private fun msg(
        id: String,
        chatId: String = "c1",
        content: String = "hi",
        type: String = "TEXT",
        mediaUrl: String? = null,
        timestamp: Long = 1000L,
        senderId: String = "me",
    ): MessageEntity = MessageEntity(
        id = id,
        chatId = chatId,
        senderId = senderId,
        content = content,
        type = type,
        mediaUrl = mediaUrl,
        mediaThumbnailUrl = null,
        status = MessageStatus.SENT.name,
        replyToId = null,
        timestamp = timestamp,
        editedAt = null,
    )
}
