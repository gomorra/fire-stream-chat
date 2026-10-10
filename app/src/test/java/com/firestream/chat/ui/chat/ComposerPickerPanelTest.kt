package com.firestream.chat.ui.chat

import androidx.compose.foundation.layout.height
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.unit.dp
import com.firestream.chat.domain.model.Sticker
import com.firestream.chat.domain.model.StickerPack
import com.firestream.chat.domain.model.StickerPackKind
import com.firestream.chat.test.fakes.testSticker
import com.firestream.chat.test.fakes.testStickerPack
import com.firestream.chat.ui.chat.picker.PickerPanelState
import com.firestream.chat.ui.chat.picker.PickerSelection
import com.firestream.chat.ui.chat.picker.PickerTab
import com.firestream.chat.ui.stickers.stickerCellTag
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
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
    private var creates = 0

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
                        onManageStickers = { imports++ },
                        onCreateSticker = { creates++ },
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
    fun `the maker opens from an empty library and from the end of the pack row`() {
        var packs by mutableStateOf(emptyList<StickerPack>())
        composeTestRule.setContent {
            MaterialTheme {
                ComposerPickerPanel(
                    recentEmojis = emptyList(),
                    stickerPacks = packs,
                    recentStickers = emptyList(),
                    callbacks = ComposerPickerCallbacks({ _, _ -> }, {}, {}, {}, {}, {}, { creates++ }),
                    modifier = Modifier.height(360.dp),
                )
            }
        }
        openStickers()
        composeTestRule.onNodeWithText("Make a sticker").performClick()

        packs = listOf(pack("animals", StickerPackKind.USER, cat))
        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithContentDescription("Make a sticker").performClick()

        assertEquals(2, creates)
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
    fun `the manage button at the end of the pack row opens the library`() {
        setPanel(packs = listOf(pack("animals", StickerPackKind.USER, cat)))
        openStickers()

        composeTestRule.onNodeWithContentDescription("Manage stickers").performClick()

        assertEquals(1, imports)
    }

    @Test
    fun `a pick from the WhatsApp shelf carries the id of the sticker's own pack`() {
        val big = listOf("a", "b", "c", "d").map { testSticker(it) }.toTypedArray()
        setPanel(
            packs = listOf(
                pack("zoo", StickerPackKind.USER, *big).copy(shownInRow = false),
                pack("pets", StickerPackKind.USER, cat).copy(shownInRow = false),
                pack("wild", StickerPackKind.USER, fox).copy(shownInRow = false),
            ),
        )
        openStickers()

        // The three packs share one entry of the row, and none has its own.
        composeTestRule.onNodeWithContentDescription("WhatsApp").assertIsDisplayed()
        composeTestRule.onNodeWithContentDescription("zoo").assertDoesNotExist()
        composeTestRule.onNodeWithText("zoo · 4").assertIsDisplayed()
        composeTestRule.onNodeWithText("More · 2").assertIsDisplayed()

        composeTestRule.onNodeWithTag(stickerCellTag("b")).performClick()
        composeTestRule.onNodeWithTag(stickerCellTag("fox")).performClick()
        composeTestRule.onNodeWithTag(stickerCellTag("cat")).performClick()

        assertEquals(
            listOf(
                PickerSelection.Sticker("b", "zoo"),
                PickerSelection.Sticker("fox", "wild"),
                PickerSelection.Sticker("cat", "pets"),
            ),
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
                    callbacks = ComposerPickerCallbacks({ _, _ -> }, {}, {}, {}, {}, {}, {}),
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
    fun `the pack the tab opened on stays open when a first send adds Recents in front`() {
        var recents by mutableStateOf(emptyList<Sticker>())
        composeTestRule.setContent {
            MaterialTheme {
                ComposerPickerPanel(
                    recentEmojis = emptyList(),
                    stickerPacks = listOf(pack("animals", StickerPackKind.USER, cat, dog)),
                    recentStickers = recents,
                    callbacks = ComposerPickerCallbacks({ _, _ -> }, {}, {}, {}, {}, {}, {}),
                    modifier = Modifier.height(360.dp),
                )
            }
        }
        openStickers()
        composeTestRule.onNodeWithText("animals · 2").assertIsDisplayed()

        recents = listOf(dog)
        composeTestRule.waitForIdle()

        composeTestRule.onNodeWithContentDescription("Recents").assertIsDisplayed()
        composeTestRule.onNodeWithText("animals · 2").assertIsDisplayed()
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

    // ── Search ───────────────────────────────────────────────────────────────

    @Test
    fun `opening the search focuses its field, so the keyboard comes up at once`() {
        setPanel()

        composeTestRule.onNodeWithContentDescription("Search").performClick()

        composeTestRule.onNodeWithContentDescription("Search field").assertIsFocused()
    }

    @Test
    fun `the compact emoji search is one row of results above the search field`() {
        val state = PickerPanelState(PickerTab.EMOJI).apply { openSearch() }
        val emojis = mutableListOf<String>()
        composeTestRule.setContent {
            MaterialTheme {
                ComposerPickerPanel(
                    recentEmojis = listOf("😀"),
                    stickerPacks = emptyList(),
                    recentStickers = emptyList(),
                    callbacks = ComposerPickerCallbacks({ emoji, _ -> emojis += emoji }, {}, {}, {}, {}, {}, {}),
                    state = state,
                    compact = true,
                )
            }
        }

        // No query yet: the row offers the recents.
        composeTestRule.onNodeWithText("😀").assertIsDisplayed()

        composeTestRule.onNodeWithContentDescription("Search field").performTextInput("cat")
        composeTestRule.waitForIdle()
        val strip = composeTestRule.onNodeWithContentDescription("Emoji results").fetchSemanticsNode().boundsInRoot
        val field = composeTestRule.onNodeWithContentDescription("Search field").fetchSemanticsNode().boundsInRoot
        assertTrue("results sit above the field", strip.bottom <= field.top)

        composeTestRule.onNodeWithText("😺").performClick()
        assertEquals(listOf("😺"), emojis)
        // Picking keeps the search open, so several emoji can be picked in a row.
        assertEquals(PickerTab.EMOJI, state.searchingTab)
        assertEquals("cat", state.query)
    }

    @Test
    fun `the compact emoji search says when nothing matches`() {
        val state = PickerPanelState(PickerTab.EMOJI, initialSearchOpen = true, initialQuery = "zzzzqq")
        composeTestRule.setContent {
            MaterialTheme {
                ComposerPickerPanel(
                    recentEmojis = emptyList(),
                    stickerPacks = emptyList(),
                    recentStickers = emptyList(),
                    callbacks = ComposerPickerCallbacks({ _, _ -> }, {}, {}, {}, {}, {}, {}),
                    state = state,
                    compact = true,
                )
            }
        }

        composeTestRule.onNodeWithText("No emoji found").assertIsDisplayed()
    }

    @Test
    fun `a hoisted state carries the query when the panel is mounted somewhere else`() {
        val state = PickerPanelState(PickerTab.STICKER_LIBRARY, initialSearchOpen = true, initialQuery = "dog")
        var place by mutableStateOf(0)
        composeTestRule.setContent {
            MaterialTheme {
                // Two call sites, as the composer has: the keyboard's place and the overlay.
                if (place == 0) {
                    ComposerPickerPanel(
                        recentEmojis = emptyList(),
                        stickerPacks = listOf(pack("animals", StickerPackKind.USER, cat, dog)),
                        recentStickers = emptyList(),
                        callbacks = ComposerPickerCallbacks({ _, _ -> }, {}, {}, { picks += it }, {}, {}, {}),
                        state = state,
                        modifier = Modifier.height(360.dp),
                    )
                } else {
                    ComposerPickerPanel(
                        recentEmojis = emptyList(),
                        stickerPacks = listOf(pack("animals", StickerPackKind.USER, cat, dog)),
                        recentStickers = emptyList(),
                        callbacks = ComposerPickerCallbacks({ _, _ -> }, {}, {}, { picks += it }, {}, {}, {}),
                        state = state,
                        modifier = Modifier.height(600.dp),
                    )
                }
            }
        }

        place = 1
        composeTestRule.waitForIdle()

        composeTestRule.onNodeWithContentDescription("Search field").assertTextContains("dog")
        composeTestRule.onNodeWithTag(stickerCellTag("dog")).performClick()
        composeTestRule.onNodeWithTag(stickerCellTag("cat")).assertDoesNotExist()
        assertEquals(listOf(PickerSelection.Sticker("dog", "animals")), picks)
    }
}
