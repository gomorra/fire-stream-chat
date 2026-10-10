package com.firestream.chat.data.local.dao

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.firestream.chat.data.local.AppDatabase
import com.firestream.chat.data.local.entity.ChatEntity
import com.firestream.chat.data.local.entity.MessageRecord
import com.firestream.chat.data.local.entity.MessageSyncStateEntity
import com.firestream.chat.test.TestData
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The rules of a chat's sync state: a cursor never moves back, only a restore
 * creates a row, and the row leaves with the chat's messages.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [31], manifest = Config.NONE, application = android.app.Application::class)
class MessageSyncStateDaoTest {

    private lateinit var db: AppDatabase
    private lateinit var dao: MessageSyncStateDao

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        dao = db.messageSyncStateDao()
        runBlocking {
            db.chatDao().insertChat(ChatEntity.fromDomain(TestData.chat(id = "chat1")))
            db.chatDao().insertChat(ChatEntity.fromDomain(TestData.chat(id = "chat2")))
        }
    }

    @After
    fun tearDown() {
        db.close()
    }

    @Test
    fun `a restore writes the chat's row`() = runTest {
        dao.writeRestored("chat1", generation = 1, cursorMs = 500L)

        assertEquals(MessageSyncStateEntity("chat1", 1, 500L), dao.getState("chat1"))
        assertNull(dao.getState("chat2"))
    }

    // A chat deleted while its restore ran has lost its messages. A row written
    // after that would make the next sync ask a chat that came back for its tail.
    @Test
    fun `a restore of a chat that is gone writes nothing`() = runTest {
        dao.writeRestored("deleted", generation = 1, cursorMs = 500L)

        assertNull(dao.getState("deleted"))
    }

    @Test
    fun `raising never lowers the cursor`() = runTest {
        dao.writeRestored("chat1", generation = 1, cursorMs = 500L)

        dao.raiseCursor("chat1", generation = 1, cursorMs = 400L)
        assertEquals(500L, dao.getState("chat1")!!.cursorMs)

        dao.raiseCursor("chat1", generation = 1, cursorMs = 700L)
        assertEquals(700L, dao.getState("chat1")!!.cursorMs)
    }

    @Test
    fun `a raise for a missing row writes nothing`() = runTest {
        dao.raiseCursor("chat1", generation = 1, cursorMs = 700L)

        assertNull(dao.getState("chat1"))
    }

    @Test
    fun `a raise for another generation writes nothing`() = runTest {
        dao.writeRestored("chat1", generation = 1, cursorMs = 500L)

        dao.raiseCursor("chat1", generation = 2, cursorMs = 700L)

        assertEquals(MessageSyncStateEntity("chat1", 1, 500L), dao.getState("chat1"))
    }

    // Two syncs of one chat can overlap: the start of the app and a pull to refresh.
    @Test
    fun `a second restore of the same generation keeps the higher cursor`() = runTest {
        dao.writeRestored("chat1", generation = 1, cursorMs = 500L)

        dao.writeRestored("chat1", generation = 1, cursorMs = 300L)
        assertEquals(500L, dao.getState("chat1")!!.cursorMs)

        dao.writeRestored("chat1", generation = 1, cursorMs = 900L)
        assertEquals(900L, dao.getState("chat1")!!.cursorMs)
    }

    @Test
    fun `a restore under a new generation replaces the row, cursor included`() = runTest {
        dao.writeRestored("chat1", generation = 1, cursorMs = 500L)

        dao.writeRestored("chat1", generation = 2, cursorMs = 300L)

        assertEquals(MessageSyncStateEntity("chat1", 2, 300L), dao.getState("chat1"))
    }

    @Test
    fun `deleting a chat's messages removes its row and leaves other chats alone`() = runTest {
        val messageDao = db.messageDao()
        messageDao.upsertRecord(MessageRecord.fromDomain(TestData.message(id = "m1", chatId = "chat1")))
        messageDao.upsertRecord(MessageRecord.fromDomain(TestData.message(id = "m2", chatId = "chat2")))
        dao.writeRestored("chat1", generation = 1, cursorMs = 500L)
        dao.writeRestored("chat2", generation = 1, cursorMs = 600L)

        messageDao.deleteChatMessages("chat1")

        assertNull(dao.getState("chat1"))
        assertEquals(emptyList<String>(), messageDao.getMessagesByChatId("chat1").first().map { it.id })
        assertEquals(600L, dao.getState("chat2")!!.cursorMs)
        assertEquals(listOf("m2"), messageDao.getMessagesByChatId("chat2").first().map { it.id })
    }

    @Test
    fun `deleteAll empties the table`() = runTest {
        dao.writeRestored("chat1", generation = 1, cursorMs = 500L)
        dao.writeRestored("chat2", generation = 1, cursorMs = 600L)

        dao.deleteAll()

        assertNull(dao.getState("chat1"))
        assertNull(dao.getState("chat2"))
    }
}
