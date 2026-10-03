package com.firestream.chat.ui.stickers

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.firestream.chat.domain.model.StickerPack
import com.firestream.chat.domain.model.StickerPackKind
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
// Stub Application to bypass FireStreamApp's Hilt + Firebase init.
@Config(sdk = [31], application = android.app.Application::class)
class StickerLibraryScreenTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    @Test
    fun `an empty library says so and offers both import routes`() {
        var whatsApp = 0
        var files = 0
        composeTestRule.setContent {
            MaterialTheme {
                StickerLibraryContent(
                    uiState = StickerLibraryUiState(isLoading = false),
                    actions = StickerLibraryActions(
                        onImportFromWhatsApp = { whatsApp++ },
                        onImportFromFiles = { files++ },
                    ),
                )
            }
        }

        composeTestRule.onNodeWithText("No stickers yet").assertIsDisplayed()
        composeTestRule.onNodeWithText("Packs").assertDoesNotExist()
        composeTestRule.onNodeWithText("From WhatsApp").performClick()
        composeTestRule.onNodeWithText("From files").performClick()

        assertEquals(1, whatsApp)
        assertEquals(1, files)
    }

    @Test
    fun `a loading library does not claim to be empty`() {
        composeTestRule.setContent {
            MaterialTheme { StickerLibraryContent(uiState = StickerLibraryUiState(), actions = StickerLibraryActions()) }
        }

        composeTestRule.onNodeWithText("No stickers yet").assertDoesNotExist()
    }

    @Test
    fun `the unnamed packs are listed by kind with their count`() {
        val favourites = StickerPack("f", "", null, StickerPackKind.FAVOURITES, null, emptyList(), 0, 0)
        composeTestRule.setContent {
            MaterialTheme {
                StickerLibraryContent(
                    uiState = StickerLibraryUiState(isLoading = false, packs = listOf(favourites)),
                    actions = StickerLibraryActions(),
                )
            }
        }

        composeTestRule.onNodeWithText("Favourites").assertIsDisplayed()
        composeTestRule.onNodeWithText("0 stickers").assertIsDisplayed()
        composeTestRule.onNodeWithText("No stickers yet").assertDoesNotExist()
    }
}
