package com.firestream.chat.ui.stickers

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.firestream.chat.domain.model.WhatsAppStickerFile
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
// Stub Application to bypass FireStreamApp's Hilt + Firebase init.
@Config(sdk = [31], application = android.app.Application::class)
class WhatsAppImportScreenTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    private val old = WhatsAppStickerFile("content://wa/old", "old.webp", 10, lastModified = 100)
    private val new = WhatsAppStickerFile("content://wa/new", "new.webp", 10, lastModified = 300)

    private fun show(state: WhatsAppImportState, onSetShowAll: (Boolean) -> Unit = {}) {
        composeTestRule.setContent {
            MaterialTheme {
                WhatsAppImportScreen(
                    state = state,
                    isImporting = false,
                    onClose = {},
                    onToggleFile = {},
                    onToggleSelectAll = {},
                    onImport = {},
                    onSetShowAll = onSetShowAll,
                )
            }
        }
    }

    private fun folder(importedUntil: Long, showAll: Boolean = false) =
        WhatsAppImportState(isLoading = false, files = listOf(new, old), importedUntil = importedUntil, showAll = showAll)

    @Test
    fun `before the first import every file is shown and there is no switch`() {
        show(folder(importedUntil = 0))

        composeTestRule.onNodeWithTag(stickerCellTag(old.uri)).assertExists()
        composeTestRule.onNodeWithTag(stickerCellTag(new.uri)).assertExists()
        composeTestRule.onNodeWithTag(SHOW_ALL_SWITCH_TAG).assertDoesNotExist()
        composeTestRule.onNodeWithText("Every sticker you have sent", substring = true).assertIsDisplayed()
    }

    @Test
    fun `after an import only the newer files are shown, and the switch asks for the rest`() {
        val asked = mutableListOf<Boolean>()
        show(folder(importedUntil = 200), onSetShowAll = { asked += it })

        composeTestRule.onNodeWithTag(stickerCellTag(new.uri)).assertExists()
        composeTestRule.onNodeWithTag(stickerCellTag(old.uri)).assertDoesNotExist()
        composeTestRule.onNodeWithText("since your last import", substring = true).assertIsDisplayed()
        composeTestRule.onNodeWithTag(SHOW_ALL_SWITCH_TAG).assertIsOff().performClick()

        assertEquals(listOf(true), asked)
    }

    @Test
    fun `show all brings the older files back`() {
        show(folder(importedUntil = 200, showAll = true))

        composeTestRule.onNodeWithTag(SHOW_ALL_SWITCH_TAG).assertIsOn()
        composeTestRule.onNodeWithTag(stickerCellTag(old.uri)).assertExists()
    }

    @Test
    fun `a folder with nothing new says so and keeps the switch and the import button`() {
        show(folder(importedUntil = 300))

        composeTestRule.onNodeWithText("Nothing new").assertIsDisplayed()
        composeTestRule.onNodeWithTag(SHOW_ALL_SWITCH_TAG).assertIsOff()
        composeTestRule.onNodeWithText("Select all").assertDoesNotExist()
        composeTestRule.onNodeWithText("Import 0").assertIsDisplayed()
    }
}
