package com.firestream.chat.data.local.dao

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.firestream.chat.data.local.AppDatabase
import com.firestream.chat.data.local.entity.StickerEntity
import com.firestream.chat.data.local.entity.StickerPackEntity
import com.firestream.chat.data.local.entity.StickerSyncState
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
 * The library's write rules: a sticker is in a pack once and in order, a known
 * sticker keeps its row, and every change to a pack marks it for sync.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [31], manifest = Config.NONE, application = android.app.Application::class)
class StickerDaoTest {

    private lateinit var db: AppDatabase
    private lateinit var dao: StickerDao

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        dao = db.stickerDao()
    }

    @After
    fun tearDown() {
        db.close()
    }

    private fun sticker(id: String, emojis: List<String> = emptyList()) =
        StickerEntity(id = id, format = "WEBP", width = 512, height = 512, isAnimated = false, emojis = emojis, createdAt = 1L)

    private fun pack(id: String, kind: String = "USER", importKey: String? = null, sortOrder: Int = 0, createdAt: Long = 1L) =
        StickerPackEntity(
            id = id, name = id, publisher = null, kind = kind, originPackId = null, importKey = importKey,
            sortOrder = sortOrder, createdAt = createdAt, updatedAt = 1L, syncState = StickerSyncState.SYNCED.name,
        )

    private suspend fun syncState(packId: String) = dao.getPack(packId)!!.syncState

    @Test
    fun `stickers are appended in order and a sticker already in the pack is not added twice`() = runTest {
        dao.insertPack(pack("p"))

        assertEquals(2, dao.addToPack("p", listOf("a", "b"), atFront = false, now = 5L))
        assertEquals(1, dao.addToPack("p", listOf("b", "c", "c"), atFront = false, now = 6L))

        assertEquals(listOf("a", "b", "c"), dao.getStickerIds("p"))
    }

    @Test
    fun `stickers added at the front come before the rest, in the order given`() = runTest {
        dao.insertPack(pack("p"))
        dao.addToPack("p", listOf("a"), atFront = false, now = 5L)

        dao.addToPack("p", listOf("x", "y"), atFront = true, now = 6L)
        dao.addToPack("p", listOf("z"), atFront = true, now = 7L)

        assertEquals(listOf("z", "x", "y", "a"), dao.getStickerIds("p"))
    }

    @Test
    fun `packs are observed in sort order with their stickers in position order`() = runTest {
        dao.insertStickers(listOf(sticker("a"), sticker("b")))
        dao.insertPack(pack("second", sortOrder = 1))
        dao.insertPack(pack("first", sortOrder = 0))
        dao.addToPack("first", listOf("b", "a"), atFront = false, now = 5L)
        dao.addToPack("second", listOf("a"), atFront = false, now = 5L)

        assertEquals(listOf("first", "second"), dao.observePacks().first().map { it.id })
        assertEquals(
            listOf("first" to "b", "first" to "a", "second" to "a"),
            dao.observePackStickers().first().map { it.packId to it.sticker.id },
        )
    }

    @Test
    fun `a known sticker keeps its row and gains emojis only when it had none`() = runTest {
        dao.insertStickers(listOf(sticker("bare"), sticker("tagged", listOf("😀"))))

        dao.mergeStickers(
            listOf(
                sticker("bare", listOf("🔥")).copy(width = 1, createdAt = 99L),
                sticker("tagged", listOf("💧")),
                sticker("new", listOf("🌱")),
            )
        )

        val rows = dao.getStickers(listOf("bare", "tagged", "new")).associateBy { it.id }
        assertEquals(sticker("bare", listOf("🔥")), rows["bare"])
        assertEquals(sticker("tagged", listOf("😀")), rows["tagged"])
        assertEquals(sticker("new", listOf("🌱")), rows["new"])
    }

    @Test
    fun `a pack is found again by its import key, and a pack without one is always new`() = runTest {
        val first = dao.getOrCreatePack(pack("x1", importKey = "wa:cats"))
        val again = dao.getOrCreatePack(pack("x2", importKey = "wa:cats"))
        val plain = dao.getOrCreatePack(pack("u1"))
        val plainAgain = dao.getOrCreatePack(pack("u2"))

        assertEquals("x1", again.id)
        assertEquals(listOf("u1", "u2"), listOf(plain.id, plainAgain.id))
        assertEquals(listOf(0, 1, 2), listOf(first, plain, plainAgain).map { it.sortOrder })
        assertEquals(listOf("x1", "u1", "u2"), dao.getPackIds())
    }

    @Test
    fun `a favourite of a sticker the library lacks creates no pack`() = runTest {
        dao.insertStickers(listOf(sticker("a")))

        assertEquals(false, dao.addFavourite(pack("fav", kind = "FAVOURITES", importKey = "kind:FAVOURITES"), "ghost", now = 5L))
        assertEquals(emptyList<String>(), dao.getPackIds())

        assertEquals(true, dao.addFavourite(pack("fav", kind = "FAVOURITES", importKey = "kind:FAVOURITES"), "a", now = 5L))
        assertEquals(true, dao.addFavourite(pack("fav2", kind = "FAVOURITES", importKey = "kind:FAVOURITES"), "a", now = 6L))
        assertEquals(listOf("fav"), dao.getPackIds())
        assertEquals(listOf("a"), dao.getStickerIds("fav"))
    }

    @Test
    fun `a move into a pack that does not exist moves nothing`() = runTest {
        dao.insertPack(pack("from"))
        dao.addToPack("from", listOf("a"), atFront = false, now = 5L)

        assertEquals(false, dao.moveBetweenPacks("from", "gone", listOf("a"), now = 6L))

        assertEquals(listOf("a"), dao.getStickerIds("from"))
        assertEquals(emptyList<String>(), dao.getStickerIds("gone"))
    }

    @Test
    fun `reordering numbers the listed packs first and keeps the others behind them`() = runTest {
        listOf("a", "b", "c", "d").forEachIndexed { index, id -> dao.insertPack(pack(id, sortOrder = index)) }

        dao.reorderPacks(listOf("c", "gone", "a", "c"), now = 9L)

        assertEquals(listOf("c", "a", "b", "d"), dao.getPackIds())
        assertEquals("a pack whose place did not change is not marked", StickerSyncState.SYNCED.name, syncState("d"))
        assertEquals(StickerSyncState.PENDING.name, syncState("c"))
    }

    @Test
    fun `moving takes only what the source holds and skips what the target already has`() = runTest {
        dao.insertPack(pack("from"))
        dao.insertPack(pack("to"))
        dao.addToPack("from", listOf("a", "b", "c"), atFront = false, now = 5L)
        dao.addToPack("to", listOf("b"), atFront = false, now = 5L)

        dao.moveBetweenPacks("from", "to", listOf("a", "b", "stranger"), now = 6L)

        assertEquals(listOf("c"), dao.getStickerIds("from"))
        assertEquals(listOf("b", "a"), dao.getStickerIds("to"))
    }

    @Test
    fun `deleting a pack deletes its items and leaves the stickers`() = runTest {
        dao.insertStickers(listOf(sticker("a")))
        dao.insertPack(pack("p"))
        dao.addToPack("p", listOf("a"), atFront = false, now = 5L)

        dao.deletePack("p")

        assertEquals(emptyList<String>(), dao.getPackIds())
        assertEquals(emptyList<String>(), dao.getStickerIds("p"))
        assertEquals(1, dao.getStickers(listOf("a")).size)
    }

    @Test
    fun `every change to a pack marks it pending and moves its updatedAt`() = runTest {
        suspend fun synced(id: String) = dao.insertPack(pack(id))
        synced("add"); synced("remove"); synced("rename"); synced("from"); synced("to"); synced("idle")
        dao.addToPack("remove", listOf("a"), atFront = false, now = 2L)
        dao.addToPack("from", listOf("a"), atFront = false, now = 2L)
        db.openHelper.writableDatabase.execSQL("UPDATE sticker_packs SET syncState = 'SYNCED', updatedAt = 1")

        dao.addToPack("add", listOf("a"), atFront = false, now = 7L)
        dao.removeFromPack("remove", listOf("a"), now = 7L)
        dao.renamePack("rename", "New", now = 7L)
        dao.moveBetweenPacks("from", "to", listOf("a"), now = 7L)
        dao.addToPack("idle", emptyList(), atFront = false, now = 7L)
        dao.removeFromPack("idle", listOf("never there"), now = 7L)

        listOf("add", "remove", "rename", "from", "to").forEach { id ->
            assertEquals(id, StickerSyncState.PENDING.name, syncState(id))
            assertEquals(id, 7L, dao.getPack(id)!!.updatedAt)
        }
        assertEquals("a write that changed nothing marks nothing", StickerSyncState.SYNCED.name, syncState("idle"))
    }
}
