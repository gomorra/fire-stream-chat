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
import org.junit.Assert.assertNull
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

        dao.deletePack("p", now = 9L)

        assertEquals(emptyList<String>(), dao.getPackIds())
        assertEquals(emptyList<String>(), dao.getStickerIds("p"))
        assertEquals(1, dao.getStickers(listOf("a")).size)
    }

    // --- Tidying ---

    @Test
    fun `a merge keeps the first pack, appends the others in order without repeats, and deletes them`() = runTest {
        dao.insertPack(pack("first", importKey = "wa:first", sortOrder = 0))
        dao.insertPack(pack("second", importKey = "wa:second", sortOrder = 1))
        dao.insertPack(pack("third", kind = "INSTALLED", sortOrder = 2))
        dao.addToPack("first", listOf("a", "b"), atFront = false, now = 5L)
        dao.addToPack("second", listOf("c", "a"), atFront = false, now = 5L)
        dao.addToPack("third", listOf("d", "c"), atFront = false, now = 5L)

        assertEquals(true, dao.mergePacks(listOf("first", "third", "second", "third"), "All", keepTombstones = true, now = 9L))

        assertEquals(listOf("first"), dao.getPackIds())
        assertEquals(listOf("a", "b", "d", "c"), dao.getStickerIds("first"))
        val merged = dao.getPack("first")!!
        assertEquals(listOf("All", "wa:first", StickerSyncState.PENDING.name), listOf(merged.name, merged.importKey, merged.syncState))
        assertEquals(StickerSyncState.DELETED.name, dao.getPackRow("second")!!.syncState)
        assertNull("the key of a merged pack is free again", dao.getPackByImportKey("wa:second"))
    }

    @Test
    fun `a merge without a backup leaves no tombstone`() = runTest {
        dao.insertPack(pack("first"))
        dao.insertPack(pack("second"))

        dao.mergePacks(listOf("first", "second"), "All", keepTombstones = false, now = 9L)

        assertNull(dao.getPackRow("second"))
    }

    @Test
    fun `a merge with a pack that is gone changes nothing`() = runTest {
        dao.insertPack(pack("mine"))
        dao.insertPack(pack("other"))
        dao.addToPack("other", listOf("a"), atFront = false, now = 5L)

        assertEquals(false, dao.mergePacks(listOf("mine", "other", "gone"), "All", keepTombstones = true, now = 9L))

        assertEquals(setOf("mine", "other"), dao.getPackIds().toSet())
        assertEquals("mine", dao.getPack("mine")!!.name)
        assertEquals(listOf("a"), dao.getStickerIds("other"))
    }

    @Test
    fun `a new pack holds the stickers the library knows, in order, after every other pack`() = runTest {
        dao.insertStickers(listOf(sticker("a"), sticker("b")))
        dao.insertPack(pack("old", sortOrder = 4))

        assertEquals(listOf("b", "a"), dao.createPack(pack("new"), listOf("b", "ghost", "a", "b")))
        assertEquals(emptyList<String>(), dao.createPack(pack("empty"), listOf("ghost")))

        assertEquals(listOf("old", "new"), dao.getPackIds())
        assertEquals(listOf("b", "a"), dao.getStickerIds("new"))
        assertNull(dao.getPackRow("empty"))
    }

    @Test
    fun `stickers are taken out of every pack that holds them, and only those packs are marked`() = runTest {
        listOf("one", "two", "other").forEach { dao.insertPack(pack(it)) }
        dao.addToPack("one", listOf("a", "b"), atFront = false, now = 5L)
        dao.addToPack("two", listOf("b", "c"), atFront = false, now = 5L)
        dao.addToPack("other", listOf("d"), atFront = false, now = 5L)
        listOf("one", "two", "other").forEach { dao.markSynced(it, dao.getPack(it)!!.updatedAt) }

        dao.removeFromEveryPack(listOf("b", "c"), now = 9L)

        assertEquals(listOf(listOf("a"), emptyList(), listOf("d")), listOf("one", "two", "other").map { dao.getStickerIds(it) })
        assertEquals(
            listOf(StickerSyncState.PENDING.name, StickerSyncState.PENDING.name, StickerSyncState.SYNCED.name),
            listOf("one", "two", "other").map { syncState(it) },
        )
    }

    @Test
    fun `a removal and a pack delete report the stickers that are left in no pack`() = runTest {
        dao.insertPack(pack("one"))
        dao.insertPack(pack("two"))
        dao.addToPack("one", listOf("a", "b", "c"), atFront = false, now = 5L)
        dao.addToPack("two", listOf("b"), atFront = false, now = 5L)

        assertEquals(listOf("a"), dao.removeFromPack("one", listOf("a", "b", "stranger"), now = 6L))
        assertEquals(emptyList<String>(), dao.removeFromPack("one", listOf("stranger"), now = 6L))
        dao.addToPack("one", listOf("b"), atFront = false, now = 7L)
        assertEquals(listOf("c"), dao.deletePackLocally("one", keepTombstone = true, now = 8L))
        assertEquals(listOf("b"), dao.deletePackLocally("two", keepTombstone = false, now = 8L))
    }

    @Test
    fun `the row flag marks the pack for sync only when it changes, and never a tombstone`() = runTest {
        dao.insertPack(pack("p"))
        dao.insertPack(pack("gone"))
        dao.deletePack("gone", now = 2L)

        dao.setShownInRow("p", true, now = 9L)
        assertEquals(StickerSyncState.SYNCED.name, syncState("p"))

        dao.setShownInRow("p", false, now = 9L)
        dao.setShownInRow("gone", false, now = 9L)

        assertEquals(listOf(false, true), listOf(dao.getPack("p")!!.shownInRow, dao.getPackRow("gone")!!.shownInRow))
        assertEquals(StickerSyncState.PENDING.name, syncState("p"))
    }

    // --- Tombstones ---

    @Test
    fun `a deleted pack stays as a tombstone that no library read returns and no edit brings back`() = runTest {
        dao.insertPack(pack("other", sortOrder = 0))
        dao.insertPack(pack("p", importKey = "loose:WhatsApp", sortOrder = 1))

        dao.deletePack("p", now = 9L)
        dao.touchPack("p", now = 10L)
        dao.renamePack("p", "Back again", now = 10L)
        dao.reorderPacks(listOf("other", "p"), now = 10L)

        assertEquals(StickerSyncState.DELETED.name, dao.getPackRow("p")!!.syncState)
        assertNull(dao.getPack("p"))
        assertEquals(listOf("other"), dao.observePacks().first().map { it.id })
        assertNull("the key is given up, so a new pack can take it", dao.getPackByImportKey("loose:WhatsApp"))
        assertEquals(listOf("p"), dao.getUnsyncedPacks().map { it.id })
        assertEquals(false, dao.moveBetweenPacks("other", "p", listOf("a"), now = 11L))
    }

    @Test
    fun `only a tombstone is purged`() = runTest {
        dao.insertPack(pack("gone"))
        dao.insertPack(pack("kept"))
        dao.deletePack("gone", now = 9L)

        dao.purgeTombstone("gone")
        dao.purgeTombstone("kept")

        assertNull(dao.getPackRow("gone"))
        assertEquals("kept", dao.getPackRow("kept")!!.id)
    }

    // --- The sync's writes ---

    @Test
    fun `a pack is marked synced only while it is still what was uploaded`() = runTest {
        dao.insertPack(pack("p"))
        dao.touchPack("p", now = 5L)
        val uploaded = dao.getPackRow("p")!!.updatedAt
        dao.touchPack("p", now = 5L)

        assertEquals("changed during the upload", 0, dao.markSynced("p", uploaded))
        assertEquals(StickerSyncState.PENDING.name, syncState("p"))

        assertEquals(1, dao.markSynced("p", dao.getPackRow("p")!!.updatedAt))
        assertEquals(StickerSyncState.SYNCED.name, syncState("p"))
    }

    @Test
    fun `two changes in one millisecond are two values of updatedAt`() = runTest {
        dao.insertPack(pack("p"))

        dao.touchPack("p", now = 5L)
        dao.renamePack("p", "New", now = 5L)
        dao.touchPack("p", now = 3L)

        assertEquals(7L, dao.getPackRow("p")!!.updatedAt)
    }

    @Test
    fun `a pack is read with its stickers in order, as the manifest lists them`() = runTest {
        dao.insertStickers(listOf(sticker("a"), sticker("b")))
        dao.insertPack(pack("p"))
        dao.addToPack("p", listOf("a", "ghost"), atFront = false, now = 5L)
        dao.addToPack("p", listOf("b"), atFront = true, now = 6L)

        val (row, stickers) = dao.getPackWithStickers("p")!!

        assertEquals("p", row.id)
        assertEquals("an item without a sticker row is not listed", listOf("b", "a"), stickers.map { it.id })
        assertNull(dao.getPackWithStickers("nothing"))
    }

    // --- The restore ---

    private fun remote(id: String, updatedAt: Long, importKey: String? = null, createdAt: Long = 1L, name: String = "Remote") =
        pack(id, importKey = importKey, createdAt = createdAt).copy(name = name, updatedAt = updatedAt, sortOrder = 3)

    @Test
    fun `a pack the library does not have is inserted as synced, with its stickers in order`() = runTest {
        dao.insertStickers(listOf(sticker("a", emojis = listOf("😺"))))

        assertEquals(true, dao.applyRemotePack(remote("r", updatedAt = 8L), listOf(sticker("b"), sticker("a")), now = 20L))

        val row = dao.getPackRow("r")!!
        assertEquals(listOf(StickerSyncState.SYNCED.name, "Remote", "8"), listOf(row.syncState, row.name, row.updatedAt.toString()))
        assertEquals(listOf("b", "a"), dao.getStickerIds("r"))
        assertEquals("a sticker the library already had keeps its row", listOf("😺"), dao.getSticker("a")!!.emojis)
    }

    @Test
    fun `a backend copy replaces the local pack only when it is newer`() = runTest {
        dao.insertStickers(listOf(sticker("a")))
        dao.insertPack(pack("p"))
        dao.addToPack("p", listOf("a"), atFront = false, now = 10L)

        assertEquals("older", false, dao.applyRemotePack(remote("p", updatedAt = 9L), listOf(sticker("b")), now = 20L))
        assertEquals("the same, which is this device's own write coming back", false, dao.applyRemotePack(remote("p", updatedAt = 10L), listOf(sticker("b")), now = 20L))
        assertEquals(listOf("a"), dao.getStickerIds("p"))
        assertEquals(StickerSyncState.PENDING.name, syncState("p"))

        assertEquals(true, dao.applyRemotePack(remote("p", updatedAt = 11L), listOf(sticker("b"), sticker("c")), now = 20L))

        assertEquals(listOf("b", "c"), dao.getStickerIds("p"))
        val row = dao.getPackRow("p")!!
        assertEquals(listOf("Remote", StickerSyncState.SYNCED.name, "11", "3"), listOf(row.name, row.syncState, row.updatedAt.toString(), row.sortOrder.toString()))
    }

    @Test
    fun `a backend copy never brings a deleted pack back`() = runTest {
        dao.insertPack(pack("p"))
        dao.deletePack("p", now = 9L)

        assertEquals(false, dao.applyRemotePack(remote("p", updatedAt = 99L), listOf(sticker("a")), now = 20L))

        assertEquals(StickerSyncState.DELETED.name, dao.getPackRow("p")!!.syncState)
        assertEquals(emptyList<String>(), dao.getStickerIds("p"))
    }

    @Test
    fun `an older backend pack with the same import key takes over, and gains what the local pack had`() = runTest {
        dao.insertStickers(listOf(sticker("mine")))
        dao.insertPack(pack("local", kind = "FAVOURITES", importKey = "kind:FAVOURITES", createdAt = 50L))
        dao.addToPack("local", listOf("mine"), atFront = false, now = 51L)

        dao.applyRemotePack(
            remote("backend", updatedAt = 40L, importKey = "kind:FAVOURITES", createdAt = 5L),
            listOf(sticker("theirs")),
            now = 60L,
        )

        assertEquals(listOf("backend"), dao.getPackIds())
        assertEquals(listOf("theirs", "mine"), dao.getStickerIds("backend"))
        assertEquals("it gained a sticker, so it is uploaded", StickerSyncState.PENDING.name, syncState("backend"))
        assertEquals("the local pack's backend copy is deleted", StickerSyncState.DELETED.name, dao.getPackRow("local")!!.syncState)
        assertEquals("backend", dao.getPackByImportKey("kind:FAVOURITES")!!.id)
    }

    @Test
    fun `a newer backend pack with the same import key is merged into the local one and deleted`() = runTest {
        dao.insertStickers(listOf(sticker("mine")))
        dao.insertPack(pack("local", kind = "FAVOURITES", importKey = "kind:FAVOURITES", createdAt = 5L))
        dao.addToPack("local", listOf("mine"), atFront = false, now = 6L)
        db.openHelper.writableDatabase.execSQL("UPDATE sticker_packs SET syncState = 'SYNCED'")

        dao.applyRemotePack(
            remote("backend", updatedAt = 40L, importKey = "kind:FAVOURITES", createdAt = 50L),
            listOf(sticker("theirs"), sticker("mine")),
            now = 60L,
        )

        assertEquals(listOf("local"), dao.getPackIds())
        assertEquals(listOf("mine", "theirs"), dao.getStickerIds("local"))
        assertEquals(StickerSyncState.PENDING.name, syncState("local"))
        assertEquals(StickerSyncState.DELETED.name, dao.getPackRow("backend")!!.syncState)
        // The tombstone holds against the same manifest arriving again before its delete lands.
        assertEquals(false, dao.applyRemotePack(remote("backend", updatedAt = 99L, importKey = "kind:FAVOURITES", createdAt = 50L), emptyList(), now = 61L))
        assertEquals(listOf("mine", "theirs"), dao.getStickerIds("local"))
    }

    @Test
    fun `a pack the backend dropped is removed only when it has no changes of its own`() = runTest {
        dao.insertPack(pack("synced"))
        dao.insertPack(pack("changed"))
        dao.addToPack("synced", listOf("a"), atFront = false, now = 5L)
        db.openHelper.writableDatabase.execSQL("UPDATE sticker_packs SET syncState = 'SYNCED'")
        dao.touchPack("changed", now = 6L)

        assertEquals(true, dao.removeIfSynced("synced"))
        assertEquals(false, dao.removeIfSynced("changed"))
        assertEquals(false, dao.removeIfSynced("never there"))

        assertEquals(listOf("changed"), dao.getPackIds())
        assertEquals(emptyList<String>(), dao.getStickerIds("synced"))
    }

    // --- Favourites and installed packs ---

    @Test
    fun `a toggle flips a favourite in one step, and refuses a sticker the library lacks`() = runTest {
        dao.insertStickers(listOf(sticker("a")))
        val favourites = pack("fav", kind = "FAVOURITES", importKey = "kind:FAVOURITES")

        assertEquals(true, dao.toggleFavourite(favourites, "a", now = 5L))
        assertEquals(listOf("a"), dao.getStickerIds("fav"))
        assertEquals(false, dao.toggleFavourite(favourites, "a", now = 6L))
        assertEquals(emptyList<String>(), dao.getStickerIds("fav"))
        assertNull(dao.toggleFavourite(favourites, "ghost", now = 7L))
    }

    @Test
    fun `a made sticker lands at the end of its pack and marks it pending, or writes nothing for a pack that is gone`() = runTest {
        dao.insertPack(pack("mine").copy(syncState = StickerSyncState.SYNCED.name))

        assertEquals(true, dao.addMadeSticker("mine", sticker("a"), now = 8L))
        assertEquals(true, dao.addMadeSticker("mine", sticker("b"), now = 9L))
        assertEquals(false, dao.addMadeSticker("ghost", sticker("c"), now = 9L))

        assertEquals(listOf("a", "b"), dao.getStickerIds("mine"))
        assertEquals(StickerSyncState.PENDING.name, syncState("mine"))
        assertNull(dao.getSticker("c"))
    }

    @Test
    fun `a pack is installed once per import key, after every other pack`() = runTest {
        dao.insertPack(pack("first", sortOrder = 4))
        val copy = pack("copy", kind = "INSTALLED", importKey = "installed:root").copy(syncState = StickerSyncState.PENDING.name)

        assertEquals(true, dao.installPack(copy, listOf(sticker("a"), sticker("b"))))
        assertEquals(false, dao.installPack(copy.copy(id = "copy2"), listOf(sticker("c"))))

        assertEquals(listOf("first", "copy"), dao.getPackIds())
        assertEquals(listOf("a", "b"), dao.getStickerIds("copy"))
        assertEquals(StickerSyncState.PENDING.name, syncState("copy"))
        assertNull(dao.getSticker("c"))
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
