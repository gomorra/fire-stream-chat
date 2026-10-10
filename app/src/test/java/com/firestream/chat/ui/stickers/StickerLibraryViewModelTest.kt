package com.firestream.chat.ui.stickers

import com.firestream.chat.domain.model.AppError
import com.firestream.chat.domain.model.Sticker
import com.firestream.chat.domain.model.StickerFormat
import com.firestream.chat.domain.model.StickerImportResult
import com.firestream.chat.domain.model.StickerPack
import com.firestream.chat.domain.model.StickerPackKind
import com.firestream.chat.domain.model.WhatsAppStickerFile
import com.firestream.chat.domain.repository.StickerRepository
import com.firestream.chat.test.MainDispatcherRule
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test

class StickerLibraryViewModelTest {

    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    private val repository = mockk<StickerRepository>()
    private val packs = MutableStateFlow<List<StickerPack>>(emptyList())

    @Before
    fun setUp() {
        every { repository.observePacks() } returns packs
        coEvery { repository.removeStickers(any(), any()) } returns Result.success(Unit)
        coEvery { repository.moveStickers(any(), any(), any()) } returns Result.success(Unit)
        coEvery { repository.reorderPacks(any()) } returns Result.success(Unit)
        coEvery { repository.renamePack(any(), any()) } returns Result.success(Unit)
        coEvery { repository.deletePack(any()) } returns Result.success(Unit)
        coEvery { repository.mergePacks(any(), any()) } returns Result.success(Unit)
        coEvery { repository.setPackShownInRow(any(), any()) } returns Result.success(Unit)
    }

    private fun sticker(id: String) = Sticker(id, StickerFormat.WEBP, 512, 512, false, emptyList(), "/stickers/$id.webp")

    private fun pack(
        id: String,
        vararg stickerIds: String,
        kind: StickerPackKind = StickerPackKind.USER,
        shownInRow: Boolean = true,
    ) = StickerPack(
        shownInRow = shownInRow,
        id = id,
        name = if (kind == StickerPackKind.USER) "Pack $id" else "",
        publisher = null,
        kind = kind,
        originPackId = null,
        stickers = stickerIds.map(::sticker),
        createdAt = 0,
        updatedAt = 0,
    )

    private fun file(name: String) = WhatsAppStickerFile("content://wa/$name", "$name.webp", 10, 0)

    @Test
    fun `the library is loading until the first emission and then shows the packs`() {
        packs.value = listOf(pack("a", "s1"))
        val viewModel = StickerLibraryViewModel(repository)

        assertFalse(viewModel.uiState.value.isLoading)
        assertEquals(listOf("a"), viewModel.uiState.value.packs.map { it.id })
    }

    @Test
    fun `a pack that is deleted while open closes its grid`() {
        packs.value = listOf(pack("a", "s1"), pack("b"))
        val viewModel = StickerLibraryViewModel(repository)
        viewModel.openPack("a")
        viewModel.toggleSticker("s1")

        packs.value = listOf(pack("b"))

        assertNull(viewModel.uiState.value.openPackId)
        assertTrue(viewModel.uiState.value.selectedStickerIds.isEmpty())
    }

    @Test
    fun `a selected sticker that leaves the open pack leaves the selection`() {
        packs.value = listOf(pack("a", "s1", "s2"))
        val viewModel = StickerLibraryViewModel(repository)
        viewModel.openPack("a")
        viewModel.toggleSticker("s1")
        viewModel.toggleSticker("s2")

        packs.value = listOf(pack("a", "s2"))

        assertEquals(setOf("s2"), viewModel.uiState.value.selectedStickerIds)
    }

    @Test
    fun `toggling a sticker twice deselects it`() {
        packs.value = listOf(pack("a", "s1"))
        val viewModel = StickerLibraryViewModel(repository)
        viewModel.openPack("a")

        viewModel.toggleSticker("s1")
        assertEquals(setOf("s1"), viewModel.uiState.value.selectedStickerIds)
        viewModel.toggleSticker("s1")
        assertTrue(viewModel.uiState.value.selectedStickerIds.isEmpty())
    }

    @Test
    fun `removing the selection removes it from the open pack and clears it`() {
        packs.value = listOf(pack("a", "s1", "s2"))
        val viewModel = StickerLibraryViewModel(repository)
        viewModel.openPack("a")
        viewModel.toggleSticker("s2")

        viewModel.removeSelected()

        coVerify(exactly = 1) { repository.removeStickers("a", listOf("s2")) }
        assertTrue(viewModel.uiState.value.selectedStickerIds.isEmpty())
    }

    @Test
    fun `moving the selection names the open pack as its source`() {
        packs.value = listOf(pack("a", "s1"), pack("b"))
        val viewModel = StickerLibraryViewModel(repository)
        viewModel.openPack("a")
        viewModel.toggleSticker("s1")

        viewModel.moveSelectedTo("b")

        coVerify(exactly = 1) { repository.moveStickers(listOf("s1"), "a", "b") }
        assertTrue(viewModel.uiState.value.selectedStickerIds.isEmpty())
    }

    @Test
    fun `a dropped order is shown at once and saved`() = runTest {
        val write = CompletableDeferred<Result<Unit>>()
        coEvery { repository.reorderPacks(any()) } coAnswers { write.await() }
        packs.value = listOf(pack("a"), pack("b"), pack("c"))
        val viewModel = StickerLibraryViewModel(repository)

        viewModel.reorderPacks(listOf("c", "a", "b"))

        assertEquals(listOf("c", "a", "b"), viewModel.uiState.value.packs.map { it.id })
        coVerify(exactly = 1) { repository.reorderPacks(listOf("c", "a", "b")) }
        write.complete(Result.success(Unit))
    }

    @Test
    fun `a dropped order that cannot be saved goes back to the library's order`() {
        coEvery { repository.reorderPacks(any()) } returns Result.failure(IllegalStateException("boom"))
        packs.value = listOf(pack("a"), pack("b"))
        val viewModel = StickerLibraryViewModel(repository)

        viewModel.reorderPacks(listOf("b", "a"))

        assertEquals(listOf("a", "b"), viewModel.uiState.value.packs.map { it.id })
        assertEquals("boom", viewModel.uiState.value.error?.message)
    }

    @Test
    fun `a drop that changes nothing writes nothing`() {
        packs.value = listOf(pack("a"), pack("b"))
        val viewModel = StickerLibraryViewModel(repository)

        viewModel.reorderPacks(listOf("a", "b"))

        coVerify(exactly = 0) { repository.reorderPacks(any()) }
    }

    @Test
    fun `an unnamed pack has its own thumbnail whatever its stored flag says`() {
        assertTrue(pack("own").hasOwnThumbnail)
        assertFalse(pack("wa", shownInRow = false).hasOwnThumbnail)
        assertTrue(pack("f", kind = StickerPackKind.FAVOURITES, shownInRow = false).hasOwnThumbnail)
        assertTrue(pack("s", kind = StickerPackKind.SAVED, shownInRow = false).hasOwnThumbnail)
    }

    @Test
    fun `the switch writes the pack's row flag`() {
        packs.value = listOf(pack("a", shownInRow = false))
        val viewModel = StickerLibraryViewModel(repository)

        viewModel.setPackShownInRow("a", true)

        coVerify(exactly = 1) { repository.setPackShownInRow("a", true) }
    }

    @Test
    fun `a merge names the selected packs in the order the list shows them and clears the selection`() {
        packs.value = listOf(pack("wa", shownInRow = false), pack("own"), pack("other"))
        val viewModel = StickerLibraryViewModel(repository)
        // Picked last to first. The list shows the packs with a thumbnail before the others.
        viewModel.togglePack("wa")
        viewModel.togglePack("other")
        viewModel.togglePack("own")

        viewModel.mergeSelected("All of them")

        coVerify(exactly = 1) { repository.mergePacks(listOf("own", "other", "wa"), "All of them") }
        assertTrue(viewModel.uiState.value.selectedPackIds.isEmpty())
    }

    @Test
    fun `a merge of one pack does not start`() {
        packs.value = listOf(pack("a"), pack("b"))
        val viewModel = StickerLibraryViewModel(repository)
        viewModel.togglePack("a")

        viewModel.mergeSelected("One")

        coVerify(exactly = 0) { repository.mergePacks(any(), any()) }
        assertEquals(setOf("a"), viewModel.uiState.value.selectedPackIds)
    }

    @Test
    fun `the unnamed packs cannot be selected`() {
        packs.value = listOf(pack("f", kind = StickerPackKind.FAVOURITES), pack("s", kind = StickerPackKind.SAVED), pack("a"))
        val viewModel = StickerLibraryViewModel(repository)

        viewModel.togglePack("f")
        viewModel.togglePack("s")
        viewModel.togglePack("gone")
        viewModel.togglePack("a")

        assertEquals(setOf("a"), viewModel.uiState.value.selectedPackIds)
    }

    @Test
    fun `a selected pack that leaves the library leaves the selection`() {
        packs.value = listOf(pack("a"), pack("b"), pack("c"))
        val viewModel = StickerLibraryViewModel(repository)
        viewModel.togglePack("a")
        viewModel.togglePack("b")

        packs.value = listOf(pack("b"), pack("c"))

        assertEquals(setOf("b"), viewModel.uiState.value.selectedPackIds)
        // One pack is left, so a merge has nothing to merge.
        viewModel.mergeSelected("Two")
        coVerify(exactly = 0) { repository.mergePacks(any(), any()) }
    }

    @Test
    fun `deleting the selection deletes each pack and stops at the first failure`() {
        coEvery { repository.deletePack("b") } returns Result.failure(IllegalStateException("boom"))
        packs.value = listOf(pack("a"), pack("b"), pack("c"))
        val viewModel = StickerLibraryViewModel(repository)
        viewModel.togglePack("a")
        viewModel.togglePack("b")
        viewModel.togglePack("c")

        viewModel.deleteSelected()

        coVerify(exactly = 1) { repository.deletePack("a") }
        coVerify(exactly = 1) { repository.deletePack("b") }
        coVerify(exactly = 0) { repository.deletePack("c") }
        assertEquals("boom", viewModel.uiState.value.error?.message)
        assertTrue(viewModel.uiState.value.selectedPackIds.isEmpty())
    }

    @Test
    fun `changing the tab or opening a pack ends a pack selection`() {
        packs.value = listOf(pack("a"), pack("b"))
        val viewModel = StickerLibraryViewModel(repository)

        viewModel.togglePack("a")
        viewModel.selectTab(StickerManagerTab.ALL_STICKERS)
        assertEquals(StickerManagerTab.ALL_STICKERS, viewModel.uiState.value.tab)
        assertTrue(viewModel.uiState.value.selectedPackIds.isEmpty())

        viewModel.togglePack("a")
        viewModel.openPack("b")
        assertTrue(viewModel.uiState.value.selectedPackIds.isEmpty())
    }

    @Test
    fun `a failed edit shows the repository's message`() {
        coEvery { repository.renamePack("a", " ") } returns Result.failure(IllegalArgumentException("A pack needs a name"))
        packs.value = listOf(pack("a"))
        val viewModel = StickerLibraryViewModel(repository)

        viewModel.renamePack("a", " ")

        val error = viewModel.uiState.value.error
        assertTrue(error is AppError.Unknown)
        assertEquals("A pack needs a name", error?.message)
        viewModel.clearError()
        assertNull(viewModel.uiState.value.error)
    }

    @Test
    fun `importing files passes no pack name and reports the counts`() {
        coEvery { repository.importFrom(listOf("content://a", "content://b"), null, skipKnown = false) } returns
            Result.success(StickerImportResult(imported = 1, duplicates = 1, rejected = 0, packIds = listOf("p")))
        val viewModel = StickerLibraryViewModel(repository)

        viewModel.importFiles(listOf("content://a", "content://b"))

        assertFalse(viewModel.uiState.value.isImporting)
        assertEquals("Imported 1 sticker · 1 already in the library", viewModel.uiState.value.notice)
    }

    @Test
    fun `a pick of no files imports nothing`() {
        val viewModel = StickerLibraryViewModel(repository)

        viewModel.importFiles(emptyList())

        coVerify(exactly = 0) { repository.importFrom(any(), any(), any()) }
        assertFalse(viewModel.uiState.value.isImporting)
    }

    @Test
    fun `a second import is ignored while one is running`() = runTest {
        val running = CompletableDeferred<Result<StickerImportResult>>()
        coEvery { repository.importFrom(any(), any(), any()) } coAnswers { running.await() }
        val viewModel = StickerLibraryViewModel(repository)

        viewModel.importFiles(listOf("content://a"))
        assertTrue(viewModel.uiState.value.isImporting)
        viewModel.importFiles(listOf("content://b"))
        running.complete(Result.success(StickerImportResult(1, 0, 0, listOf("p"))))

        coVerify(exactly = 1) { repository.importFrom(any(), any(), any()) }
        assertFalse(viewModel.uiState.value.isImporting)
    }

    @Test
    fun `a failed import shows an error and ends the progress`() {
        coEvery { repository.importFrom(any(), any(), any()) } returns Result.failure(IllegalStateException("boom"))
        val viewModel = StickerLibraryViewModel(repository)

        viewModel.importFiles(listOf("content://a"))

        assertFalse(viewModel.uiState.value.isImporting)
        assertEquals("boom", viewModel.uiState.value.error?.message)
        assertNull(viewModel.uiState.value.notice)
    }

    @Test
    fun `opening the WhatsApp folder lists its files with nothing selected`() {
        coEvery { repository.listWhatsAppFolder("content://tree") } returns Result.success(listOf(file("x"), file("y")))
        val viewModel = StickerLibraryViewModel(repository)

        viewModel.openWhatsAppFolder("content://tree")

        val whatsApp = viewModel.uiState.value.whatsApp!!
        assertFalse(whatsApp.isLoading)
        assertEquals(2, whatsApp.files.size)
        assertTrue(whatsApp.selected.isEmpty())
    }

    @Test
    fun `a folder that cannot be listed closes the view with an error`() {
        coEvery { repository.listWhatsAppFolder(any()) } returns Result.failure(SecurityException("No access to that folder"))
        val viewModel = StickerLibraryViewModel(repository)

        viewModel.openWhatsAppFolder("content://tree")

        assertNull(viewModel.uiState.value.whatsApp)
        assertEquals("No access to that folder", viewModel.uiState.value.error?.message)
    }

    @Test
    fun `a listing that returns after the view was closed does not reopen it`() = runTest {
        val listing = CompletableDeferred<Result<List<WhatsAppStickerFile>>>()
        coEvery { repository.listWhatsAppFolder(any()) } coAnswers { listing.await() }
        val viewModel = StickerLibraryViewModel(repository)

        viewModel.openWhatsAppFolder("content://tree")
        assertTrue(viewModel.uiState.value.whatsApp!!.isLoading)
        viewModel.closeWhatsApp()
        listing.complete(Result.success(listOf(file("x"))))

        assertNull(viewModel.uiState.value.whatsApp)
    }

    @Test
    fun `select all selects every file and a second tap selects none`() {
        coEvery { repository.listWhatsAppFolder(any()) } returns Result.success(listOf(file("x"), file("y")))
        val viewModel = StickerLibraryViewModel(repository)
        viewModel.openWhatsAppFolder("content://tree")
        viewModel.toggleWhatsAppFile("content://wa/x")

        viewModel.toggleSelectAllWhatsApp()
        assertEquals(setOf("content://wa/x", "content://wa/y"), viewModel.uiState.value.whatsApp!!.selected)

        viewModel.toggleSelectAllWhatsApp()
        assertTrue(viewModel.uiState.value.whatsApp!!.selected.isEmpty())
    }

    @Test
    fun `the WhatsApp import sends the selection in folder order under the WhatsApp pack name and closes the view`() {
        coEvery { repository.listWhatsAppFolder(any()) } returns Result.success(listOf(file("x"), file("y"), file("z")))
        coEvery { repository.importFrom(any(), any(), any()) } returns Result.success(StickerImportResult(2, 0, 0, listOf("p")))
        val viewModel = StickerLibraryViewModel(repository)
        viewModel.openWhatsAppFolder("content://tree")
        viewModel.toggleWhatsAppFile("content://wa/z")
        viewModel.toggleWhatsAppFile("content://wa/x")

        viewModel.importSelectedWhatsApp()

        coVerify(exactly = 1) { repository.importFrom(listOf("content://wa/x", "content://wa/z"), "WhatsApp", skipKnown = true) }
        assertNull(viewModel.uiState.value.whatsApp)
        assertEquals("Imported 2 stickers", viewModel.uiState.value.notice)
    }

    @Test
    fun `a failed WhatsApp import keeps the view and its selection`() {
        coEvery { repository.listWhatsAppFolder(any()) } returns Result.success(listOf(file("x")))
        coEvery { repository.importFrom(any(), any(), any()) } returns Result.failure(IllegalStateException("boom"))
        val viewModel = StickerLibraryViewModel(repository)
        viewModel.openWhatsAppFolder("content://tree")
        viewModel.toggleWhatsAppFile("content://wa/x")

        viewModel.importSelectedWhatsApp()

        assertEquals(setOf("content://wa/x"), viewModel.uiState.value.whatsApp!!.selected)
        assertEquals("boom", viewModel.uiState.value.error?.message)
    }

    @Test
    fun `an import with nothing selected does not start`() {
        coEvery { repository.listWhatsAppFolder(any()) } returns Result.success(listOf(file("x")))
        val viewModel = StickerLibraryViewModel(repository)
        viewModel.openWhatsAppFolder("content://tree")

        viewModel.importSelectedWhatsApp()

        coVerify(exactly = 0) { repository.importFrom(any(), any(), any()) }
    }

    @Test
    fun `the import summary leaves out the counts that are zero`() {
        assertEquals("Nothing to import", importSummary(StickerImportResult(0, 0, 0, emptyList())))
        assertEquals("3 already in the library", importSummary(StickerImportResult(0, 3, 0, emptyList())))
        assertEquals(
            "Imported 5 stickers · 2 already in the library · 1 could not be imported",
            importSummary(StickerImportResult(5, 2, 1, listOf("p"))),
        )
        assertEquals(
            "Imported 1 sticker · 7 already in the library · 2 deleted earlier",
            importSummary(StickerImportResult(1, 3, 0, listOf("p"), alreadyInLibrary = 4, deletedEarlier = 2)),
        )
    }

    @Test
    fun `the unnamed packs are labelled by kind and cannot be renamed`() {
        val favourites = pack("f", kind = StickerPackKind.FAVOURITES)
        val saved = pack("s", kind = StickerPackKind.SAVED)
        val user = pack("u")

        assertEquals("Favourites", favourites.label())
        assertEquals("Saved stickers", saved.label())
        assertEquals("Pack u", user.label())
        assertFalse(favourites.canRename)
        assertFalse(saved.canRename)
        assertTrue(user.canRename)
    }
}
