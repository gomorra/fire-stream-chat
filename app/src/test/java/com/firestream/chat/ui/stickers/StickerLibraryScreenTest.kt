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
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.isDialog
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import com.firestream.chat.domain.model.StickerAutoNaming
import com.firestream.chat.domain.model.StickerPack
import com.firestream.chat.domain.model.StickerPackKind
import com.firestream.chat.domain.util.StickerNameFilter
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

    private fun allStickers(
        vararg packs: StickerPack,
        selected: Set<StickerEntry> = emptySet(),
        query: String = "",
    ) = StickerLibraryUiState(
        isLoading = false,
        packs = packs.toList(),
        tab = StickerManagerTab.ALL_STICKERS,
        selectedStickers = selected,
        query = query,
    )

    @Test
    fun `the grid of every sticker titles each pack with its count and shows a shared sticker under both`() {
        show(allStickers(cats, dogs, birds))

        composeTestRule.onNodeWithText("Cats · 2").assertIsDisplayed()
        composeTestRule.onNodeWithText("Dogs · 2").assertIsDisplayed()
        // An empty pack has no title.
        composeTestRule.onNodeWithText("Birds", substring = true).assertDoesNotExist()
        composeTestRule.onNodeWithTag(stickerEntryTag(StickerEntry("Cats", "shared"))).assertExists()
        composeTestRule.onNodeWithTag(stickerEntryTag(StickerEntry("Dogs", "shared"))).assertExists()
    }

    @Test
    fun `a long press in the grid of every sticker picks an entry, and a tap picks only during a selection`() {
        val toggled = mutableListOf<StickerEntry>()
        show(allStickers(cats, dogs), StickerLibraryActions(onToggleSticker = { toggled += it }))

        composeTestRule.onNodeWithTag(stickerEntryTag(StickerEntry("Cats", "c1"))).performClick()
        assertEquals(emptyList<StickerEntry>(), toggled)
        composeTestRule.onNodeWithTag(stickerEntryTag(StickerEntry("Dogs", "shared"))).performTouchInput { longClick() }

        assertEquals(listOf(StickerEntry("Dogs", "shared")), toggled)
    }

    @Test
    fun `the search field hands its text over, and a search without a hit says so`() {
        val typed = mutableListOf<String>()
        show(allStickers(cats, query = "fox"), StickerLibraryActions(onSetQuery = { typed += it }))

        composeTestRule.onNodeWithText("No stickers for “fox”").assertIsDisplayed()
        composeTestRule.onNodeWithText("Cats · 2").assertDoesNotExist()
        composeTestRule.onNodeWithContentDescription("Clear search").performClick()
        composeTestRule.onNodeWithTag(STICKER_SEARCH_TAG).performTextInput("x")

        assertEquals("", typed.first())
        assertEquals(2, typed.size)
    }

    @Test
    fun `a selection across packs offers the four actions and no removal from one pack`() {
        val done = mutableListOf<String>()
        val selection = setOf(StickerEntry("Cats", "shared"), StickerEntry("Dogs", "shared"), StickerEntry("Dogs", "d1"))
        show(
            allStickers(cats, dogs, birds, favourites, selected = selection),
            StickerLibraryActions(
                onMoveSelectedTo = { done += "move:$it" },
                onNewPackFromSelected = { done += "new:$it" },
                onFavouriteSelected = { done += "favourite" },
                onDeleteSelectedStickers = { done += "delete" },
                onToggleSticker = { done += "toggle:${it.stickerId}" },
            ),
        )

        composeTestRule.onNodeWithText("3 selected").assertIsDisplayed()
        composeTestRule.onNodeWithContentDescription("Add stickers").assertDoesNotExist()
        // A tap picks while a selection runs.
        composeTestRule.onNodeWithTag(stickerEntryTag(StickerEntry("Cats", "c1"))).performClick()

        composeTestRule.onNodeWithContentDescription("Move to pack").performClick()
        // Favourites is no target. The selection comes from two packs, so both of them are.
        composeTestRule.onNodeWithText("Favourites").assertDoesNotExist()
        composeTestRule.onNodeWithText("Birds").performClick()

        composeTestRule.onNodeWithContentDescription("More for the selection").performClick()
        composeTestRule.onNodeWithText("Remove from pack").assertDoesNotExist()
        // Names and emojis are edited one sticker at a time.
        composeTestRule.onNodeWithText(EDIT_STICKER_LABEL).assertDoesNotExist()
        composeTestRule.onNodeWithText("Add to favourites").performClick()

        composeTestRule.onNodeWithContentDescription("More for the selection").performClick()
        composeTestRule.onNodeWithText("New pack from these").performClick()
        composeTestRule.onNodeWithText("Create").assertIsNotEnabled()
        composeTestRule.onNode(hasSetTextAction() and hasAnyAncestor(isDialog())).performTextInput("Best")
        composeTestRule.onNodeWithText("Create").performClick()

        composeTestRule.onNodeWithContentDescription("Delete from library").performClick()
        assertEquals(false, "delete" in done)
        // Three entries, and one sticker is picked in two packs.
        composeTestRule.onNodeWithText("Delete 2 stickers from your library?", substring = true).assertIsDisplayed()
        composeTestRule.onNodeWithText("WhatsApp import", substring = true).assertIsDisplayed()
        composeTestRule.onNodeWithText("Delete").performClick()

        assertEquals(listOf("toggle:c1", "move:Birds", "favourite", "new:Best", "delete"), done)
    }

    @Test
    fun `a selection of one sticker offers the edit of its names and emojis`() {
        var edits = 0
        // The same sticker picked in two packs is one sticker.
        val selection = setOf(StickerEntry("Cats", "shared"), StickerEntry("Dogs", "shared"))
        show(allStickers(cats, dogs, selected = selection), StickerLibraryActions(onEditSelectedSticker = { edits++ }))

        composeTestRule.onNodeWithContentDescription("More for the selection").performClick()
        composeTestRule.onNodeWithText(EDIT_STICKER_LABEL).performClick()

        assertEquals(1, edits)
    }

    // --- Automatic names ---

    private val naming = StickerAutoNaming(isSupported = true)

    @Test
    fun `the naming switch asks before it goes on, and only the notice's button switches it on`() {
        val set = mutableListOf<Boolean>()
        show(library(cats).copy(autoNaming = naming), StickerLibraryActions(onSetAutoNaming = { set += it }))

        composeTestRule.onNodeWithText(AUTO_NAMING_TITLE).assertIsDisplayed()
        composeTestRule.onNodeWithTag(AUTO_NAMING_SWITCH_TAG).assertIsOff()
        composeTestRule.onNodeWithText(AUTO_NAMING_ACCEPT_LABEL).assertDoesNotExist()

        composeTestRule.onNodeWithTag(AUTO_NAMING_SWITCH_TAG).performClick()
        // The tap alone switches nothing on. The notice says what happens first.
        assertEquals(emptyList<Boolean>(), set)
        composeTestRule.onNodeWithText("read by a computer", substring = true).assertExists()
        composeTestRule.onNodeWithText("made in the app are left out", substring = true).assertExists()
        composeTestRule.onNodeWithText("Not now").performClick()
        composeTestRule.onNodeWithText(AUTO_NAMING_ACCEPT_LABEL).assertDoesNotExist()
        assertEquals(emptyList<Boolean>(), set)

        composeTestRule.onNodeWithTag(AUTO_NAMING_SWITCH_TAG).performClick()
        composeTestRule.onNodeWithText(AUTO_NAMING_ACCEPT_LABEL).performClick()

        assertEquals(listOf(true), set)
    }

    @Test
    fun `switching naming off needs no question, and a refusal is said without switching anything off`() {
        val set = mutableListOf<Boolean>()
        show(
            library(cats).copy(autoNaming = StickerAutoNaming(isSupported = true, isEnabled = true, isRefused = true)),
            StickerLibraryActions(onSetAutoNaming = { set += it }),
        )

        composeTestRule.onNodeWithText(AUTO_NAMING_REFUSED).assertIsDisplayed()
        composeTestRule.onNodeWithTag(AUTO_NAMING_SWITCH_TAG).assertIsOn()
        composeTestRule.onNodeWithTag(AUTO_NAMING_SWITCH_TAG).performClick()

        assertEquals(listOf(false), set)
        composeTestRule.onNodeWithText(AUTO_NAMING_ACCEPT_LABEL).assertDoesNotExist()
    }

    @Test
    fun `a backend without a naming service offers nothing about automatic names`() {
        show(library(cats))

        composeTestRule.onNodeWithText(AUTO_NAMING_TITLE).assertDoesNotExist()
        composeTestRule.onNodeWithContentDescription("Options for Cats").performClick()
        composeTestRule.onNodeWithText("Rename").assertIsDisplayed()
        composeTestRule.onNodeWithText(PACK_AUTO_NAMING_LABEL).assertDoesNotExist()
    }

    @Test
    fun `a pack's menu leaves the pack out of naming and lets it in again`() {
        val set = mutableListOf<Pair<String, Boolean>>()
        show(
            library(cats, dogs.copy(autoNameAllowed = false)).copy(autoNaming = naming),
            StickerLibraryActions(onSetPackAutoNaming = { id, allowed -> set += id to allowed }),
        )

        composeTestRule.onNodeWithContentDescription("Options for Cats").performClick()
        composeTestRule.onNodeWithContentDescription("On").assertExists()
        composeTestRule.onNodeWithText(PACK_AUTO_NAMING_LABEL).performClick()
        composeTestRule.onNodeWithContentDescription("Options for Dogs").performClick()
        composeTestRule.onNodeWithContentDescription("On").assertDoesNotExist()
        composeTestRule.onNodeWithText(PACK_AUTO_NAMING_LABEL).performClick()

        assertEquals(listOf("Cats" to false, "Dogs" to true), set)
    }

    @Test
    fun `a sticker selection can be named now while naming is on, and left out of naming`() {
        val done = mutableListOf<String>()
        show(
            allStickers(cats, selected = setOf(StickerEntry("Cats", "c1")))
                .copy(autoNaming = StickerAutoNaming(isSupported = true, isEnabled = true)),
            StickerLibraryActions(onNameSelectedNow = { done += "name" }, onStopNamingSelected = { done += "stop" }),
        )

        composeTestRule.onNodeWithContentDescription("More for the selection").performClick()
        composeTestRule.onNodeWithText(NAME_NOW_LABEL).performClick()
        composeTestRule.onNodeWithContentDescription("More for the selection").performClick()
        composeTestRule.onNodeWithText(STOP_NAMING_LABEL).performClick()

        assertEquals(listOf("name", "stop"), done)
    }

    @Test
    fun `with naming switched off a selection can be left out, and cannot be named now`() {
        show(allStickers(cats, selected = setOf(StickerEntry("Cats", "c1"))).copy(autoNaming = naming))

        composeTestRule.onNodeWithContentDescription("More for the selection").performClick()

        // Asking sends a picture. Without the switch nothing may ask.
        composeTestRule.onNodeWithText(NAME_NOW_LABEL).assertDoesNotExist()
        composeTestRule.onNodeWithText(STOP_NAMING_LABEL).assertExists()
    }

    @Test
    fun `the name filters narrow the grid, and a second tap on a chip shows every sticker again`() {
        val set = mutableListOf<StickerNameFilter>()
        val zoo = testStickerPack("Zoo", StickerPackKind.USER, testSticker("typed").copy(names = listOf("Cat")), testSticker("nameless"))
        show(
            allStickers(zoo).copy(autoNaming = naming, nameFilter = StickerNameFilter.NO_NAME),
            StickerLibraryActions(onSetNameFilter = { set += it }),
        )

        composeTestRule.onNodeWithText("Zoo · 1").assertIsDisplayed()
        composeTestRule.onNodeWithTag(stickerEntryTag(StickerEntry("Zoo", "typed"))).assertDoesNotExist()
        composeTestRule.onNodeWithText(NO_NAME_FILTER_LABEL).performClick()
        composeTestRule.onNodeWithText(AUTO_NAMED_FILTER_LABEL).performClick()

        assertEquals(listOf(StickerNameFilter.ALL, StickerNameFilter.NAMED_AUTOMATICALLY), set)
    }

    @Test
    fun `a filter that leaves nothing says so, and the automatic filter is not offered without a naming service`() {
        show(allStickers(cats).copy(nameFilter = StickerNameFilter.NAMED_AUTOMATICALLY))

        composeTestRule.onNodeWithText("No sticker is named automatically yet").assertIsDisplayed()
        composeTestRule.onNodeWithText(AUTO_NAMED_FILTER_LABEL).assertDoesNotExist()
        composeTestRule.onNodeWithText(NO_NAME_FILTER_LABEL).assertIsDisplayed()
    }

    @Test
    fun `the search field says what it finds by`() {
        show(allStickers(cats))

        composeTestRule.onNodeWithText("Search by name, emoji or pack").assertIsDisplayed()
    }

    @Test
    fun `a pack's own grid has the same bar, and can take a sticker out of that pack`() {
        val done = mutableListOf<String>()
        val toggled = mutableListOf<StickerEntry>()
        show(
            library(cats, dogs).copy(openPackId = "Cats", selectedStickers = setOf(StickerEntry("Cats", "c1"))),
            StickerLibraryActions(onRemoveSelected = { done += "remove" }, onToggleSticker = { toggled += it }),
        )

        composeTestRule.onNodeWithText("1 selected").assertIsDisplayed()
        composeTestRule.onNodeWithContentDescription("Delete from library").assertExists()
        composeTestRule.onNodeWithTag(stickerCellTag("shared")).performClick()
        composeTestRule.onNodeWithContentDescription("Move to pack").performClick()
        // The pack the selection is in is no target.
        composeTestRule.onNodeWithText("Dogs").assertIsDisplayed()
        composeTestRule.onNodeWithText("Cats").assertDoesNotExist()
        composeTestRule.onNodeWithText("Cancel").performClick()
        composeTestRule.onNodeWithContentDescription("More for the selection").performClick()
        composeTestRule.onNodeWithText("Remove from pack").performClick()

        assertEquals(listOf("remove"), done)
        assertEquals(listOf(StickerEntry("Cats", "shared")), toggled)
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
