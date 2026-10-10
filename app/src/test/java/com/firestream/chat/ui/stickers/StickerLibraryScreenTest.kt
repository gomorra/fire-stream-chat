package com.firestream.chat.ui.stickers

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.click
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import com.firestream.chat.domain.model.StickerPack
import com.firestream.chat.domain.model.StickerPackKind
import com.firestream.chat.test.fakes.testSticker
import com.firestream.chat.test.fakes.testStickerPack
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
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

    private val cats = testStickerPack("Cats", StickerPackKind.USER, testSticker("c1"), testSticker("shared"))
    private val dogs = testStickerPack("Dogs", StickerPackKind.USER, testSticker("d1"), testSticker("shared"))
        .copy(shownInRow = false)
    private val birds = testStickerPack("Birds").copy(shownInRow = false)
    private val favourites = testStickerPack("f", StickerPackKind.FAVOURITES)

    private fun show(uiState: StickerLibraryUiState, actions: StickerLibraryActions = StickerLibraryActions()) {
        composeTestRule.setContent {
            MaterialTheme { StickerLibraryContent(uiState = uiState, actions = actions) }
        }
    }

    private fun library(vararg packs: StickerPack, selected: Set<String> = emptySet()) =
        StickerLibraryUiState(isLoading = false, packs = packs.toList(), selectedPackIds = selected)

    @Test
    fun `an empty library says so and the plus offers the maker and both import routes`() {
        val picked = mutableListOf<String>()
        show(
            StickerLibraryUiState(isLoading = false),
            StickerLibraryActions(
                onCreate = { picked += "create" },
                onImportFromWhatsApp = { picked += "whatsapp" },
                onImportFromFiles = { picked += "files" },
            ),
        )

        composeTestRule.onNodeWithText("No stickers yet").assertIsDisplayed()
        composeTestRule.onNodeWithText(ROW_GROUP_TITLE).assertDoesNotExist()
        // The routes are in the menu, not in the list.
        composeTestRule.onNodeWithText("From WhatsApp").assertDoesNotExist()
        listOf("Create", "From WhatsApp", "From files").forEach { entry ->
            composeTestRule.onNodeWithContentDescription("Add stickers").performClick()
            composeTestRule.onNodeWithText(entry).performClick()
        }

        assertEquals(listOf("create", "whatsapp", "files"), picked)
    }

    @Test
    fun `a running import switches the import routes off and leaves the maker on`() {
        show(StickerLibraryUiState(isLoading = false, isImporting = true))

        composeTestRule.onNodeWithContentDescription("Add stickers").performClick()

        composeTestRule.onNodeWithText("From WhatsApp").assertIsNotEnabled()
        composeTestRule.onNodeWithText("From files").assertIsNotEnabled()
        composeTestRule.onNodeWithText("Create").assertIsEnabled()
    }

    @Test
    fun `a loading library does not claim to be empty`() {
        show(StickerLibraryUiState())

        composeTestRule.onNodeWithText("No stickers yet").assertDoesNotExist()
    }

    @Test
    fun `the unnamed packs are listed by kind with their count and have no switch`() {
        show(library(favourites))

        composeTestRule.onNodeWithText("Favourites").assertIsDisplayed()
        composeTestRule.onNodeWithText("0 stickers", substring = true).assertIsDisplayed()
        composeTestRule.onNodeWithText("No stickers yet").assertDoesNotExist()
        composeTestRule.onNodeWithTag(ownThumbnailSwitchTag("f")).assertDoesNotExist()
        composeTestRule.onNodeWithText(ROW_GROUP_TITLE).assertIsDisplayed()
        composeTestRule.onNodeWithText(GROUPED_GROUP_TITLE).assertDoesNotExist()
    }

    @Test
    fun `the packs are listed in two groups and the switch moves a pack between them`() {
        val switched = mutableListOf<Pair<String, Boolean>>()
        show(library(dogs, cats, favourites), StickerLibraryActions(onSetShownInRow = { id, shown -> switched += id to shown }))

        composeTestRule.onNodeWithText(ROW_GROUP_TITLE).assertIsDisplayed()
        composeTestRule.onNodeWithText(GROUPED_GROUP_TITLE).assertIsDisplayed()
        composeTestRule.onNodeWithTag(ownThumbnailSwitchTag("Cats")).assertIsOn()
        composeTestRule.onNodeWithTag(ownThumbnailSwitchTag("Dogs")).assertIsOff().performClick()
        composeTestRule.onNodeWithTag(ownThumbnailSwitchTag("Cats")).performClick()

        assertEquals(listOf("Dogs" to true, "Cats" to false), switched)
    }

    @Test
    fun `a long press selects a pack with a name and a tap opens one`() {
        val toggled = mutableListOf<String>()
        val opened = mutableListOf<String>()
        show(library(favourites, cats), StickerLibraryActions(onTogglePack = { toggled += it }, onOpenPack = { opened += it }))

        // At the row's start: on a narrow screen its middle is the switch.
        composeTestRule.onNodeWithText("Cats").performTouchInput { longClick(centerLeft + Offset(8f, 0f)) }
        composeTestRule.onNodeWithText("Favourites").performTouchInput { longClick(centerLeft + Offset(8f, 0f)) }
        composeTestRule.onNodeWithText("Cats").performTouchInput { click(centerLeft + Offset(8f, 0f)) }

        assertEquals(listOf("Cats"), toggled)
        // Favourites cannot be selected, so a long press on it is a tap.
        assertEquals(listOf("f", "Cats"), opened)
    }

    @Test
    fun `a selection of two packs can be merged under the first pack's name`() {
        var merged: String? = null
        val toggled = mutableListOf<String>()
        show(
            library(dogs, cats, birds, selected = setOf("Dogs", "Cats")),
            StickerLibraryActions(onMergeSelected = { merged = it }, onTogglePack = { toggled += it }),
        )

        composeTestRule.onNodeWithText("2 selected").assertIsDisplayed()
        composeTestRule.onNodeWithContentDescription("Add stickers").assertDoesNotExist()
        // A tap picks while a selection runs.
        composeTestRule.onNodeWithText("Birds").performClick()
        assertEquals(listOf("Birds"), toggled)

        composeTestRule.onNodeWithContentDescription("Merge").performClick()
        // Cats has its own thumbnail, so the list shows it first.
        composeTestRule.onNode(hasSetTextAction()).assertTextContains("Cats")
        composeTestRule.onNodeWithText("Merge").performClick()

        assertEquals("Cats", merged)
    }

    @Test
    fun `a selection of one pack cannot be merged`() {
        show(library(cats, dogs, selected = setOf("Dogs")))

        composeTestRule.onNodeWithText("1 selected").assertIsDisplayed()
        composeTestRule.onNodeWithContentDescription("Merge").assertIsNotEnabled()
    }

    @Test
    fun `deleting a selection asks once and counts each sticker once`() {
        var deleted = 0
        show(library(cats, dogs, selected = setOf("Cats", "Dogs")), StickerLibraryActions(onDeleteSelected = { deleted++ }))

        composeTestRule.onNodeWithContentDescription("Delete").performClick()
        assertEquals(0, deleted)
        // Four entries in two packs, and one sticker is in both.
        composeTestRule.onNodeWithText("Delete 2 packs and take their 3 stickers", substring = true).assertIsDisplayed()
        composeTestRule.onNodeWithText("WhatsApp import", substring = true).assertIsDisplayed()
        composeTestRule.onNodeWithText("Delete").performClick()

        assertEquals(1, deleted)
    }

    @Test
    fun `the second tab is chosen from the tab row and shows no pack list`() {
        var picked: StickerManagerTab? = null
        show(library(cats).copy(tab = StickerManagerTab.ALL_STICKERS), StickerLibraryActions(onSelectTab = { picked = it }))

        composeTestRule.onNodeWithTag(ALL_STICKERS_TAB_TAG).assertExists()
        composeTestRule.onNodeWithText(ROW_GROUP_TITLE).assertDoesNotExist()
        composeTestRule.onNodeWithText("Packs").performClick()

        assertEquals(StickerManagerTab.PACKS, picked)
    }

    @Test
    fun `a drag moves a pack inside its group only`() {
        val packs = listOf(dogs, cats, birds, favourites)

        // Dogs and Birds share the WhatsApp thumbnail. Cats, between them, keeps its place.
        assertEquals(listOf("Cats", "Birds", "Dogs", "f"), packs.movedWithinGroup("Dogs", "Birds")?.map { it.id })
        assertEquals(listOf("Birds", "Dogs", "Cats", "f"), packs.movedWithinGroup("Birds", "Dogs")?.map { it.id })
        assertEquals(listOf("Dogs", "f", "Cats", "Birds"), packs.movedWithinGroup("f", "Cats")?.map { it.id })
        assertNull(packs.movedWithinGroup("Dogs", "Cats"))
        assertNull(packs.movedWithinGroup("Dogs", "header:$GROUPED_GROUP_TITLE"))
    }
}
