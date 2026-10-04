package com.firestream.chat.ui.stickers

import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.dp
import com.firestream.chat.domain.model.AppError
import com.firestream.chat.domain.model.Sticker
import com.firestream.chat.domain.model.StickerPackPreview
import com.firestream.chat.test.fakes.testSticker
import com.firestream.chat.ui.chat.StickerActionRows
import com.firestream.chat.ui.components.LibraryStickerImage
import com.firestream.chat.ui.components.LocalStickerFetcher
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * What the sticker sheet and the pack preview offer, and the fetch of a
 * sticker's file when a cell shows a sticker that has none.
 */
@RunWith(RobolectricTestRunner::class)
// Stub Application to bypass FireStreamApp's Hilt + Firebase init.
@Config(sdk = [31], application = android.app.Application::class)
class StickerPackSheetTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    @get:Rule
    val folder = TemporaryFolder()

    private fun preview(isInLibrary: Boolean = false) = StickerPackPreview(
        packId = "p1",
        rootPackId = "p1",
        name = "Cats",
        publisher = "Ana",
        stickers = listOf(testSticker("cat", "😺"), testSticker("kitten", "😺")),
        isInLibrary = isInLibrary,
    )

    private fun setSheet(uiState: StickerPackPreviewUiState, onAddPack: () -> Unit = {}) {
        composeTestRule.setContent {
            MaterialTheme { StickerPackSheetContent(uiState = uiState, onAddPack = onAddPack) }
        }
    }

    @Test
    fun `a pack shows its name, its count and its stickers, and can be added`() {
        var added = 0
        setSheet(StickerPackPreviewUiState(isLoading = false, preview = preview()), onAddPack = { added++ })

        composeTestRule.onNodeWithText("Cats").assertIsDisplayed()
        composeTestRule.onNodeWithText("2 stickers · Ana").assertIsDisplayed()
        composeTestRule.onNodeWithTag(stickerCellTag("cat")).assertIsDisplayed()
        composeTestRule.onNodeWithTag(stickerCellTag("kitten")).assertIsDisplayed()
        composeTestRule.onNodeWithText("Add pack").performClick()

        assertEquals(1, added)
    }

    @Test
    fun `a pack the library holds says so and offers nothing to add`() {
        setSheet(StickerPackPreviewUiState(isLoading = false, preview = preview(isInLibrary = true)))

        composeTestRule.onNodeWithText("This pack is in your library").assertIsDisplayed()
        composeTestRule.onNodeWithText("Add pack").assertDoesNotExist()
    }

    @Test
    fun `a pack that could not be found shows why`() {
        val gone = AppError.from(NoSuchElementException("This pack is no longer available"))
        setSheet(StickerPackPreviewUiState(isLoading = false, error = gone))

        composeTestRule.onNodeWithText("This pack is no longer available").assertIsDisplayed()
        composeTestRule.onNodeWithText("Add pack").assertDoesNotExist()
    }

    @Test
    fun `a loading pack offers nothing yet`() {
        setSheet(StickerPackPreviewUiState())

        composeTestRule.onNodeWithText("Add pack").assertDoesNotExist()
        composeTestRule.onNodeWithText("This pack is no longer available").assertDoesNotExist()
    }

    // --- The sticker sheet's rows ---

    @Test
    fun `a sticker that names a pack offers View pack, and one that names none does not`() {
        var viewed = 0
        composeTestRule.setContent {
            MaterialTheme {
                StickerActionRows(isFavourite = false, onToggleFavourite = {}, onViewPack = { viewed++ })
            }
        }
        composeTestRule.onNodeWithText("Add to favourites").assertIsDisplayed()
        composeTestRule.onNodeWithText("View pack").performClick()
        assertEquals(1, viewed)
    }

    @Test
    fun `a sticker without a pack has the favourites row only`() {
        composeTestRule.setContent {
            MaterialTheme { StickerActionRows(isFavourite = true, onToggleFavourite = {}, onViewPack = null) }
        }

        composeTestRule.onNodeWithText("Remove from favourites").assertIsDisplayed()
        composeTestRule.onNodeWithText("View pack").assertDoesNotExist()
    }

    // --- Fetch on display ---

    private fun setImage(sticker: Sticker, fetch: suspend (Sticker) -> Boolean) {
        composeTestRule.setContent {
            MaterialTheme {
                CompositionLocalProvider(LocalStickerFetcher provides fetch) {
                    LibraryStickerImage(sticker = sticker, modifier = Modifier.size(64.dp))
                }
            }
        }
    }

    @Test
    fun `a sticker without a file shows a placeholder and asks for the file once`() {
        val asked = mutableListOf<String>()
        setImage(testSticker("cat")) { asked += it.id; false }

        composeTestRule.onNodeWithContentDescription("Sticker, not downloaded yet").assertIsDisplayed()
        composeTestRule.waitForIdle()
        assertEquals(listOf("cat"), asked)
    }

    @Test
    fun `a sticker whose file arrives is drawn from it`() {
        val file = folder.root.resolve("cat.webp")
        val sticker = testSticker("cat").copy(localPath = file.absolutePath)
        setImage(sticker) {
            file.writeBytes(byteArrayOf(1))
            true
        }

        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithContentDescription("Sticker, not downloaded yet").assertDoesNotExist()
        composeTestRule.onNodeWithContentDescription("Sticker").assertIsDisplayed()
    }

    @Test
    fun `a sticker whose file is there asks for nothing`() {
        val file = folder.newFile("cat.webp")
        var asked = 0
        setImage(testSticker("cat").copy(localPath = file.absolutePath)) { asked++; true }

        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithContentDescription("Sticker").assertIsDisplayed()
        assertEquals(0, asked)
    }
}
