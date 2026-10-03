package com.firestream.chat.data.repository

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.firestream.chat.data.local.AppDatabase
import com.firestream.chat.data.local.PreferencesDataStore
import com.firestream.chat.data.sticker.StickerFiles
import com.firestream.chat.data.sticker.WhatsAppStickerFolder
import com.firestream.chat.domain.model.StickerImportResult
import com.firestream.chat.domain.model.StickerPack
import com.firestream.chat.domain.model.StickerPackKind
import com.firestream.chat.domain.model.WhatsAppStickerFile
import com.firestream.chat.test.WebpFixtures.sticker
import com.firestream.chat.test.WebpFixtures.waJson
import com.firestream.chat.test.WebpFixtures.zip
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

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
        repository = StickerRepositoryImpl(db.stickerDao(), files, folder, preferences)
    }

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

        repository.setFavourite(first, true).getOrThrow()
        repository.setFavourite(second, true).getOrThrow()
        repository.setFavourite(second, true).getOrThrow()

        val favourites = packs().single { it.kind == StickerPackKind.FAVOURITES }
        assertEquals(listOf(second, first), favourites.stickers.map { it.id })
        assertEquals("the sticker stays in its own pack", 2, pack("Cats").stickers.size)

        repository.setFavourite(second, false).getOrThrow()

        assertEquals(listOf(first), packs().single { it.kind == StickerPackKind.FAVOURITES }.stickers.map { it.id })
    }

    @Test
    fun `a sticker the library does not have cannot be a favourite`() = runTest {
        assertTrue(repository.setFavourite("f".repeat(64), true).isFailure)
        assertTrue(repository.setFavourite("f".repeat(64), false).isSuccess)
        assertEquals(emptyList<StickerPack>(), packs())
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
}
