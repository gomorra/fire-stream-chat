package com.firestream.chat.data.worker

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.work.ListenableWorker
import androidx.work.ListenableWorker.Result
import androidx.work.WorkerFactory
import androidx.work.WorkerParameters
import androidx.work.testing.TestListenableWorkerBuilder
import com.firestream.chat.data.local.AppDatabase
import com.firestream.chat.data.local.dao.StickerDao
import com.firestream.chat.data.local.entity.StickerEntity
import com.firestream.chat.data.local.entity.StickerPackEntity
import com.firestream.chat.data.local.entity.StickerSyncState
import com.firestream.chat.data.remote.source.AuthSource
import com.firestream.chat.data.remote.source.RemoteStickerPack
import com.firestream.chat.data.remote.source.SendErrorClassifier
import com.firestream.chat.data.remote.source.SendFailure
import com.firestream.chat.data.remote.source.StickerObjectSource
import com.firestream.chat.data.remote.source.StickerPackSource
import com.firestream.chat.data.sticker.StickerFiles
import com.firestream.chat.data.sticker.StickerUploads
import com.firestream.chat.test.WebpFixtures.sticker
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.IOException
import java.util.concurrent.CopyOnWriteArrayList

/**
 * The backup against a real in-memory library and a real sticker directory:
 * what a run uploads, what it writes, and which packs it leaves unsynced.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [31], manifest = Config.NONE, application = android.app.Application::class)
class StickerSyncWorkerTest {

    private val context = ApplicationProvider.getApplicationContext<Context>()
    private lateinit var db: AppDatabase
    private lateinit var dao: StickerDao
    private lateinit var files: StickerFiles

    private val objectSource = mockk<StickerObjectSource>()
    private val packSource = mockk<StickerPackSource>()
    private val authSource = mockk<AuthSource>()

    /** The neutral rules only — no backend here. */
    private val classifier = object : SendErrorClassifier {
        override fun classifyBackendError(error: Throwable): SendFailure? = null
    }

    // Thread-safe: the worker uploads several files at once on Dispatchers.IO,
    // and an append to a plain list from two threads can lose one of them.
    private val uploaded = CopyOnWriteArrayList<String>()
    private val written = CopyOnWriteArrayList<RemoteStickerPack>()
    private val deleted = CopyOnWriteArrayList<String>()

    @Before
    fun setUp() {
        db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        dao = db.stickerDao()
        files = StickerFiles(context)
        every { authSource.currentUserId } returns "uid1"
        coEvery { objectSource.ensureUploaded(any(), any(), any(), any()) } answers {
            uploaded += firstArg<String>()
            "https://storage.example/stickers/${firstArg<String>()}.webp"
        }
        coEvery { packSource.writePack(any()) } answers { written += firstArg<RemoteStickerPack>() }
        coEvery { packSource.deletePack(any()) } answers { deleted += firstArg<String>() }
    }

    @After
    fun tearDown() {
        db.close()
        context.filesDir.deleteRecursively()
    }

    private fun worker(): StickerSyncWorker =
        TestListenableWorkerBuilder<StickerSyncWorker>(context)
            .setWorkerFactory(object : WorkerFactory() {
                override fun createWorker(
                    appContext: Context,
                    workerClassName: String,
                    workerParameters: WorkerParameters,
                ): ListenableWorker = StickerSyncWorker(
                    appContext, workerParameters, dao, files, StickerUploads(dao, files, objectSource), packSource, authSource, classifier,
                )
            })
            .build()

    /** A sticker with its file in the directory and its row in the library. Returns its id. */
    private suspend fun imported(seed: Int): String {
        val stored = files.store(sticker(seed))!!
        dao.insertStickers(listOf(StickerEntity.of(stored, emptyList(), now = 1L)))
        return stored.id
    }

    /** A pending pack holding [stickerIds]. */
    private suspend fun pendingPack(id: String, vararg stickerIds: String, importKey: String? = null) {
        dao.insertPack(
            StickerPackEntity(
                id = id, name = id, publisher = null, kind = "USER", originPackId = null, importKey = importKey,
                sortOrder = 0, createdAt = 1L, updatedAt = 1L,
            )
        )
        dao.addToPack(id, stickerIds.toList(), atFront = false, now = 5L)
    }

    private suspend fun syncState(packId: String) = dao.getPackRow(packId)?.syncState

    @Test
    fun `a pending pack has its files uploaded and its manifest written, and is then synced`() = runTest {
        val first = imported(1)
        val second = imported(2)
        pendingPack("cats", first, second, importKey = "loose:WhatsApp")

        assertEquals(Result.success(), worker().doWork())

        assertEquals(setOf(first, second), uploaded.toSet())
        val manifest = written.single()
        assertEquals(listOf("cats", "uid1", "loose:WhatsApp"), listOf(manifest.id, manifest.ownerId, manifest.importKey))
        assertEquals(listOf(first, second), manifest.stickers.map { it.id })
        assertEquals(StickerSyncState.SYNCED.name, syncState("cats"))
        assertEquals("https://storage.example/stickers/$first.webp", dao.getSticker(first)!!.remoteUrl)
    }

    @Test
    fun `a file is uploaded once, however often its packs are synced`() = runTest {
        val shared = imported(1)
        pendingPack("cats", shared)
        pendingPack("favourites", shared)
        worker().doWork()

        dao.touchPack("cats", now = 9L)
        assertEquals(Result.success(), worker().doWork())

        assertEquals(listOf(shared), uploaded)
        assertEquals(listOf("cats", "cats", "favourites"), written.map { it.id }.sorted())
    }

    @Test
    fun `a sticker whose file is not on this device is listed and not uploaded`() = runTest {
        val restored = "a".repeat(64)
        dao.insertStickers(listOf(StickerEntity(restored, "WEBP", 512, 512, false, emptyList(), createdAt = 1L)))
        pendingPack("cats", restored)

        assertEquals(Result.success(), worker().doWork())

        assertEquals(emptyList<String>(), uploaded)
        assertEquals(listOf(restored), written.single().stickers.map { it.id })
        assertEquals(StickerSyncState.SYNCED.name, syncState("cats"))
    }

    @Test
    fun `a deleted pack has its manifest deleted and its tombstone dropped`() = runTest {
        pendingPack("cats", imported(1))
        dao.deletePack("cats", now = 9L)

        assertEquals(Result.success(), worker().doWork())

        assertEquals(listOf("cats"), deleted)
        assertEquals(emptyList<RemoteStickerPack>(), written)
        assertNull(dao.getPackRow("cats"))
    }

    @Test
    fun `a pack that changes during its upload is uploaded again before the run ends`() = runTest {
        val first = imported(1)
        val second = imported(2)
        pendingPack("cats", first)
        coEvery { packSource.writePack(any()) } coAnswers {
            written += firstArg<RemoteStickerPack>()
            if (written.size == 1) dao.addToPack("cats", listOf(second), atFront = false, now = 9L)
        }

        assertEquals(Result.success(), worker().doWork())

        assertEquals(listOf(listOf(first), listOf(first, second)), written.map { pack -> pack.stickers.map { it.id } })
        assertEquals(StickerSyncState.SYNCED.name, syncState("cats"))
    }

    @Test
    fun `a pack that changes while its files upload is written once, as it is afterwards`() = runTest {
        val first = imported(1)
        val second = imported(2)
        pendingPack("cats", first)
        coEvery { objectSource.ensureUploaded(first, any(), any(), any()) } coAnswers {
            dao.addToPack("cats", listOf(second), atFront = false, now = 9L)
            "https://storage.example/stickers/$first.webp"
        }

        assertEquals(Result.success(), worker().doWork())

        assertEquals(listOf(listOf(first, second)), written.map { pack -> pack.stickers.map { it.id } })
        assertEquals(StickerSyncState.SYNCED.name, syncState("cats"))
    }

    @Test
    fun `a pack that never stops changing is left for a later run`() = runTest {
        pendingPack("cats", imported(1))
        coEvery { packSource.writePack(any()) } coAnswers {
            written += firstArg<RemoteStickerPack>()
            dao.touchPack("cats", now = 9L)
        }

        assertEquals(Result.retry(), worker().doWork())

        assertEquals(StickerSyncWorker.MAX_PASSES, written.size)
        assertEquals(StickerSyncState.PENDING.name, syncState("cats"))
    }

    @Test
    fun `a write that fails for the network is retried, and the other packs are still synced`() = runTest {
        pendingPack("cats", imported(1))
        pendingPack("dogs", imported(2))
        coEvery { packSource.writePack(match { it.id == "cats" }) } throws IOException("offline")

        assertEquals(Result.retry(), worker().doWork())

        assertEquals(StickerSyncState.PENDING.name, syncState("cats"))
        assertEquals(StickerSyncState.SYNCED.name, syncState("dogs"))
        coVerify(exactly = 1) { packSource.writePack(match { it.id == "cats" }) }
    }

    @Test
    fun `a write the backend refuses fails the run and leaves the pack pending`() = runTest {
        pendingPack("cats", imported(1))
        coEvery { packSource.writePack(any()) } throws SecurityException("PERMISSION_DENIED")

        assertEquals(Result.failure(), worker().doWork())

        assertEquals(StickerSyncState.PENDING.name, syncState("cats"))
    }

    @Test
    fun `an upload that fails keeps the manifest back`() = runTest {
        pendingPack("cats", imported(1))
        coEvery { objectSource.ensureUploaded(any(), any(), any(), any()) } throws IOException("offline")

        assertEquals(Result.retry(), worker().doWork())

        assertEquals(emptyList<RemoteStickerPack>(), written)
        assertEquals(StickerSyncState.PENDING.name, syncState("cats"))
    }

    @Test
    fun `with nobody signed in nothing is written`() = runTest {
        pendingPack("cats", imported(1))
        every { authSource.currentUserId } returns null

        assertEquals(Result.success(), worker().doWork())

        assertEquals(emptyList<String>(), uploaded)
        assertEquals(emptyList<RemoteStickerPack>(), written)
        assertEquals(StickerSyncState.PENDING.name, syncState("cats"))
    }
}
