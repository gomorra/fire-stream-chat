package com.firestream.chat.ui.stickers.create

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import com.firestream.chat.domain.model.StickerDraft
import com.firestream.chat.domain.model.StickerDraftImage
import com.firestream.chat.test.fakes.testStickerPack
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** What the maker offers in each of its states. The picture itself is not drawn here: its file does not exist. */
@RunWith(RobolectricTestRunner::class)
// Stub Application to bypass FireStreamApp's Hilt + Firebase init.
@Config(sdk = [31], application = android.app.Application::class)
class StickerCreateScreenTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    private val original = StickerDraftImage("/draft/original.png", 800, 400)
    private val withSubject = StickerDraft(
        original = original,
        cutout = StickerDraftImage("/draft/cutout.png", 300, 300),
        outlined = StickerDraftImage("/draft/outlined.png", 316, 316),
    )

    private fun setContent(uiState: StickerCreateUiState, actions: StickerCreateActions = StickerCreateActions()) {
        composeTestRule.setContent {
            MaterialTheme { StickerCreateContent(uiState = uiState, actions = actions) }
        }
    }

    @Test
    fun `without a photo the screen offers to choose one, and nothing can be saved`() {
        var picks = 0
        setContent(StickerCreateUiState(), StickerCreateActions(onPickPhoto = { picks++ }))

        composeTestRule.onNodeWithText("Choose a photo").performClick()

        assertEquals(1, picks)
        composeTestRule.onNodeWithText("Save").assertIsNotEnabled()
        composeTestRule.onNodeWithTag(STICKER_PREVIEW_TAG).assertDoesNotExist()
    }

    @Test
    fun `a photo being prepared shows neither the editor nor the photo button`() {
        setContent(StickerCreateUiState(isPreparing = true))

        composeTestRule.onNodeWithText("Looking for the subject…").assertIsDisplayed()
        composeTestRule.onNodeWithText("Choose a photo").assertDoesNotExist()
        composeTestRule.onNodeWithTag(STICKER_PREVIEW_TAG).assertDoesNotExist()
    }

    @Test
    fun `a draft with a cutout offers the cutout, the photo and the outline`() {
        val cutoutChoices = mutableListOf<Boolean>()
        val outlineChoices = mutableListOf<Boolean>()
        var saves = 0
        setContent(
            StickerCreateUiState(draft = withSubject),
            StickerCreateActions(onUseCutout = { cutoutChoices += it }, onOutline = { outlineChoices += it }, onSave = { saves++ }),
        )

        composeTestRule.onNodeWithTag(STICKER_PREVIEW_TAG).assertIsDisplayed()
        composeTestRule.onNodeWithText("Original").performClick()
        composeTestRule.onNodeWithText("Outline").performClick()
        composeTestRule.onNodeWithText("Save").assertIsEnabled().performClick()

        assertEquals(listOf(false), cutoutChoices)
        assertEquals("the outline was on, so the tap turns it off", listOf(false), outlineChoices)
        assertEquals(1, saves)
    }

    @Test
    fun `the outline is not offered for the whole photo`() {
        setContent(StickerCreateUiState(draft = withSubject, useCutout = false))

        composeTestRule.onNodeWithText("Cutout").assertIsDisplayed()
        composeTestRule.onNodeWithText("Outline").assertDoesNotExist()
    }

    @Test
    fun `a draft without a cutout offers the crop only and says why`() {
        setContent(StickerCreateUiState(draft = StickerDraft(original, null, null)))

        composeTestRule.onNodeWithTag(STICKER_PREVIEW_TAG).assertIsDisplayed()
        composeTestRule.onNodeWithText("Cutout").assertDoesNotExist()
        composeTestRule.onNodeWithText("Outline").assertDoesNotExist()
        composeTestRule.onNodeWithText("No subject was cut out", substring = true).assertIsDisplayed()
    }

    @Test
    fun `the user's packs and a new pack are offered, and only a new pack asks for a name`() {
        val choices = mutableListOf<String?>()
        setContent(
            StickerCreateUiState(draft = withSubject, packs = listOf(testStickerPack("Holiday")), packId = "Holiday"),
            StickerCreateActions(onChoosePack = { choices += it }),
        )

        composeTestRule.onNodeWithText("Pack name").assertDoesNotExist()
        composeTestRule.onNodeWithText("Holiday").performScrollTo().assertIsDisplayed()
        composeTestRule.onNodeWithText("New pack").performScrollTo().performClick()

        assertEquals(listOf<String?>(null), choices)
    }

    @Test
    fun `a chosen emoji is taken out by a tap, and a fourth cannot be added`() {
        val removed = mutableListOf<String>()
        setContent(
            StickerCreateUiState(draft = withSubject, emojis = listOf("😺", "🐶", "🦊")),
            StickerCreateActions(onRemoveEmoji = { removed += it }),
        )

        composeTestRule.onNodeWithText("Add emoji").assertDoesNotExist()
        composeTestRule.onNodeWithText("🐶").performScrollTo().performClick()

        assertEquals(listOf("🐶"), removed)
    }
}
