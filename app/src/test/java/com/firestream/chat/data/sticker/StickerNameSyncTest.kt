package com.firestream.chat.data.sticker

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.firestream.chat.data.local.AppDatabase
import com.firestream.chat.data.local.PreferencesDataStore
import com.firestream.chat.data.local.dao.StickerDao
import com.firestream.chat.data.local.entity.StickerEntity
import com.firestream.chat.data.local.entity.StickerPackEntity
import com.firestream.chat.data.local.entity.StickerPackItemEntity
import com.firestream.chat.data.local.entity.StickerSyncState
import com.firestream.chat.data.remote.source.AuthSource
import com.firestream.chat.data.remote.source.RemoteStickerName
import com.firestream.chat.data.remote.source.StickerNameSource
import com.firestream.chat.data.remote.source.StickerNamingRefusedException
import com.firestream.chat.domain.model.StickerAutoName
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList

/**
 * Who the naming service is asked about, against a real in-memory library:
 * the conditions one at a time, what an answer writes, and what a record that
 * is no answer costs.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [31], manifest = Config.NONE, application = android.app.Application::class)
class StickerNameSyncTest {

    private lateinit var db: AppDatabase
    private lateinit var dao: StickerDao
    private lateinit var sync: StickerNameSync

    private val preferences = mockk<PreferencesDataStore>()
    private val authSource = mockk<AuthSource>()
    private val source = FakeNameSource()

    private var switchOn = true
    private val refusedWrites = CopyOnWriteArrayList<Boolean>()

    /** The backend's records, with what was read and what was asked for. */
    private class FakeNameSource : StickerNameSource {
        override var isSupported = true
        val records = ConcurrentHashMap<String, RemoteStickerName>()
        val fetched = CopyOnWriteArrayList<String>()
        val requested = CopyOnWriteArrayList<Pair<String, String>>()

        /** What a fetch of this id throws, if anything. */
        var failure: (String) -> Throwable? = { null }
        var onFetch: () -> Unit = {}

        override suspend fun fetch(stickerId: String): RemoteStickerName? {
            fetched += stickerId
            onFetch()
            failure(stickerId)?.let { throw it }
            return records[stickerId]
        }

        override suspend fun request(stickerId: String, format: String) {
            requested += stickerId to format
            records.putIfAbsent(stickerId, RemoteStickerName(RemoteStickerName.STATUS_PENDING))
        }
    }

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        dao = db.stickerDao()
        coEvery { preferences.autoNameStickers() } answers { switchOn }
        coEvery { preferences.setStickerNamingRefused(any()) } answers { refusedWrites += firstArg<Boolean>() }
        every { authSource.currentUserId } returns "uid1"
        sync = StickerNameSync(dao, source, preferences, authSource)
    }

    @After
    fun tearDown() {
        db.close()
    }

    private fun id(n: Int) = "%064x".format(n)

    private suspend fun pack(id: String, allowed: Boolean = true, synced: Boolean = true) {
        val state = if (synced) StickerSyncState.SYNCED else StickerSyncState.PENDING
        dao.insertPack(
            StickerPackEntity(
                id = id, name = id, publisher = null, kind = "USER", originPackId = null, importKey = null,
                sortOrder = 0, createdAt = 1L, updatedAt = 1L, syncState = state.name, autoNameAllowed = allowed,
            )
        )
    }

    /** A sticker that is asked for as it stands: a WebP on the backend, open, in the open pack `p`. */
    private suspend fun sticker(n: Int, packId: String? = "p", change: (StickerEntity) -> StickerEntity = { it }): String {
        val row = StickerEntity(
            id = id(n), format = "WEBP", width = 512, height = 512, isAnimated = false, emojis = emptyList(),
            createdAt = n.toLong(), remoteUrl = "https://storage.example/${id(n)}.webp",
        )
        dao.insertStickers(listOf(change(row)))
        if (packId != null) dao.insertItems(listOf(StickerPackItemEntity(packId, row.id, n)))
        return row.id
    }

    private fun done(vararg fields: Pair<String, Any?>) = RemoteStickerName(RemoteStickerName.STATUS_DONE, mapOf(*fields))

    @Test
    fun `a sticker is asked for only when every condition holds`() = runTest {
        pack("p")
        pack("closed", allowed = false)
        val asked = sticker(1)
        sticker(2) { it.copy(autoNameAllowed = false) }
        val inClosedPackToo = sticker(3)
        dao.insertItems(listOf(StickerPackItemEntity("closed", inClosedPackToo, 0)))
        sticker(4) { it.copy(autoNameEn = "Bear") }
        // Not on the backend: this device never uploaded it, and its pack was never backed up.
        pack("not backed up", synced = false)
        sticker(5, packId = "not backed up") { it.copy(remoteUrl = null) }
        val askedToday = sticker(6) { it.copy(nameRequestedAt = NOW - HOUR) }
        sticker(7) { it.copy(format = "LOTTIE") }
        sticker(8, packId = null)
        val askedLongAgo = sticker(9) { it.copy(nameRequestedAt = NOW - 25 * HOUR) }
        // A restored row has no url of its own. Its synced pack says the file is on the backend.
        val restored = sticker(10) { it.copy(remoteUrl = null) }

        val run = sync.run(NOW)

        assertEquals(setOf(asked to "WEBP", askedLongAgo to "WEBP", restored to "WEBP"), source.requested.toSet())
        // One asked within the day is looked up for its answer, and nothing more.
        assertEquals(setOf(asked, askedToday, askedLongAgo, restored), source.fetched.toSet())
        assertEquals("the three requests wait for an answer", 3, run.waiting)
        assertEquals(NOW, dao.getSticker(asked)!!.nameRequestedAt)
        assertEquals(NOW, dao.getSticker(askedLongAgo)!!.nameRequestedAt)
        assertEquals(NOW - HOUR, dao.getSticker(askedToday)!!.nameRequestedAt)
    }

    @Test
    fun `nothing is asked or looked up while the switch is off`() = runTest {
        pack("p")
        sticker(1)
        switchOn = false

        val run = sync.run(NOW)

        assertTrue(source.fetched.isEmpty() && source.requested.isEmpty())
        assertEquals(StickerNameRun(), run)
        assertTrue("nothing was learned about the backend", refusedWrites.isEmpty())
    }

    @Test
    fun `nothing is asked on a backend without a naming service, or with nobody signed in`() = runTest {
        pack("p")
        sticker(1)

        source.isSupported = false
        sync.run(NOW)
        source.isSupported = true
        every { authSource.currentUserId } returns null
        sync.run(NOW)

        assertTrue(source.fetched.isEmpty() && source.requested.isEmpty())
    }

    @Test
    fun `an answer fills the automatic columns and leaves a person's words and the pack alone`() = runTest {
        pack("p")
        val named = sticker(1) { it.copy(emojis = listOf("😺"), names = listOf("Mine")) }
        source.records[named] = done(
            "nameEn" to "Sleepy bear", "nameDe" to "Müder Bär", "words" to listOf("tired", "Schlaf"),
            "text" to "Gute Nacht", "emojis" to listOf("😴"),
        )

        val run = sync.run(NOW)

        val row = dao.getSticker(named)!!
        assertEquals(StickerAutoName("Sleepy bear", "Müder Bär", listOf("tired", "Schlaf"), "Gute Nacht", listOf("😴")), row.autoName)
        assertEquals(listOf("😺"), row.emojis)
        assertEquals(listOf("Mine"), row.names)
        assertTrue("an answer that is there needs no request", source.requested.isEmpty())
        assertEquals(0, run.waiting)
        // No manifest carries what was found, so the pack is not backed up again.
        val pack = dao.getPack("p")!!
        assertEquals(StickerSyncState.SYNCED.name to 1L, pack.syncState to pack.updatedAt)
        assertEquals(listOf(false), refusedWrites)
    }

    @Test
    fun `a request someone else made is not made again, and its answer is taken by a later run`() = runTest {
        pack("p")
        val shared = sticker(1)
        source.records[shared] = RemoteStickerName(RemoteStickerName.STATUS_PENDING)

        assertEquals(1, sync.run(NOW).waiting)
        assertTrue(source.requested.isEmpty())
        assertEquals(NOW, dao.getSticker(shared)!!.nameRequestedAt)

        source.records[shared] = done("nameEn" to "Sleepy bear")
        assertEquals(0, sync.run(NOW + HOUR).waiting)

        assertEquals("Sleepy bear", dao.getSticker(shared)!!.autoNameEn)
        assertTrue(source.requested.isEmpty())
    }

    @Test
    fun `a sticker the service skipped is never asked for, and keeps no run coming back`() = runTest {
        pack("p")
        val skipped = sticker(1)
        source.records[skipped] = RemoteStickerName(RemoteStickerName.STATUS_SKIPPED, mapOf("reason" to "not a picture"))

        val runs = listOf(sync.run(NOW), sync.run(NOW + HOUR), sync.run(NOW + 25 * HOUR))

        assertTrue(source.requested.isEmpty())
        assertEquals(listOf(0, 0, 0), runs.map { it.waiting })
        assertNull(dao.getSticker(skipped)!!.autoName)
        assertEquals("the day starts over with the look after it", NOW + 25 * HOUR, dao.getSticker(skipped)!!.nameRequestedAt)
    }

    @Test
    fun `a malformed answer and a status this build does not know store nothing and ask for nothing`() = runTest {
        pack("p")
        val malformed = sticker(1) { it.copy(names = listOf("Mine")) }
        val unknown = sticker(2)
        source.records[malformed] = done("nameEn" to "Sleepy bear", "words" to "not a list")
        source.records[unknown] = RemoteStickerName("archived", mapOf("nameEn" to "Sleepy bear"))

        val run = sync.run(NOW)

        assertNull(dao.getSticker(malformed)!!.autoName)
        assertEquals(listOf("Mine"), dao.getSticker(malformed)!!.names)
        assertNull(dao.getSticker(unknown)!!.autoName)
        assertTrue(source.requested.isEmpty())
        assertEquals(0, run.waiting)
    }

    @Test
    fun `a record that is gone is asked for again, but not within the day`() = runTest {
        pack("p")
        val asked = sticker(1) { it.copy(nameRequestedAt = NOW - HOUR) }

        sync.run(NOW)
        assertTrue(source.requested.isEmpty())

        sync.run(NOW + 24 * HOUR)
        assertEquals(listOf(asked to "WEBP"), source.requested.toList())
    }

    @Test
    fun `a clock that was set back does not hold a sticker for longer than a day`() = runTest {
        pack("p")
        val asked = sticker(1) { it.copy(nameRequestedAt = NOW + 30 * 24 * HOUR) }

        sync.run(NOW)

        assertEquals(listOf(asked to "WEBP"), source.requested.toList())
    }

    @Test
    fun `a refusal stops the run, is remembered, and switches nothing off`() = runTest {
        pack("p")
        repeat(3 * StickerNameSync.BATCH_SIZE) { sticker(it + 1) }
        source.failure = { StickerNamingRefusedException() }

        val run = sync.run(NOW)

        assertTrue(run.refused)
        assertEquals("the first batch is the last", StickerNameSync.BATCH_SIZE, source.fetched.size)
        assertEquals(listOf(true), refusedWrites)
        coVerify(exactly = 0) { preferences.setAutoNameStickers(any()) }
    }

    @Test
    fun `a refusal after a sign-out says nothing about the backend`() = runTest {
        pack("p")
        sticker(1)
        source.failure = { StickerNamingRefusedException() }
        source.onFetch = { every { authSource.currentUserId } returns null }

        assertTrue(sync.run(NOW).refused)

        assertTrue(refusedWrites.isEmpty())
    }

    @Test
    fun `one sticker's failure is reported and does not stop the others`() = runTest {
        pack("p")
        val failing = sticker(1)
        val fine = sticker(2)
        source.failure = { if (it == failing) IOException("offline") else null }

        val run = sync.run(NOW)

        assertEquals(listOf("offline"), run.failures.map { it.message })
        assertFalse(run.refused)
        assertEquals(listOf(fine to "WEBP"), source.requested.toList())
        assertNull("a look that failed is not a look", dao.getSticker(failing)!!.nameRequestedAt)
    }

    @Test
    fun `a backend that fails every look of a batch is not tried for the rest of the library`() = runTest {
        pack("p")
        repeat(3 * StickerNameSync.BATCH_SIZE) { sticker(it + 1) }
        source.failure = { IOException("offline") }

        val run = sync.run(NOW)

        assertEquals(StickerNameSync.BATCH_SIZE, source.fetched.size)
        assertEquals(StickerNameSync.BATCH_SIZE, run.failures.size)
        assertTrue("nothing was learned about the backend", refusedWrites.isEmpty())
    }

    @Test
    fun `switching naming off stops a run after the batch it is in`() = runTest {
        pack("p")
        repeat(3 * StickerNameSync.BATCH_SIZE) { sticker(it + 1) }
        source.onFetch = { switchOn = false }

        sync.run(NOW)

        assertEquals(StickerNameSync.BATCH_SIZE, source.fetched.size)
    }

    @Test
    fun `a sticker closed while a run works is not asked for in a later batch`() = runTest {
        pack("p")
        val ids = (1..2 * StickerNameSync.BATCH_SIZE).map { sticker(it) }
        val closedLate = ids.last()
        var closed = false
        source.onFetch = {
            if (!closed) {
                closed = true
                db.openHelper.writableDatabase.execSQL("UPDATE stickers SET autoNameAllowed = 0 WHERE id = '$closedLate'")
            }
        }

        sync.run(NOW)

        assertFalse(closedLate in source.fetched)
        assertEquals(2 * StickerNameSync.BATCH_SIZE - 1, source.requested.size)
    }

    private companion object {
        const val HOUR = 60 * 60 * 1000L
        const val NOW = 1_000 * 24 * HOUR
    }
}
