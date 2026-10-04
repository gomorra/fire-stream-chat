package com.firestream.chat.ui.stickers

import com.firestream.chat.domain.model.StickerPackPreview
import com.firestream.chat.domain.repository.StickerRepository
import com.firestream.chat.test.MainDispatcherRule
import com.firestream.chat.test.fakes.testSticker
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.CompletableDeferred
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

class StickerPackPreviewViewModelTest {

    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    private val repository = mockk<StickerRepository>()

    private fun preview(packId: String, isInLibrary: Boolean = false) = StickerPackPreview(
        packId = packId,
        rootPackId = packId,
        name = "Cats",
        publisher = "Ana",
        stickers = listOf(testSticker("cat", "😺")),
        isInLibrary = isInLibrary,
    )

    @Test
    fun `a pack is loading until it is found, and then shown`() {
        val found = CompletableDeferred<Result<StickerPackPreview>>()
        coEvery { repository.viewPack("p1") } coAnswers { found.await() }
        val viewModel = StickerPackPreviewViewModel(repository)

        viewModel.load("p1")
        assertTrue(viewModel.uiState.value.isLoading)

        found.complete(Result.success(preview("p1")))

        assertFalse(viewModel.uiState.value.isLoading)
        assertEquals("p1", viewModel.uiState.value.preview?.packId)
        assertNull(viewModel.uiState.value.error)
    }

    @Test
    fun `a pack that is gone shows why, and nothing to add`() {
        coEvery { repository.viewPack("gone") } returns Result.failure(NoSuchElementException("This pack is no longer available"))
        val viewModel = StickerPackPreviewViewModel(repository)

        viewModel.load("gone")
        viewModel.addPack()

        assertFalse(viewModel.uiState.value.isLoading)
        assertNull(viewModel.uiState.value.preview)
        assertEquals("This pack is no longer available", viewModel.uiState.value.error?.message)
        coVerify(exactly = 0) { repository.installPack(any()) }
    }

    @Test
    fun `adding a pack installs it and then says it is in the library`() {
        coEvery { repository.viewPack("p1") } returns Result.success(preview("p1"))
        coEvery { repository.installPack(any()) } returns Result.success(Unit)
        val viewModel = StickerPackPreviewViewModel(repository)
        viewModel.load("p1")

        viewModel.addPack()
        viewModel.addPack()

        assertTrue(viewModel.uiState.value.preview!!.isInLibrary)
        assertFalse(viewModel.uiState.value.isAdding)
        coVerify(exactly = 1) { repository.installPack(preview("p1")) }
    }

    @Test
    fun `a pack the library holds is not added again`() {
        coEvery { repository.viewPack("p1") } returns Result.success(preview("p1", isInLibrary = true))
        val viewModel = StickerPackPreviewViewModel(repository)
        viewModel.load("p1")

        viewModel.addPack()

        coVerify(exactly = 0) { repository.installPack(any()) }
    }

    @Test
    fun `an add that fails can be tried again`() {
        coEvery { repository.viewPack("p1") } returns Result.success(preview("p1"))
        coEvery { repository.installPack(any()) } returns Result.failure(IllegalStateException("disk full"))
        val viewModel = StickerPackPreviewViewModel(repository)
        viewModel.load("p1")

        viewModel.addPack()

        assertFalse(viewModel.uiState.value.isAdding)
        assertFalse(viewModel.uiState.value.preview!!.isInLibrary)
        assertEquals("disk full", viewModel.uiState.value.error?.message)
    }

    @Test
    fun `opening another pack drops the look-up of the first`() {
        val slow = CompletableDeferred<Result<StickerPackPreview>>()
        coEvery { repository.viewPack("slow") } coAnswers { slow.await() }
        coEvery { repository.viewPack("p2") } returns Result.success(preview("p2"))
        val viewModel = StickerPackPreviewViewModel(repository)

        viewModel.load("slow")
        viewModel.load("p2")
        slow.complete(Result.success(preview("slow")))

        assertEquals("p2", viewModel.uiState.value.preview?.packId)
    }
}
