package com.firestream.chat.data.repository

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.firestream.chat.data.local.AppDatabase
import com.firestream.chat.data.local.PreferencesDataStore
import com.firestream.chat.data.local.entity.StickerEntity
import com.firestream.chat.data.local.entity.StickerSyncState
import com.firestream.chat.data.remote.source.RemoteSticker
import com.firestream.chat.data.remote.source.RemoteStickerPack
import com.firestream.chat.data.remote.source.StickerObjectSource
import com.firestream.chat.data.remote.source.StickerPackSource
import com.firestream.chat.data.sticker.StickerDownloads
import com.firestream.chat.data.sticker.StickerFiles
import com.firestream.chat.data.sticker.StickerMaker
import com.firestream.chat.data.sticker.WhatsAppStickerFolder
import com.firestream.chat.data.worker.StickerSyncScheduler
import com.firestream.chat.domain.model.Sticker
import com.firestream.chat.domain.model.StickerCrop
import com.firestream.chat.domain.model.StickerDraft
import com.firestream.chat.domain.model.StickerDraftImage
import com.firestream.chat.domain.model.StickerFormat
import com.firestream.chat.domain.model.StickerImportResult
import com.firestream.chat.domain.model.StickerPack
import com.firestream.chat.domain.model.StickerPackKind
import com.firestream.chat.domain.model.WhatsAppStickerFile
import com.firestream.chat.test.LottieFixtures.animation
import com.firestream.chat.test.LottieFixtures.tgs
import com.firestream.chat.test.LottieFixtures.waProps
import com.firestream.chat.test.LottieFixtures.was
import com.firestream.chat.test.WebpFixtures.sticker
import com.firestream.chat.test.WebpFixtures.waJson
import com.firestream.chat.test.WebpFixtures.zip
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import okhttp3.Call
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
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
import java.io.File
import java.io.IOException

/**
 * The library against a real in-memory database and a real sticker directory:
 * an import groups by pack metadata and de-duplicates by hash, and the pack edits.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [31], manifest = Config.NONE, application = android.app.Application::class)
class StickerRepositoryImplTest {

    private val context = ApplicationProvider.getApplicationContext<Context>()
    private lateinit var db: AppDatabase
    private lateinit var files: StickerFiles
    private lateinit var repository: StickerRepositoryImpl

    private val recentIds = MutableStateFlow(emptyList<String>())
    private val preferences = mockk<PreferencesDataStore>()
    private val folder = mockk<WhatsAppStickerFolder>()
    private val objectSource = mockk<StickerObjectSource>()
    private val packSource = mockk<StickerPackSource> { every { isSupported } returns true }
    private val scheduler = mockk<StickerSyncScheduler>(relaxed = true)
    private val httpClient = mockk<OkHttpClient>()
    private val maker = mockk<StickerMaker>()

    @Before
    fun setUp() {
        db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        files = StickerFiles(context)
        every { preferences.recentStickerIdsFlow } returns recentIds
        coEvery { preferences.addRecentSticker(any()) } answers {
            recentIds.value = listOf(firstArg<String>()) + (recentIds.value - firstArg<String>())
        }
        repository = newStickerRepository(
            stickerDao = db.stickerDao(),
            stickerFiles = files,
            whatsAppFolder = folder,
            preferences = preferences,
            stickerDownloads = StickerDownloads(files, db.stickerDao(), httpClient),
            stickerObjectSource = objectSource,
            packSource = packSource,
            syncScheduler = scheduler,
            stickerMaker = maker,
        )
    }

    /** Makes every download answer with [body]. */
    private fun serve(body: ByteArray) {
        every { httpClient.newCall(any()) } answers {
            mockk<Call> {
                every { execute() } returns Response.Builder()
                    .request(firstArg<Request>())
                    .protocol(Protocol.HTTP_1_1).code(200).message("OK")
                    .body(body.toResponseBody())
                    .build()
            }
        }
    }

    private fun remoteSticker(id: String, format: String = "WEBP") =
        RemoteSticker(id = id, format = format, width = 512, height = 512, isAnimated = false, emojis = listOf("😺"))

    private fun remotePack(
        id: String,
        ownerId: String = "someone",
        originPackId: String? = null,
        stickers: List<RemoteSticker>,
    ) = RemoteStickerPack(
        id = id, ownerId = ownerId, name = "Shared cats", publisher = "Ana", kind = "USER", originPackId = originPackId,
        importKey = "wa:their-key", sortOrder = 4, createdAt = 10L, updatedAt = 20L, stickers = stickers,
    )

    @After
    fun tearDown() {
        db.close()
        context.filesDir.deleteRecursively()
        context.cacheDir.deleteRecursively()
    }

    /** Writes [bytes] where a picker would point, and returns the path. */
    private fun source(name: String, bytes: ByteArray): String =
        File(context.cacheDir, "picked/$name").apply {
            parentFile!!.mkdirs()
            writeBytes(bytes)
        }.absolutePath

    private suspend fun packs(): List<StickerPack> = repository.observePacks().first()

    private suspend fun pack(name: String): StickerPack = packs().single { it.name == name }

    private fun storedFiles(): List<String> = files.dir.list()?.sorted().orEmpty()

    private val cats = waJson("com.cats", "Cats", "Ana", listOf("😺"))
    private val dogs = waJson("com.dogs", "Dogs", "Ben", listOf("🐶"))

    // --- Import: grouping ---

    @Test
    fun `an import groups stickers by the pack their metadata names`() = runTest {
        val uris = listOf(
            source("1.webp", sticker(1, cats)),
            source("2.webp", sticker(2, dogs)),
            source("3.webp", sticker(3, cats, animated = true)),
            source("4.webp", sticker(4)),
        )

        val result = repository.importFrom(uris, loosePackName = "WhatsApp").getOrThrow()

        assertEquals(4, result.imported)
        assertEquals(0, result.duplicates)
        assertEquals(0, result.rejected)
        val all = packs()
        assertEquals(listOf("Cats", "Dogs", "WhatsApp"), all.map { it.name })
        assertEquals(all.map { it.id }, result.packIds)
        assertTrue(all.all { it.kind == StickerPackKind.USER })
        assertEquals("Ana", pack("Cats").publisher)
        assertEquals(listOf(StickerFiles.sha256Hex(sticker(1, cats)), StickerFiles.sha256Hex(sticker(3, cats, animated = true))), pack("Cats").stickers.map { it.id })
        assertEquals(listOf(false, true), pack("Cats").stickers.map { it.isAnimated })
        assertEquals(listOf("😺"), pack("Cats").stickers.first().emojis)
        assertEquals(1, pack("WhatsApp").stickers.size)
    }

    @Test
    fun `a sticker points at its file in the sticker directory`() = runTest {
        repository.importFrom(listOf(source("1.webp", sticker(1, cats)))).getOrThrow()

        val imported = pack("Cats").stickers.single()

        assertEquals(File(files.dir, "${imported.id}.webp").absolutePath, imported.localPath)
        assertTrue(File(imported.localPath).isFile)
    }

    // --- Import: Lottie ---

    @Test
    fun `a was file from the WhatsApp folder joins the pack its animation names, as a Lottie sticker`() = runTest {
        val json = animation(1, customProps = waProps("SchoolDays", listOf("🚌", "👍")))

        val result = repository.importFrom(listOf(source("STK-1.was", was(json))), loosePackName = "WhatsApp").getOrThrow()

        assertEquals(1, result.imported)
        assertEquals(0, result.rejected)
        // WhatsApp's own Lottie packs have an id and no name.
        val imported = pack("SchoolDays").stickers.single()
        assertEquals(StickerFormat.LOTTIE, imported.format)
        assertTrue(imported.isAnimated)
        assertEquals(listOf("🚌", "👍"), imported.emojis)
        assertEquals(File(files.dir, "${imported.id}.tgs").absolutePath, imported.localPath)
        assertEquals(imported.localPath + ".png", imported.stillPath)
        assertTrue(File(imported.localPath).isFile)
    }

    @Test
    fun `a tgs file is told by its bytes, whatever it is named, and a second import adds nothing`() = runTest {
        val uri = source("sticker.bin", tgs(animation(2)))

        val first = repository.importFrom(listOf(uri)).getOrThrow()
        val again = repository.importFrom(listOf(uri)).getOrThrow()

        assertEquals(1, first.imported)
        assertEquals(1, again.duplicates)
        val saved = packs().single()
        assertEquals(StickerPackKind.SAVED, saved.kind)
        assertEquals(StickerFormat.LOTTIE, saved.stickers.single().format)
    }

    @Test
    fun `a was file whose animation is refused counts as rejected and leaves no file`() = runTest {
        val notAnAnimation = was("""{"some":"json"}""".toByteArray())

        val result = repository.importFrom(listOf(source("bad.was", notAnAnimation), source("1.webp", sticker(1)))).getOrThrow()

        assertEquals(1, result.imported)
        assertEquals(1, result.rejected)
        assertEquals(1, storedFiles().size)
    }

    @Test
    fun `without a loose pack name a sticker with no metadata lands in the SAVED pack`() = runTest {
        repository.importFrom(listOf(source("1.webp", sticker(1)))).getOrThrow()
        repository.importFrom(listOf(source("2.webp", sticker(2)))).getOrThrow()

        val saved = packs().single()
        assertEquals(StickerPackKind.SAVED, saved.kind)
        assertEquals("", saved.name)
        assertEquals(2, saved.stickers.size)
    }

    @Test
    fun `two packs that share an id but not a name or publisher stay apart`() = runTest {
        val uris = listOf(
            source("1.webp", sticker(1, waJson("1", "Cats", "Ana"))),
            source("2.webp", sticker(2, waJson("1", "Dogs", "Ben"))),
        )

        repository.importFrom(uris).getOrThrow()

        assertEquals(listOf("Cats", "Dogs"), packs().map { it.name })
    }

    @Test
    fun `pack ids are random, so two libraries never claim the same backend document`() = runTest {
        repository.importFrom(listOf(source("1.webp", sticker(1, cats)))).getOrThrow()
        val first = pack("Cats").id
        repository.deletePack(first).getOrThrow()

        repository.importFrom(listOf(source("1.webp", sticker(1, cats)))).getOrThrow()

        assertFalse(first == pack("Cats").id)
    }

    // --- Import: de-duplication ---

    @Test
    fun `importing the same files again adds nothing`() = runTest {
        val uris = listOf(source("1.webp", sticker(1, cats)), source("2.webp", sticker(2)))
        repository.importFrom(uris, "WhatsApp").getOrThrow()

        val again = repository.importFrom(uris, "WhatsApp").getOrThrow()

        assertEquals(0, again.imported)
        assertEquals(2, again.duplicates)
        assertEquals(emptyList<String>(), again.packIds)
        assertEquals(2, packs().size)
        assertEquals(2, storedFiles().size)
    }

    @Test
    fun `the same bytes under two names are one sticker`() = runTest {
        val uris = listOf(source("a.webp", sticker(1, cats)), source("b.webp", sticker(1, cats)))

        val result = repository.importFrom(uris + uris).getOrThrow()

        assertEquals(1, result.imported)
        assertEquals(1, result.duplicates)
        assertEquals(1, pack("Cats").stickers.size)
        assertEquals(1, storedFiles().size)
    }

    @Test
    fun `a renamed pack is still the one a later import adds to`() = runTest {
        repository.importFrom(listOf(source("1.webp", sticker(1, cats)))).getOrThrow()
        repository.renamePack(pack("Cats").id, "My cats").getOrThrow()

        repository.importFrom(listOf(source("2.webp", sticker(2, cats)))).getOrThrow()

        assertEquals(listOf("My cats"), packs().map { it.name })
        assertEquals(2, pack("My cats").stickers.size)
    }

    @Test
    fun `a sticker already in the library can join a second pack without a second file`() = runTest {
        val uri = source("1.webp", sticker(1))
        repository.importFrom(listOf(uri), "First").getOrThrow()

        val result = repository.importFrom(listOf(uri), "Second").getOrThrow()

        assertEquals(1, result.imported)
        assertEquals(pack("First").stickers, pack("Second").stickers)
        assertEquals(1, storedFiles().size)
    }

    // --- Import: refusals ---

    @Test
    fun `files that cannot be read or are not stickers are counted and skipped`() = runTest {
        val uris = listOf(
            source("ok.webp", sticker(1, cats)),
            source("text.webp", "hello".toByteArray()),
            File(context.cacheDir, "missing.webp").absolutePath,
            source("cut.webp", sticker(2, cats).copyOf(40)),
            source("big.webp", sticker(3, cats, fillerBytes = StickerFiles.MAX_BYTES)),
        )

        val result = repository.importFrom(uris).getOrThrow()

        assertEquals(1, result.imported)
        assertEquals(4, result.rejected)
        assertEquals(1, storedFiles().size)
    }

    @Test
    fun `an import of nothing but refusals makes no pack`() = runTest {
        val result = repository.importFrom(listOf(source("x.webp", ByteArray(12))), "WhatsApp").getOrThrow()

        assertEquals(1, result.rejected)
        assertEquals(emptyList<StickerPack>(), packs())
    }

    // --- Import: archives ---

    @Test
    fun `an archive with a title is one pack, whatever its stickers say`() = runTest {
        val archive = zip(
            "1.webp" to sticker(1, cats),
            "2.webp" to sticker(2),
            "title.txt" to "Holiday".toByteArray(),
            "author.txt" to "Cleo".toByteArray(),
            "tray.png" to ByteArray(50),
            "broken.webp" to "nope".toByteArray(),
        )
        val uri = source("holiday.wastickers", archive)

        val result = repository.importFrom(listOf(uri)).getOrThrow()
        val again = repository.importFrom(listOf(uri)).getOrThrow()

        assertEquals(2, result.imported)
        assertEquals(1, result.rejected)
        assertEquals(0, again.imported)
        assertEquals(2, again.duplicates)
        val pack = packs().single()
        assertEquals("Holiday", pack.name)
        assertEquals("Cleo", pack.publisher)
        assertEquals(listOf(listOf("😺"), emptyList()), pack.stickers.map { it.emojis })
    }

    @Test
    fun `an archive is recognised by its bytes, not by its name`() = runTest {
        val uri = source("looks-like-a-sticker.webp", zip("1.webp" to sticker(1), "title.txt" to "Disguised".toByteArray()))

        repository.importFrom(listOf(uri)).getOrThrow()

        assertEquals(listOf("Disguised"), packs().map { it.name })
    }

    @Test
    fun `an archive without a title groups its stickers like loose files`() = runTest {
        val uri = source("pack.zip", zip("1.webp" to sticker(1, cats), "2.webp" to sticker(2)))

        repository.importFrom(listOf(uri), "WhatsApp").getOrThrow()

        assertEquals(listOf("Cats", "WhatsApp"), packs().map { it.name })
    }

    @Test
    fun `an archive that breaks a cap is refused whole and leaves no file behind`() = runTest {
        val entries = (1..201).map { "$it.webp" to sticker(it) }.toTypedArray()
        val kept = source("kept.webp", sticker(1))
        repository.importFrom(listOf(kept), "Mine").getOrThrow()

        val result = repository.importFrom(listOf(source("bomb.wastickers", zip(*entries)))).getOrThrow()

        assertEquals(0, result.imported)
        assertEquals(1, result.rejected)
        assertEquals("only the sticker the library already had is still on disk", 1, storedFiles().size)
        assertEquals(listOf("Mine"), packs().map { it.name })
    }

    @Test
    fun `an archive the zip reader chokes on is one refusal, leaves no file and does not stop the import`() = runTest {
        val badName = zip(Charsets.ISO_8859_1, "1.webp" to sticker(1), "café.webp" to sticker(2))
        val uris = listOf(source("windows.zip", badName), source("3.webp", sticker(3, cats)))

        val result = repository.importFrom(uris).getOrThrow()

        assertEquals(1, result.imported)
        assertEquals(1, result.rejected)
        assertEquals(listOf("Cats"), packs().map { it.name })
        assertEquals(1, storedFiles().size)
    }

    @Test
    fun `a zip that is not a sticker pack is counted as refused`() = runTest {
        val uri = source("report.docx", zip("word/document.xml" to ByteArray(40)))

        val result = repository.importFrom(listOf(uri)).getOrThrow()

        assertEquals(StickerImportResult(imported = 0, duplicates = 0, rejected = 1, packIds = emptyList()), result)
        assertEquals(emptyList<StickerPack>(), packs())
    }

    // --- Pack edits ---

    @Test
    fun `a favourite joins the favourites pack at the front and leaves it again`() = runTest {
        repository.importFrom(listOf(source("1.webp", sticker(1, cats)), source("2.webp", sticker(2, cats)))).getOrThrow()
        val (first, second) = pack("Cats").stickers.map { it.id }

        assertTrue(repository.toggleFavourite(first).getOrThrow())
        assertTrue(repository.toggleFavourite(second).getOrThrow())

        val favourites = packs().single { it.kind == StickerPackKind.FAVOURITES }
        assertEquals(listOf(second, first), favourites.stickers.map { it.id })
        assertEquals("the sticker stays in its own pack", 2, pack("Cats").stickers.size)

        assertFalse(repository.toggleFavourite(second).getOrThrow())

        assertEquals(listOf(first), packs().single { it.kind == StickerPackKind.FAVOURITES }.stickers.map { it.id })
    }

    @Test
    fun `a sticker the library does not have cannot be a favourite`() = runTest {
        assertTrue(repository.toggleFavourite("f".repeat(64)).isFailure)
        assertEquals(emptyList<StickerPack>(), packs())
    }

    // --- Backup ---

    @Test
    fun `a deleted pack leaves a tombstone for the backup, and a new import of its source starts a new pack`() = runTest {
        repository.importFrom(listOf(source("1.webp", sticker(1, cats)))).getOrThrow()
        val deleted = pack("Cats").id

        repository.deletePack(deleted).getOrThrow()

        assertEquals(emptyList<StickerPack>(), packs())
        val tombstone = db.stickerDao().getPackRow(deleted)!!
        assertEquals(StickerSyncState.DELETED.name, tombstone.syncState)
        assertNull("the key is free for the next import", tombstone.importKey)

        repository.importFrom(listOf(source("1.webp", sticker(1, cats)))).getOrThrow()
        assertEquals(1, pack("Cats").stickers.size)
    }

    @Test
    fun `a backend that keeps no packs gets no tombstone`() = runTest {
        every { packSource.isSupported } returns false
        repository.importFrom(listOf(source("1.webp", sticker(1, cats)))).getOrThrow()
        val deleted = pack("Cats").id

        repository.deletePack(deleted).getOrThrow()

        assertNull(db.stickerDao().getPackRow(deleted))
    }

    @Test
    fun `every change to a pack asks for its backup`() = runTest {
        repository.importFrom(listOf(source("1.webp", sticker(1, cats)), source("2.webp", sticker(2, dogs)))).getOrThrow()
        val cats = pack("Cats")
        val dogs = pack("Dogs")
        val sticker = cats.stickers.single().id

        repository.toggleFavourite(sticker).getOrThrow()
        repository.renamePack(cats.id, "Kittens").getOrThrow()
        repository.reorderPacks(listOf(dogs.id, cats.id)).getOrThrow()
        repository.moveStickers(listOf(sticker), cats.id, dogs.id).getOrThrow()
        repository.removeStickers(dogs.id, listOf(sticker)).getOrThrow()
        repository.deletePack(cats.id).getOrThrow()

        // The import, and the six edits.
        coVerify(exactly = 7) { scheduler.syncIfPending() }
    }

    // --- A sticker whose row came before its file ---

    private suspend fun restoredRow(bytes: ByteArray): Sticker {
        val id = StickerFiles.sha256Hex(bytes)
        db.stickerDao().insertStickers(
            listOf(StickerEntity(id = id, format = "WEBP", width = 512, height = 512, isAnimated = false, emojis = emptyList(), createdAt = 1L))
        )
        return Sticker(id, StickerFormat.WEBP, 512, 512, false, emptyList(), files.fileFor(id, StickerFormat.WEBP).absolutePath)
    }

    @Test
    fun `a missing file is fetched from the object its id names`() = runTest {
        val bytes = sticker(1, cats)
        val restored = restoredRow(bytes)
        coEvery { objectSource.urlIfPresent(restored.id, "webp") } returns OBJECT_URL
        serve(bytes)

        assertTrue(repository.ensureFile(restored))

        assertTrue(File(restored.localPath).isFile)
        assertNull("the url on the row is StickerUploads' to write", db.stickerDao().getSticker(restored.id)!!.remoteUrl)
        // Held now: nobody is asked a second time.
        assertTrue(repository.ensureFile(restored))
        coVerify(exactly = 1) { objectSource.urlIfPresent(any(), any()) }
    }

    @Test
    fun `an object that serves other bytes gives no file and no url`() = runTest {
        val restored = restoredRow(sticker(1, cats))
        coEvery { objectSource.urlIfPresent(restored.id, "webp") } returns OBJECT_URL
        serve(sticker(2, dogs))

        assertFalse(repository.ensureFile(restored))

        assertEquals(emptyList<String>(), storedFiles())
        assertNull(db.stickerDao().getSticker(restored.id)!!.remoteUrl)
    }

    @Test
    fun `a failed lookup, a sticker the backend does not hold and an id that is no hash all answer false`() = runTest {
        val restored = restoredRow(sticker(1, cats))
        coEvery { objectSource.urlIfPresent(restored.id, "webp") } throws IOException("offline")
        assertFalse(repository.ensureFile(restored))

        coEvery { objectSource.urlIfPresent(restored.id, "webp") } returns null
        assertFalse("a failed lookup is not remembered", repository.ensureFile(restored))
        coVerify(exactly = 2) { objectSource.urlIfPresent(any(), any()) }

        assertFalse("an object that is not there is not asked for again", repository.ensureFile(restored))
        coVerify(exactly = 2) { objectSource.urlIfPresent(any(), any()) }

        assertFalse(repository.ensureFile(restored.copy(id = "../../databases/app")))
        verify(exactly = 0) { httpClient.newCall(any()) }
    }

    @Test
    fun `a cell that scrolls away cancels its own fetch, and the next cell fetches the file`() = runTest {
        val bytes = sticker(1, cats)
        val restored = restoredRow(bytes)
        val asked = CompletableDeferred<Unit>()
        coEvery { objectSource.urlIfPresent(restored.id, "webp") } coAnswers {
            asked.complete(Unit)
            awaitCancellation()
        } andThen OBJECT_URL
        serve(bytes)

        val cell = launch { repository.ensureFile(restored) }
        asked.await()
        cell.cancelAndJoin()

        assertTrue(cell.isCancelled)
        assertTrue(repository.ensureFile(restored))
        assertTrue(File(restored.localPath).isFile)
    }

    // --- A pack someone shared ---

    @Test
    fun `a viewed pack lists its valid stickers, and adding it makes an INSTALLED copy that is backed up`() = runTest {
        val first = "a".repeat(64)
        val second = "b".repeat(64)
        coEvery { packSource.fetchPack("their-pack") } returns remotePack(
            id = "their-pack",
            stickers = listOf(remoteSticker(first), remoteSticker("../escape"), remoteSticker(second), remoteSticker("c".repeat(64), "TGS")),
        )

        val preview = repository.viewPack("their-pack").getOrThrow()

        assertEquals("Shared cats", preview.name)
        assertEquals("Ana", preview.publisher)
        assertEquals(listOf(first, second), preview.stickers.map { it.id })
        assertEquals("their-pack", preview.rootPackId)
        assertFalse(preview.isInLibrary)
        assertEquals("viewing writes nothing", emptyList<StickerPack>(), packs())

        repository.installPack(preview).getOrThrow()

        val installed = packs().single()
        assertEquals(StickerPackKind.INSTALLED, installed.kind)
        assertEquals("their-pack", installed.originPackId)
        assertEquals(listOf(first, second), installed.stickers.map { it.id })
        assertFalse("a copy has an id of its own, which only this user writes", installed.id == "their-pack")
        assertEquals(StickerSyncState.PENDING.name, db.stickerDao().getPackRow(installed.id)!!.syncState)
        coVerify(exactly = 1) { scheduler.syncIfPending() }
    }

    @Test
    fun `a pack that is already installed says so, and adding it again changes nothing`() = runTest {
        coEvery { packSource.fetchPack("their-pack") } returns remotePack("their-pack", stickers = listOf(remoteSticker("a".repeat(64))))
        val preview = repository.viewPack("their-pack").getOrThrow()
        repository.installPack(preview).getOrThrow()

        assertTrue(repository.viewPack("their-pack").getOrThrow().isInLibrary)
        repository.installPack(preview).getOrThrow()

        assertEquals(1, packs().size)
    }

    @Test
    fun `a copy of a copy is recognised by the pack it started from`() = runTest {
        val stickers = listOf(remoteSticker("a".repeat(64)))
        coEvery { packSource.fetchPack("root") } returns remotePack("root", stickers = stickers)
        coEvery { packSource.fetchPack("copy-of-root") } returns remotePack("copy-of-root", originPackId = "root", stickers = stickers)
        repository.installPack(repository.viewPack("root").getOrThrow()).getOrThrow()

        val viaCopy = repository.viewPack("copy-of-root").getOrThrow()

        assertEquals("root", viaCopy.rootPackId)
        assertTrue(viaCopy.isInLibrary)
    }

    @Test
    fun `the user's own pack is in the library, whatever the rows say`() = runTest {
        coEvery { packSource.fetchPack("mine") } returns remotePack("mine", ownerId = "uid1", stickers = listOf(remoteSticker("a".repeat(64))))

        assertTrue(repository.viewPack("mine").getOrThrow().isInLibrary)
    }

    @Test
    fun `a pack that is gone, is empty or has an id that is no document id cannot be viewed`() = runTest {
        coEvery { packSource.fetchPack("gone") } returns null
        coEvery { packSource.fetchPack("empty") } returns remotePack("empty", stickers = listOf(remoteSticker("not a hash")))

        assertTrue(repository.viewPack("gone").isFailure)
        assertTrue(repository.viewPack("empty").isFailure)
        assertTrue(repository.viewPack("packs/../users/uid1").isFailure)
        coVerify(exactly = 0) { packSource.fetchPack("packs/../users/uid1") }
    }

    @Test
    fun `a pack is renamed, but not to nothing and not the SAVED pack`() = runTest {
        repository.importFrom(listOf(source("1.webp", sticker(1, cats)), source("2.webp", sticker(2)))).getOrThrow()
        val cats = pack("Cats").id
        val saved = packs().single { it.kind == StickerPackKind.SAVED }.id

        assertTrue(repository.renamePack(cats, "  Kit\u0007tens\n ").isSuccess)
        assertTrue(repository.renamePack(cats, "   ").isFailure)
        assertTrue(repository.renamePack(saved, "Mine").isFailure)
        assertTrue(repository.renamePack("no such pack", "X").isFailure)

        assertEquals(listOf("Kittens", ""), packs().map { it.name })
    }

    @Test
    fun `packs are reordered and deleted`() = runTest {
        repository.importFrom(
            listOf(source("1.webp", sticker(1, cats)), source("2.webp", sticker(2, dogs)), source("3.webp", sticker(3))),
            "WhatsApp",
        ).getOrThrow()
        val ids = packs().associate { it.name to it.id }

        repository.reorderPacks(listOf(ids.getValue("WhatsApp"), ids.getValue("Cats"))).getOrThrow()
        assertEquals(listOf("WhatsApp", "Cats", "Dogs"), packs().map { it.name })

        repository.deletePack(ids.getValue("Cats")).getOrThrow()
        assertEquals(listOf("WhatsApp", "Dogs"), packs().map { it.name })
    }

    @Test
    fun `stickers are moved between packs and removed from one`() = runTest {
        repository.importFrom(
            listOf(source("1.webp", sticker(1, cats)), source("2.webp", sticker(2, cats)), source("3.webp", sticker(3, dogs))),
        ).getOrThrow()
        val cats = pack("Cats")
        val dogs = pack("Dogs")
        val (first, second) = cats.stickers.map { it.id }

        repository.moveStickers(listOf(first), cats.id, dogs.id).getOrThrow()
        assertEquals(listOf(second), pack("Cats").stickers.map { it.id })
        assertEquals(first, pack("Dogs").stickers.last().id)

        assertTrue(repository.moveStickers(listOf(second), cats.id, "no such pack").isFailure)
        assertEquals("a failed move takes nothing out", listOf(second), pack("Cats").stickers.map { it.id })

        repository.removeStickers(dogs.id, listOf(first)).getOrThrow()
        assertEquals(1, pack("Dogs").stickers.size)
    }

    // --- A made sticker ---

    @Test
    fun `a made sticker is stored under its hash and joins a new pack of the given name`() = runTest {
        val bytes = sticker(7)
        coEvery { maker.render("/draft/cutout.png", StickerCrop(scale = 2f)) } returns bytes

        val id = repository.createSticker("/draft/cutout.png", StickerCrop(scale = 2f), listOf("😺"), null, "My stickers").getOrThrow()

        assertEquals(StickerFiles.sha256Hex(bytes), id)
        val pack = pack("My stickers")
        assertEquals(StickerPackKind.USER, pack.kind)
        assertEquals(listOf(id), pack.stickers.map { it.id })
        assertEquals(listOf("😺"), pack.stickers.single().emojis)
        assertTrue(File(pack.stickers.single().localPath).isFile)
        assertEquals(StickerSyncState.PENDING.name, db.stickerDao().getPack(pack.id)!!.syncState)
        coVerify { scheduler.syncIfPending() }
    }

    @Test
    fun `a second made sticker joins the same named pack, after the first`() = runTest {
        coEvery { maker.render(any(), any()) } returnsMany listOf(sticker(1), sticker(2))

        val first = repository.createSticker("/draft/a.png", StickerCrop(), emptyList(), null, "My stickers").getOrThrow()
        val second = repository.createSticker("/draft/a.png", StickerCrop(), emptyList(), null, " My stickers ").getOrThrow()

        assertEquals(listOf(first, second), pack("My stickers").stickers.map { it.id })
        assertEquals(1, packs().size)
    }

    @Test
    fun `a made sticker joins the chosen pack of the user's own`() = runTest {
        repository.importFrom(listOf(source("1.webp", sticker(1, cats)))).getOrThrow()
        val catPack = pack("Cats")
        coEvery { maker.render(any(), any()) } returns sticker(2)

        val id = repository.createSticker("/draft/a.png", StickerCrop(), listOf("🐱"), catPack.id, "ignored").getOrThrow()

        assertEquals(id, pack("Cats").stickers.last().id)
        assertEquals(2, pack("Cats").stickers.size)
        assertEquals("no pack is made beside it", 1, packs().size)
    }

    @Test
    fun `a made sticker keeps three emojis, each once`() = runTest {
        coEvery { maker.render(any(), any()) } returns sticker(3)

        repository.createSticker("/draft/a.png", StickerCrop(), listOf("😺", " ", "😺", "🐶", "🦊", "🐸"), null, "Mine").getOrThrow()

        assertEquals(listOf("😺", "🐶", "🦊"), pack("Mine").stickers.single().emojis)
    }

    @Test
    fun `a made sticker is refused for a pack that is gone, is not the user's own, or has no name`() = runTest {
        coEvery { maker.render(any(), any()) } returns sticker(4)
        repository.importFrom(listOf(source("1.webp", sticker(1)))).getOrThrow()
        val saved = packs().single { it.kind == StickerPackKind.SAVED }

        assertTrue(repository.createSticker("/draft/a.png", StickerCrop(), emptyList(), "no such pack", "x").isFailure)
        assertTrue(repository.createSticker("/draft/a.png", StickerCrop(), emptyList(), saved.id, "x").isFailure)
        assertTrue(repository.createSticker("/draft/a.png", StickerCrop(), emptyList(), null, "  ").isFailure)

        assertEquals(1, packs().size)
        assertEquals(1, packs().single().stickers.size)
        assertEquals("nothing was rendered or stored", 1, storedFiles().size)
    }

    @Test
    fun `bytes that are no sticker, and a draft that cannot be rendered, make nothing`() = runTest {
        coEvery { maker.render("/draft/bad.png", any()) } returns byteArrayOf(1, 2, 3)
        coEvery { maker.render("/draft/gone.png", any()) } throws IllegalStateException("That picture is no longer available")

        val notSticker = repository.createSticker("/draft/bad.png", StickerCrop(), emptyList(), null, "Mine")
        val gone = repository.createSticker("/draft/gone.png", StickerCrop(), emptyList(), null, "Mine")

        assertEquals("That picture could not be made into a sticker", notSticker.exceptionOrNull()?.message)
        assertEquals("That picture is no longer available", gone.exceptionOrNull()?.message)
        assertEquals(emptyList<StickerPack>(), packs())
        assertEquals(emptyList<String>(), storedFiles())
    }

    @Test
    fun `a draft is prepared by the maker, and its failure is a failed result`() = runTest {
        val draft = StickerDraft(StickerDraftImage("/draft/original.png", 10, 10), null, null)
        coEvery { maker.prepare("content://photo") } returns draft
        coEvery { maker.prepare("content://gone") } throws IllegalStateException("That photo could not be read")

        assertEquals(draft, repository.prepareStickerDraft("content://photo").getOrThrow())
        assertTrue(repository.prepareStickerDraft("content://gone").isFailure)
    }

    // --- Recents and the folder ---

    @Test
    fun `recents follow the order of use and skip stickers the library does not know`() = runTest {
        repository.importFrom(listOf(source("1.webp", sticker(1, cats)), source("2.webp", sticker(2, cats)))).getOrThrow()
        val (first, second) = pack("Cats").stickers.map { it.id }
        assertEquals(emptyList<String>(), repository.observeRecents().first().map { it.id })

        repository.markUsed(first)
        repository.markUsed(second)
        repository.markUsed("e".repeat(64))
        repository.markUsed("../not-an-id")
        repository.markUsed(first)

        assertEquals(listOf(first, "e".repeat(64), second), recentIds.value)
        assertEquals(listOf(first, second), repository.observeRecents().first().map { it.id })
    }

    @Test
    fun `the folder listing is passed through, and a lost grant is a failure`() = runTest {
        val listed = listOf(WhatsAppStickerFile("content://tree/a.webp", "a.webp", 10, 20))
        coEvery { folder.list("content://tree") } returns listed
        coEvery { folder.list("content://revoked") } throws SecurityException("no grant")

        assertEquals(listed, repository.listWhatsAppFolder("content://tree").getOrThrow())
        assertTrue(repository.listWhatsAppFolder("content://revoked").isFailure)
    }

    private companion object {
        const val OBJECT_URL = "https://storage.example/stickers/object.webp"
    }
}
