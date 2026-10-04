package com.firestream.chat.ui.chat

import androidx.compose.foundation.layout.height
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.unit.dp
import com.firestream.chat.domain.model.Sticker
import com.firestream.chat.domain.model.StickerPack
import com.firestream.chat.domain.model.StickerPackKind
import com.firestream.chat.test.fakes.testSticker
import com.firestream.chat.test.fakes.testStickerPack
import com.firestream.chat.ui.chat.picker.PickerSelection
import com.firestream.chat.ui.stickers.stickerCellTag
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The composer's picker: the island with the emoji grid and the sticker
 * library, and the suggestion strip above the composer. The shell's own chrome
 * is covered by `PickerPanelTest`.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [31], application = android.app.Application::class)
class ComposerPickerPanelTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    private fun pack(id: String, kind: StickerPackKind, vararg stickers: Sticker) = testStickerPack(id, kind, *stickers)

    private val cat = testSticker("cat", "😺")
    private val dog = testSticker("dog", "🐶")
    private val fox = testSticker("fox", "🦊")

    private val picks = mutableListOf<PickerSelection.Sticker>()
    private val favourites = mutableListOf<String>()
    private var backspaces = 0
    private var imports = 0

    private fun setPanel(packs: List<StickerPack> = emptyList(), recents: List<Sticker> = emptyList()) {
        composeTestRule.setContent {
            MaterialTheme {
                ComposerPickerPanel(
                    recentEmojis = listOf("😀"),
                    stickerPacks = packs,
                    recentStickers = recents,
                    callbacks = ComposerPickerCallbacks(
                        onEmoji = { _, _ -> },
                        onBackspace = { backspaces++ },
                        onRecentEmojiUsed = {},
                        onSticker = { picks += it },
                        onToggleStickerFavourite = { favourites += it },
                        onImportStickers = { imports++ },
                    ),
                    modifier = Modifier.height(360.dp),
                )
            }
        }
    }

    private fun openStickers() {
        composeTestRule.onNodeWithContentDescription("Stickers").performClick()
        composeTestRule.waitForIdle()
    }

    @Test
    fun `the composer's picker opens on emoji, with an island and a backspace key on both tabs`() {
        setPanel()

        composeTestRule.onNodeWithContentDescription("Picker tabs").assertIsDisplayed()
        composeTestRule.onNodeWithText("Emoji").assertIsDisplayed()
        composeTestRule.onNodeWithContentDescription("Backspace").performClick()

        openStickers()
        composeTestRule.onNodeWithContentDescription("Backspace").performClick()

        assertEquals(2, backspaces)
    }

    @Test
    fun `an empty library offers the import`() {
        setPanel()
        openStickers()

        composeTestRule.onNodeWithText("Import stickers").performClick()

        assertEquals(1, imports)
    }

    @Test
    fun `a pick from Recents carries no pack, and a pick from a pack carries its id`() {
        setPanel(packs = listOf(pack("animals", StickerPackKind.USER, cat, dog)), recents = listOf(dog))
        openStickers()

        // Recents is the first shelf, so the tab opens on it.
        composeTestRule.onNodeWithTag(stickerCellTag("dog")).performClick()
        composeTestRule.onNodeWithContentDescription("animals").performClick()
        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithTag(stickerCellTag("cat")).performClick()

        assertEquals(
            listOf(PickerSelection.Sticker("dog", null), PickerSelection.Sticker("cat", "animals")),
            picks,
        )
    }

    @Test
    fun `the favourites come before the packs and are labelled by kind`() {
        setPanel(
            packs = listOf(
                pack("animals", StickerPackKind.USER, cat),
                pack("favs", StickerPackKind.FAVOURITES, fox),
            ),
        )
        openStickers()

        // No Recents, so the tab opens on the first shelf, which is the favourites.
        composeTestRule.onNodeWithText("Favourites · 1").assertIsDisplayed()
        composeTestRule.onNodeWithTag(stickerCellTag("fox")).assertIsDisplayed()
    }

    @Test
    fun `a long press on a sticker flips its favourite and sends nothing`() {
        setPanel(packs = listOf(pack("animals", StickerPackKind.USER, cat)))
        openStickers()

        composeTestRule.onNodeWithTag(stickerCellTag("cat")).performTouchInput { longClick() }

        assertEquals(listOf("cat"), favourites)
        assertEquals(emptyList<PickerSelection.Sticker>(), picks)
    }

    @Test
    fun `Recents keeps its order while the panel is open`() {
        var recents by mutableStateOf(listOf(cat, dog))
        composeTestRule.setContent {
            MaterialTheme {
                ComposerPickerPanel(
                    recentEmojis = emptyList(),
                    stickerPacks = listOf(pack("animals", StickerPackKind.USER, cat, dog)),
                    recentStickers = recents,
                    callbacks = ComposerPickerCallbacks({ _, _ -> }, {}, {}, {}, {}, {}),
                    modifier = Modifier.height(360.dp),
                )
            }
        }
        openStickers()
        val catLeft = composeTestRule.onNodeWithTag(stickerCellTag("cat")).fetchSemanticsNode().positionInRoot.x

        // Sending the dog moves it to the front of the stored recents.
        recents = listOf(dog, cat)
        composeTestRule.waitForIdle()

        val catLeftAfter = composeTestRule.onNodeWithTag(stickerCellTag("cat")).fetchSemanticsNode().positionInRoot.x
        assertEquals(catLeft, catLeftAfter)
    }

    @Test
    fun `one emoji in the composer offers its stickers, and a pick names the pack`() {
        var text by mutableStateOf("hello")
        composeTestRule.setContent {
            MaterialTheme {
                StickerSuggestionStrip(
                    text = text,
                    packs = listOf(pack("animals", StickerPackKind.USER, cat, dog)),
                    onSelection = { picks += it },
                )
            }
        }
        composeTestRule.onNodeWithContentDescription("Sticker suggestions").assertDoesNotExist()

        text = "🐶"
        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithTag(stickerCellTag("dog")).performClick()

        assertEquals(listOf(PickerSelection.Sticker("dog", "animals")), picks)
    }
}
