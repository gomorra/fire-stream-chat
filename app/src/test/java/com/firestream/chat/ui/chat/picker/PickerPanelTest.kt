package com.firestream.chat.ui.chat.picker

import androidx.compose.foundation.layout.height
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.unit.dp
import com.firestream.chat.ui.chat.EmojiHandlerPanel
import com.firestream.chat.ui.chat.EmojiMode
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * What the picker shell owes its hosts.
 *
 * Two halves, and the first is the reason this commit exists. The emoji panel
 * was ~700 lines mounted from three places and the extraction into
 * [PickerPanel] + [EmojiTab] had to change **nothing** at those three
 * (`.claude/plans/image-editor.md` §2.8, §4) — so the first half asserts the
 * one-tab hosts still look and behave exactly as they did: no island, no search
 * button, a backspace only where a text field is being typed into, a
 * quick-reactions strip only where there is a message to react to, and a
 * recents order that holds still.
 *
 * The second half covers the chrome only a multi-tab host ever sees, which the
 * image editor is about to become the first of.
 *
 * What no test here can settle is the half of the move that is *felt*: whether
 * the long-press size drag and its row-sibling fade still respond the way they
 * did under a real finger. That is on the device pass — see
 * `docs/BACKLOG.md` §*Pending on-device verification*.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29], application = android.app.Application::class)
class PickerPanelTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    private val recents = listOf("😀", "😂", "🥰")

    // ── The three pre-existing hosts ─────────────────────────────────────────

    @Test
    fun `a one-tab host renders no island and no delete button`() {
        composeTestRule.setContent {
            MaterialTheme {
                EmojiHandlerPanel(
                    mode = EmojiMode.TEXT_INPUT,
                    recentEmojis = recents,
                    onEmojiSelected = { _, _ -> },
                    modifier = Modifier.height(320.dp),
                )
            }
        }

        composeTestRule.onNodeWithContentDescription("Picker tabs").assertDoesNotExist()
        composeTestRule.onNodeWithContentDescription("Delete selected").assertDoesNotExist()
        // No circular search button either: with no island to hide, the field
        // is simply always expanded, which is what this host always showed.
        composeTestRule.onNodeWithContentDescription("Search").assertDoesNotExist()
        composeTestRule.onNodeWithText("Search emoji…").assertIsDisplayed()
    }

    @Test
    fun `the backspace key belongs to the text-input host alone`() {
        var backspaces = 0
        composeTestRule.setContent {
            MaterialTheme {
                EmojiHandlerPanel(
                    mode = EmojiMode.TEXT_INPUT,
                    recentEmojis = recents,
                    onEmojiSelected = { _, _ -> },
                    onBackspace = { backspaces++ },
                    modifier = Modifier.height(320.dp),
                )
            }
        }

        composeTestRule.onNodeWithContentDescription("Backspace").performClick()

        assertEquals(1, backspaces)
    }

    @Test
    fun `the reaction host shows the quick strip and no backspace`() {
        var picked: String? = null
        composeTestRule.setContent {
            MaterialTheme {
                EmojiHandlerPanel(
                    mode = EmojiMode.REACTION,
                    currentReaction = "❤️",
                    recentEmojis = recents,
                    onEmojiSelected = { emoji, _ -> picked = emoji },
                    modifier = Modifier.height(320.dp),
                )
            }
        }

        composeTestRule.onNodeWithContentDescription("Backspace").assertDoesNotExist()
        composeTestRule.onAllNodesWithText("🙏").onFirst().performClick()

        assertEquals("🙏", picked)
    }

    @Test
    fun `the recents order is frozen for the lifetime of one open panel`() {
        // The bug this guards: taps stream into DataStore, the recents list
        // comes back reordered, and the grid shuffles under the finger that is
        // still on it. Snapshotting once per open is the fix, and it has to
        // survive living one composable further down than it used to.
        var live by mutableStateOf(listOf("😀", "😂"))
        composeTestRule.setContent {
            MaterialTheme {
                EmojiTab(
                    query = "",
                    recentEmojis = live,
                    onSelection = {},
                    modifier = Modifier.height(320.dp),
                )
            }
        }
        composeTestRule.waitForIdle()

        live = listOf("🎉", "🥳")
        composeTestRule.waitForIdle()

        // The newcomers are not in the Recents block. They are not in any
        // category either, so finding them at all would mean the snapshot broke.
        composeTestRule.onNodeWithText("🥳").assertDoesNotExist()
    }

    // ── The chrome a multi-tab host sees ─────────────────────────────────────

    @Test
    fun `a multi-tab host gets an island whose active segment keeps its label`() {
        setUpTwoTabs()

        composeTestRule.onNodeWithContentDescription("Picker tabs").assertIsDisplayed()
        // Icon-only except the active segment (§4: four segments plus the search
        // and delete buttons do not fit a 390 dp row with every label showing).
        composeTestRule.onNodeWithText("Emoji").assertIsDisplayed()
        composeTestRule.onNodeWithText("Stickers").assertDoesNotExist()
    }

    @Test
    fun `tapping a segment switches which tab is showing`() {
        setUpTwoTabs()

        composeTestRule.onNodeWithContentDescription("Stickers").performClick()

        composeTestRule.onNodeWithText("sticker content query=").assertIsDisplayed()
        composeTestRule.onNodeWithText("Stickers").assertIsDisplayed()
    }

    @Test
    fun `a query typed on one tab does not follow the switch to another`() {
        setUpTwoTabs()

        openSearch()
        composeTestRule.onNodeWithContentDescription("Search field").performTextInput("cat")
        composeTestRule.waitForIdle()
        // Collapsing brings the island back so the other tab is reachable.
        composeTestRule.onNodeWithContentDescription("Close search").performClick()
        composeTestRule.onNodeWithContentDescription("Stickers").performClick()
        openSearch()

        composeTestRule.onNodeWithText("Search stickers…").assertIsDisplayed()
        composeTestRule.onNodeWithText("sticker content query=").assertIsDisplayed()
    }

    @Test
    fun `a tab with nothing to search offers no search button and no field`() {
        // 5b shipped Text and Shapes with a search hint each, so the shell drew
        // them a live field that accepted a query neither tab has any list to
        // apply it to — two of the editor's four tabs offering a control that
        // did nothing. A tab is searchable now only if it says what it would
        // search (PickerTab.searchHint), and Shapes says nothing.
        composeTestRule.setContent {
            MaterialTheme {
                PickerPanel(
                    tabs = listOf(PickerTab.EMOJI, PickerTab.SHAPE),
                    modifier = Modifier.height(320.dp),
                ) { tab, query ->
                    Text(
                        when (tab) {
                            PickerTab.EMOJI -> "emoji content"
                            else -> "shape content"
                        } + " query=$query",
                    )
                }
            }
        }

        // Emoji still has one.
        composeTestRule.onNodeWithContentDescription("Search").assertIsDisplayed()

        composeTestRule.onNodeWithContentDescription("Shapes").performClick()
        composeTestRule.waitForIdle()

        composeTestRule.onNodeWithText("shape content query=").assertIsDisplayed()
        composeTestRule.onNodeWithContentDescription("Search").assertDoesNotExist()
        composeTestRule.onNodeWithContentDescription("Search field").assertDoesNotExist()
        // The island is still reachable — losing the field must not cost the
        // way back to the other tabs.
        composeTestRule.onNodeWithContentDescription("Picker tabs").assertIsDisplayed()
    }

    @Test
    fun `expanding search hides the island and the field's close brings it back`() {
        setUpTwoTabs()

        openSearch()
        composeTestRule.onNodeWithContentDescription("Picker tabs").assertDoesNotExist()

        composeTestRule.onNodeWithContentDescription("Close search").performClick()
        composeTestRule.onNodeWithContentDescription("Picker tabs").assertIsDisplayed()
    }

    @Test
    fun `the delete button appears only while the host has a selection`() {
        var selected by mutableStateOf(false)
        var deletes = 0
        composeTestRule.setContent {
            MaterialTheme {
                PickerPanel(
                    tabs = listOf(PickerTab.EMOJI, PickerTab.SHAPE),
                    modifier = Modifier.height(320.dp),
                    onDelete = if (selected) ({ deletes++ }) else null,
                ) { _, _ -> Text("content") }
            }
        }

        composeTestRule.onNodeWithContentDescription("Delete selected").assertDoesNotExist()

        selected = true
        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithContentDescription("Delete selected").performClick()

        assertEquals(1, deletes)
    }

    /**
     * Two tabs that can *both* be searched, which is what the chrome tests
     * below are about. Shapes would not do here: it has no list a query could
     * shorten and therefore deliberately renders no field at all — see
     * `a tab with nothing to search offers no search button and no field`.
     */
    private fun setUpTwoTabs() {
        composeTestRule.setContent {
            MaterialTheme {
                PickerPanel(
                    tabs = listOf(PickerTab.EMOJI, PickerTab.STICKER),
                    modifier = Modifier.height(320.dp),
                ) { tab, query ->
                    Text(
                        when (tab) {
                            PickerTab.EMOJI -> "emoji content"
                            else -> "sticker content"
                        } + " query=$query",
                    )
                }
            }
        }
    }

    private fun openSearch() {
        composeTestRule.onNodeWithContentDescription("Search").performClick()
        composeTestRule.waitForIdle()
    }
}
