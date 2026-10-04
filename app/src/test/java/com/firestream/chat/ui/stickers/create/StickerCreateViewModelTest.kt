package com.firestream.chat.ui.stickers.create

import com.firestream.chat.domain.model.StickerCrop
import com.firestream.chat.domain.model.StickerDraft
import com.firestream.chat.domain.model.StickerDraftImage
import com.firestream.chat.domain.model.StickerPack
import com.firestream.chat.domain.model.StickerPackKind
import com.firestream.chat.domain.repository.StickerRepository
import com.firestream.chat.test.MainDispatcherRule
import com.firestream.chat.test.fakes.testStickerPack
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test

class StickerCreateViewModelTest {

    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    private val repository = mockk<StickerRepository>()
    private val packs = MutableStateFlow<List<StickerPack>>(emptyList())

    private val original = StickerDraftImage("/draft/original.png", 800, 400)
    private val cutout = StickerDraftImage("/draft/cutout.png", 300, 300)
    private val outlined = StickerDraftImage("/draft/outlined.png", 316, 316)
    private val withSubject = StickerDraft(original, cutout, outlined)
    private val photoOnly = StickerDraft(original, null, null)

    @Before
    fun setUp() {
        every { repository.observePacks() } returns packs
        coEvery { repository.prepareStickerDraft(any()) } returns Result.success(withSubject)
        coEvery { repository.createSticker(any(), any(), any(), any(), any()) } returns Result.success("id")
    }

    private fun viewModelWith(draft: StickerDraft = withSubject): StickerCreateViewModel {
        coEvery { repository.prepareStickerDraft(any()) } returns Result.success(draft)
        return StickerCreateViewModel(repository).also { it.onPhotoPicked("content://photo") }
    }

    @Test
    fun `a prepared photo opens on its outlined cutout`() {
        val viewModel = viewModelWith()

        val state = viewModel.uiState.value
        assertFalse(state.isPreparing)
        assertEquals(outlined, state.image)
        assertTrue(state.canSave)
    }

    @Test
    fun `the photo is being prepared until the repository answers`() {
        val pending = CompletableDeferred<Result<StickerDraft>>()
        coEvery { repository.prepareStickerDraft(any()) } coAnswers { pending.await() }
        val viewModel = StickerCreateViewModel(repository)

        viewModel.onPhotoPicked("content://photo")
        assertTrue(viewModel.uiState.value.isPreparing)
        assertFalse(viewModel.uiState.value.canSave)

        pending.complete(Result.success(withSubject))
        assertFalse(viewModel.uiState.value.isPreparing)
    }

    @Test
    fun `a photo that cannot be read is an error and no draft`() {
        coEvery { repository.prepareStickerDraft(any()) } returns
            Result.failure(IllegalStateException("That photo could not be read"))
        val viewModel = StickerCreateViewModel(repository)

        viewModel.onPhotoPicked("content://photo")

        val state = viewModel.uiState.value
        assertFalse(state.isPreparing)
        assertNull(state.image)
        assertEquals("That photo could not be read", state.error?.message)
    }

    @Test
    fun `the outline and the cutout choose which picture is saved`() {
        val viewModel = viewModelWith()

        viewModel.setOutline(false)
        assertEquals(cutout, viewModel.uiState.value.image)

        viewModel.setUseCutout(false)
        assertEquals(original, viewModel.uiState.value.image)
    }

    @Test
    fun `a draft without a cutout uses the photo whatever the choices say`() {
        val viewModel = viewModelWith(photoOnly)

        assertFalse(viewModel.uiState.value.hasCutout)
        assertEquals(original, viewModel.uiState.value.image)
    }

    @Test
    fun `switching between the cutout and the photo starts the crop again`() {
        val viewModel = viewModelWith()
        viewModel.transform(zoom = 2f, panX = 0.2f, panY = 0f)
        assertEquals(2f, viewModel.uiState.value.crop.scale)

        viewModel.setUseCutout(false)

        assertEquals(StickerCrop(), viewModel.uiState.value.crop)
    }

    @Test
    fun `a pinch and a drag are kept inside what the picture allows`() {
        val viewModel = viewModelWith()

        viewModel.transform(zoom = 2f, panX = 5f, panY = 0f)

        // The square cutout at scale 2 can move by half the square.
        assertEquals(StickerCrop(scale = 2f, offsetX = 0.5f, offsetY = 0f), viewModel.uiState.value.crop)
    }

    @Test
    fun `a new photo starts over`() {
        val viewModel = viewModelWith()
        viewModel.transform(zoom = 3f, panX = 0f, panY = 0f)
        viewModel.setOutline(false)

        viewModel.onPhotoPicked("content://other")

        val state = viewModel.uiState.value
        assertEquals(StickerCrop(), state.crop)
        assertEquals(outlined, state.image)
    }

    @Test
    fun `at most three emojis, each once, and a tap takes one out`() {
        val viewModel = viewModelWith()

        listOf("😺", "😺", "🐶", "🦊", "🐸").forEach(viewModel::addEmoji)
        assertEquals(listOf("😺", "🐶", "🦊"), viewModel.uiState.value.emojis)
        assertFalse(viewModel.uiState.value.canAddEmoji)

        viewModel.removeEmoji("🐶")
        assertEquals(listOf("😺", "🦊"), viewModel.uiState.value.emojis)
    }

    @Test
    fun `only the user's own packs are offered`() {
        packs.value = listOf(
            testStickerPack("mine"),
            testStickerPack("theirs", StickerPackKind.INSTALLED),
            testStickerPack("favs", StickerPackKind.FAVOURITES),
            testStickerPack("saved", StickerPackKind.SAVED),
        )

        val viewModel = viewModelWith()

        assertEquals(listOf("mine"), viewModel.uiState.value.packs.map { it.id })
        assertNull("a new pack until one is chosen", viewModel.uiState.value.packId)
    }

    @Test
    fun `the pack earlier stickers were made into is chosen already`() {
        packs.value = listOf(testStickerPack("mine"), testStickerPack(StickerDraft.DEFAULT_PACK_NAME))

        val viewModel = viewModelWith()

        assertEquals(StickerDraft.DEFAULT_PACK_NAME, viewModel.uiState.value.packId)
    }

    @Test
    fun `a chosen pack that is deleted falls back to a new pack`() {
        packs.value = listOf(testStickerPack("mine"))
        val viewModel = viewModelWith()
        viewModel.choosePack("mine")

        packs.value = emptyList()

        assertNull(viewModel.uiState.value.packId)
    }

    @Test
    fun `a choice of a new pack is not overruled by the library`() {
        val viewModel = viewModelWith()
        viewModel.choosePack(null)

        packs.value = listOf(testStickerPack(StickerDraft.DEFAULT_PACK_NAME))

        assertNull(viewModel.uiState.value.packId)
    }

    @Test
    fun `saving hands over the shown picture, the crop, the emojis and the pack, then is done`() {
        packs.value = listOf(testStickerPack("mine"))
        val viewModel = viewModelWith()
        viewModel.setOutline(false)
        viewModel.transform(zoom = 2f, panX = 0f, panY = 0f)
        viewModel.addEmoji("😺")
        viewModel.choosePack("mine")

        viewModel.save()

        coVerify(exactly = 1) {
            repository.createSticker(cutout.path, StickerCrop(scale = 2f), listOf("😺"), "mine", any())
        }
        assertTrue(viewModel.uiState.value.isDone)
    }

    @Test
    fun `a new pack is saved under the typed name, and not without one`() {
        val viewModel = viewModelWith()
        viewModel.setNewPackName("  ")
        assertFalse(viewModel.uiState.value.canSave)
        viewModel.save()
        coVerify(exactly = 0) { repository.createSticker(any(), any(), any(), any(), any()) }

        viewModel.setNewPackName("Holiday")
        viewModel.save()

        coVerify(exactly = 1) { repository.createSticker(outlined.path, StickerCrop(), emptyList(), null, "Holiday") }
    }

    @Test
    fun `a second tap while saving makes one sticker`() {
        val pending = CompletableDeferred<Result<String>>()
        coEvery { repository.createSticker(any(), any(), any(), any(), any()) } coAnswers { pending.await() }
        val viewModel = viewModelWith()

        viewModel.save()
        viewModel.save()

        assertTrue(viewModel.uiState.value.isSaving)
        coVerify(exactly = 1) { repository.createSticker(any(), any(), any(), any(), any()) }
    }

    @Test
    fun `a failed save shows its reason and keeps the draft`() {
        coEvery { repository.createSticker(any(), any(), any(), any(), any()) } returns
            Result.failure(IllegalStateException("That picture has too much detail for a sticker"))
        val viewModel = viewModelWith()

        viewModel.save()

        val state = viewModel.uiState.value
        assertFalse(state.isDone)
        assertFalse(state.isSaving)
        assertEquals("That picture has too much detail for a sticker", state.error?.message)
        assertEquals(outlined, state.image)
    }
}
