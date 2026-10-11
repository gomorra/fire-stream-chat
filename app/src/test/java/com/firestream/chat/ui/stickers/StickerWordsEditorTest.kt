package com.firestream.chat.ui.stickers

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import com.firestream.chat.domain.model.StickerAutoName
import com.firestream.chat.domain.util.StickerWords
import com.firestream.chat.test.fakes.testSticker
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
// Stub Application to bypass FireStreamApp's Hilt + Firebase init.
@Config(sdk = [31], application = android.app.Application::class)
class StickerWordsEditorTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    private val sticker = testSticker("s1", "😺", "🐱").copy(names = listOf("Old name", "Katze"))

    @Test
    fun `the edit starts from the sticker's words, and saves what is left after an add and two removes`() {
        val saved = mutableListOf<Pair<List<String>, List<String>>>()
        var cancels = 0
        composeTestRule.setContent {
            MaterialTheme {
                StickerWordsEditor(
                    sticker = sticker,
                    onSave = { emojis, names, stopAutoNaming ->
                        saved += emojis to names
                        assertFalse("nothing was taken over, so naming goes on", stopAutoNaming)
                    },
                    onCancel = { cancels++ },
                )
            }
        }

        composeTestRule.onNodeWithTag(STICKER_NAME_FIELD_TAG).assertDoesNotExist()
        composeTestRule.onNodeWithText(ADD_NAMES_LABEL).performScrollTo().performClick()
        composeTestRule.onNodeWithContentDescription("Add this name").assertIsNotEnabled()
        composeTestRule.onNodeWithTag(STICKER_NAME_FIELD_TAG).performTextInput(" Grumpy cat ")
        composeTestRule.onNodeWithContentDescription("Add this name").performClick()
        // A repeat adds nothing.
        composeTestRule.onNodeWithTag(STICKER_NAME_FIELD_TAG).performTextInput("KATZE")
        composeTestRule.onNodeWithContentDescription("Add this name").performClick()
        // A tap on a chip takes it out.
        composeTestRule.onNodeWithText("Old name").performScrollTo().performClick()
        composeTestRule.onNodeWithText("🐱").performScrollTo().performClick()
        composeTestRule.onNodeWithText("Save").performScrollTo().performClick()

        assertEquals(listOf(listOf("😺") to listOf("Katze", "Grumpy cat")), saved)
        assertEquals(0, cancels)
    }

    @Test
    fun `what was found automatically is shown and cannot be removed`() {
        val named = sticker.copy(
            autoName = StickerAutoName(nameEn = "Sleepy bear", nameDe = "Müder Bär", words = listOf("tired", "Schlaf"), text = "Gute Nacht"),
        )
        var saves = 0
        composeTestRule.setContent {
            MaterialTheme { StickerWordsEditor(sticker = named, onSave = { _, _, _ -> saves++ }, onCancel = {}) }
        }

        composeTestRule.onNodeWithText("Found automatically").performScrollTo().assertIsDisplayed()
        composeTestRule.onNodeWithText("Müder Bär · Sleepy bear").performScrollTo().performClick()
        composeTestRule.onNodeWithText("tired, Schlaf").assertExists()
        composeTestRule.onNodeWithText("Text: Gute Nacht").assertExists()
        composeTestRule.onNodeWithContentDescription("Remove Sleepy bear").assertDoesNotExist()
        assertEquals(0, saves)
    }

    @Test
    fun `use as mine puts the automatic names among the names, and its save stops automatic naming`() {
        val named = sticker.copy(
            autoName = StickerAutoName(nameEn = "Sleepy bear", nameDe = "Müder Bär", words = listOf("tired", "katze"), text = "Gute Nacht", emojis = listOf("😴")),
        )
        val saved = mutableListOf<Triple<List<String>, List<String>, Boolean>>()
        composeTestRule.setContent {
            MaterialTheme {
                StickerWordsEditor(sticker = named, onSave = { emojis, names, stop -> saved += Triple(emojis, names, stop) }, onCancel = {})
            }
        }

        composeTestRule.onNodeWithText(USE_AS_MINE_LABEL).performScrollTo().performClick()

        // The button is used up, and the names can be taken out like typed ones.
        composeTestRule.onNodeWithText(USE_AS_MINE_LABEL).assertDoesNotExist()
        composeTestRule.onNodeWithContentDescription("Remove Sleepy bear").assertExists()
        composeTestRule.onNodeWithText("tired").performScrollTo().performClick()
        composeTestRule.onNodeWithText("Save").performScrollTo().performClick()

        // The German name first, a word the sticker had already is not repeated, and the emojis and the text stay out.
        assertEquals(
            listOf(Triple(listOf("😺", "🐱"), listOf("Old name", "Katze", "Müder Bär", "Sleepy bear"), true)),
            saved,
        )
    }

    @Test
    fun `the add chips step aside at the limits`() {
        val full = (1..StickerWords.MAX_NAMES).map { "n$it" }
        composeTestRule.setContent {
            MaterialTheme {
                StickerWordChips(emojis = listOf("😺"), names = full, callbacks = StickerWordCallbacks(), maxEmojis = 1)
            }
        }

        composeTestRule.onNodeWithText(ADD_EMOJI_LABEL).assertDoesNotExist()
        composeTestRule.onNodeWithText(ADD_NAMES_LABEL).assertDoesNotExist()
        composeTestRule.onNodeWithText("n8").assertExists()
    }
}
