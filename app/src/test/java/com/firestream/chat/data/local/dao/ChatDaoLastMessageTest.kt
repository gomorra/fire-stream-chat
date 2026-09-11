package com.firestream.chat.data.local.dao

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.firestream.chat.data.local.AppDatabase
import com.firestream.chat.data.local.entity.ChatEntity
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * [ChatDao.updateLastMessage] is newer-only. Sends run in parallel and finish in
 * any order — a photo composed first can finish its upload after the text typed
 * next — so the chat preview must follow message timestamps, not completion
 * order. Regression: the last send to *finish* took the preview, so the chat list
 * showed a message that was not the newest in the chat.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29], manifest = Config.NONE, application = android.app.Application::class)
class ChatDaoLastMessageTest {

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
    fun `an older message finishing last does not take the preview back`() = runTest {
        dao.insertChat(chat(lastMessageId = "text", lastMessageContent = "hi", lastMessageTimestamp = 2_000L))

        dao.updateLastMessage(chatId = "c1", id = "photo", content = "📷 Photo", timestamp = 1_000L)

        assertPreview(id = "text", content = "hi", timestamp = 2_000L)
    }

    @Test
    fun `a newer message replaces the preview`() = runTest {
        dao.insertChat(chat(lastMessageId = "text", lastMessageContent = "hi", lastMessageTimestamp = 2_000L))

        dao.updateLastMessage(chatId = "c1", id = "photo", content = "📷 Photo", timestamp = 3_000L)

        assertPreview(id = "photo", content = "📷 Photo", timestamp = 3_000L)
    }

    // A retry on a backend that mints its own ids (PocketBase) swaps the row's id
    // but keeps its timestamp; the preview must follow it to the new id.
    @Test
    fun `the same timestamp rebinds the preview to a swapped id`() = runTest {
        dao.insertChat(chat(lastMessageId = "temp-id", lastMessageContent = "hi", lastMessageTimestamp = 2_000L))

        dao.updateLastMessage(chatId = "c1", id = "server-id", content = "hi", timestamp = 2_000L)

        assertPreview(id = "server-id", content = "hi", timestamp = 2_000L)
    }

    @Test
    fun `a chat without a preview takes the first one`() = runTest {
        dao.insertChat(chat(lastMessageId = null, lastMessageContent = null, lastMessageTimestamp = null))

        dao.updateLastMessage(chatId = "c1", id = "first", content = "hello", timestamp = 1_000L)

        assertPreview(id = "first", content = "hello", timestamp = 1_000L)
    }

    private suspend fun assertPreview(id: String, content: String, timestamp: Long) {
        val chat = dao.getChatById("c1")!!
        assertEquals(id, chat.lastMessageId)
        assertEquals(content, chat.lastMessageContent)
        assertEquals(timestamp, chat.lastMessageTimestamp)
    }

    private fun chat(
        lastMessageId: String?,
        lastMessageContent: String?,
        lastMessageTimestamp: Long?,
    ): ChatEntity = ChatEntity(
        id = "c1",
        type = "INDIVIDUAL",
        name = null,
        avatarUrl = null,
        participants = listOf("me", "peer"),
        unreadCount = 0,
        createdAt = 100L,
        createdBy = "me",
        admins = emptyList(),
        lastMessageId = lastMessageId,
        lastMessageContent = lastMessageContent,
        lastMessageTimestamp = lastMessageTimestamp,
    )
}
