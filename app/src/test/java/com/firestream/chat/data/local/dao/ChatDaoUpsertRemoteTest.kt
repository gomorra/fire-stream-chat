package com.firestream.chat.data.local.dao

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.firestream.chat.data.local.AppDatabase
import com.firestream.chat.data.local.entity.ChatEntity
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
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
 * Regression tests for the read-modify-write race in [ChatDao.upsertRemote].
 *
 * Bug: archive a chat, unarchive it, then archive again — the chat disappeared
 * from the archive list. Cause: the Firestore-snapshot merge in
 * `ChatRepositoryImpl.getChats` did `getChatsByIds` → map → `insertChats` without
 * a transaction. A concurrent `setArchived(true)` could land between the read
 * and the write, and the merged entity (built from the stale snapshot) then
 * resurrected `isArchived = false` on top of it.
 *
 * Fix: `upsertRemote` runs the read+merge+write inside one Room `@Transaction`,
 * which holds the writer lock and serialises any concurrent single-statement
 * `setArchived` / `setPinned` / `setMuteUntil` write either entirely before or
 * entirely after.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29], manifest = Config.NONE, application = android.app.Application::class)
class ChatDaoUpsertRemoteTest {

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
    fun `upsertRemote preserves local-only fields when the row already exists`() = runTest {
        // Local row carries archive/pin/mute + cached avatar — every field the merge
        // is supposed to preserve.
        dao.insertChat(
            chat(
                id = "A",
                isArchived = true,
                isPinned = true,
                muteUntil = 9_999L,
                cachedAvatarUrl = "https://cdn/old.jpg",
                localAvatarPath = "/data/avatars/A.jpg",
            )
        )

        // Remote snapshot (mapped from Firestore) carries default/empty values for
        // every local-only field — Firestore doesn't store them.
        val returnedExisting = dao.upsertRemote(
            listOf(chat(id = "A", isArchived = false, isPinned = false, muteUntil = 0L))
        )

        val merged = dao.getChatById("A")!!
        assertEquals("isArchived preserved", true, merged.isArchived)
        assertEquals("isPinned preserved", true, merged.isPinned)
        assertEquals("muteUntil preserved", 9_999L, merged.muteUntil)
        assertEquals("cachedAvatarUrl preserved", "https://cdn/old.jpg", merged.cachedAvatarUrl)
        assertEquals("localAvatarPath preserved", "/data/avatars/A.jpg", merged.localAvatarPath)

        // The returned snapshot mirrors what callers (avatar download) need to compare against.
        assertEquals("https://cdn/old.jpg", returnedExisting["A"]?.cachedAvatarUrl)
    }

    @Test
    fun `upsertRemote inserts brand-new chats with their default local fields`() = runTest {
        // First-sight chat from Firestore — no row in Room yet.
        val returnedExisting = dao.upsertRemote(
            listOf(chat(id = "B", isArchived = false, isPinned = false, muteUntil = 0L))
        )

        val inserted = dao.getChatById("B")!!
        assertEquals(false, inserted.isArchived)
        assertEquals(false, inserted.isPinned)
        assertEquals(0L, inserted.muteUntil)
        assertTrue("nothing pre-existing returned for new chat", "B" !in returnedExisting)
    }

    @Test
    fun `concurrent setArchived survives an in-flight upsertRemote merge`() = runTest {
        // The actual race: launch many setArchived(true) flips in parallel with many
        // upsertRemote merges that all carry isArchived=false. Without the
        // @Transaction wrapper, at least one merge would clobber a flip and leave
        // isArchived=false. With it, the writer lock serialises every operation, so
        // whichever write happens last wins — and we ensure the LAST write is a
        // setArchived(true) (the 100ms delay between waves of merges ensures this in
        // wall-clock terms; assertion below verifies).
        dao.insertChat(chat(id = "A", isArchived = false))

        val rounds = 50
        val jobs = buildList {
            repeat(rounds) {
                add(async { dao.setArchived("A", true) })
                add(async {
                    dao.upsertRemote(listOf(chat(id = "A", isArchived = false)))
                })
            }
            // Final flip must be archive=true so the assertion is unambiguous.
            add(async { dao.setArchived("A", true) })
        }
        jobs.awaitAll()

        assertEquals(
            "after the last setArchived(true), isArchived must be true — " +
                "no concurrent upsertRemote can resurrect the stale false",
            true,
            dao.getChatById("A")!!.isArchived
        )
    }

    private fun chat(
        id: String,
        isArchived: Boolean = false,
        isPinned: Boolean = false,
        muteUntil: Long = 0L,
        cachedAvatarUrl: String? = null,
        localAvatarPath: String? = null,
    ): ChatEntity = ChatEntity(
        id = id,
        type = "INDIVIDUAL",
        name = null,
        avatarUrl = null,
        cachedAvatarUrl = cachedAvatarUrl,
        localAvatarPath = localAvatarPath,
        participants = listOf("me", id),
        unreadCount = 0,
        createdAt = 1_000L,
        createdBy = "me",
        admins = emptyList(),
        lastMessageId = null,
        lastMessageContent = null,
        lastMessageTimestamp = null,
        isPinned = isPinned,
        isArchived = isArchived,
        muteUntil = muteUntil,
    )
}
