package com.firestream.chat.data.local.dao

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.firestream.chat.data.local.AppDatabase
import com.firestream.chat.data.local.entity.ChatEntity
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Regression tests for the chat-list ordering SQL in [ChatDao.getAllChats].
 *
 * The query must emit chats ordered by `lastMessageTimestamp DESC`, falling
 * back to `createdAt` when the timestamp is NULL. SQLite's default `DESC`
 * ordering puts NULLs LAST, so a brand-new chat with no messages would
 * otherwise sink to the bottom of the list regardless of when it was
 * created — the `COALESCE(lastMessageTimestamp, createdAt)` guard fixes
 * that.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29], manifest = Config.NONE, application = android.app.Application::class)
class ChatDaoOrderingTest {

    private lateinit var db: AppDatabase
    private lateinit var dao: ChatDao

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        dao = db.chatDao()
    }

    @After
    fun tearDown() {
        db.close()
    }

    @Test
    fun `chats with newer last-message timestamps sort above older ones`() = runTest {
        dao.insertChats(listOf(
            chat(id = "older", lastMessageTimestamp = 1_000L, createdAt = 100L),
            chat(id = "newer", lastMessageTimestamp = 2_000L, createdAt = 100L),
        ))

        assertEquals(listOf("newer", "older"), dao.getAllChats().first().map { it.id })
    }

    @Test
    fun `chat with null last-message timestamp falls back to createdAt for ordering`() = runTest {
        dao.insertChats(listOf(
            chat(id = "old-with-msg", lastMessageTimestamp = 1_000L, createdAt = 500L),
            chat(id = "brand-new", lastMessageTimestamp = null, createdAt = 5_000L),
        ))

        // The brand-new chat has no messages yet but was created most recently —
        // it should appear ABOVE the older chat that does have messages.
        assertEquals(listOf("brand-new", "old-with-msg"), dao.getAllChats().first().map { it.id })
    }

    @Test
    fun `mixed null and non-null timestamps sort correctly together`() = runTest {
        dao.insertChats(listOf(
            chat(id = "a", lastMessageTimestamp = 3_000L, createdAt = 100L),
            chat(id = "b", lastMessageTimestamp = null, createdAt = 2_500L),
            chat(id = "c", lastMessageTimestamp = 1_000L, createdAt = 100L),
            chat(id = "d", lastMessageTimestamp = null, createdAt = 4_000L),
        ))

        // Effective ordering key: 3000, 2500, 1000, 4000 — d should lead, then a, b, c.
        assertEquals(listOf("d", "a", "b", "c"), dao.getAllChats().first().map { it.id })
    }

    @Test
    fun `updateLastMessage bumps chat to the top of the list`() = runTest {
        dao.insertChats(listOf(
            chat(id = "x", lastMessageTimestamp = 1_000L, createdAt = 100L),
            chat(id = "y", lastMessageTimestamp = 2_000L, createdAt = 100L),
        ))
        assertEquals(listOf("y", "x"), dao.getAllChats().first().map { it.id })

        dao.updateLastMessage(chatId = "x", id = "msg-1", content = "hi", timestamp = 3_000L)

        // x now has the newest timestamp and should lead.
        assertEquals(listOf("x", "y"), dao.getAllChats().first().map { it.id })
    }

    private fun chat(
        id: String,
        lastMessageTimestamp: Long?,
        createdAt: Long,
    ): ChatEntity = ChatEntity(
        id = id,
        type = "INDIVIDUAL",
        name = null,
        avatarUrl = null,
        participants = listOf("me", id),
        unreadCount = 0,
        createdAt = createdAt,
        createdBy = "me",
        admins = emptyList(),
        lastMessageId = null,
        lastMessageContent = null,
        lastMessageTimestamp = lastMessageTimestamp,
    )
}
