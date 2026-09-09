package com.firestream.chat.ui.chat.imageedit

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.assertHasClickAction
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The overlay rail's staging rules, which are the parts a later phase can
 * silently break: what a not-yet-built tool looks like, what a video page
 * offers, and which end of the history the undo/redo buttons disable at.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29], application = android.app.Application::class)
class ImageEditToolbarUiTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    // ── Actions rail ──────────────────────────────────────────────────────────

    @Test
    fun `an unwired editor tool renders but is not clickable`() {
        composeTestRule.setContent {
            MaterialTheme {
                ImageEditActions(isHd = false, onToggleHd = {}, onDownload = {})
            }
        }

        // Phase 1 ships the rail with the three editor entry points dark.
        composeTestRule.onNodeWithContentDescription("Adjust").assertIsNotEnabled()
        composeTestRule.onNodeWithContentDescription("Draw").assertIsNotEnabled()
        composeTestRule.onNodeWithContentDescription("Add stickers or text").assertIsNotEnabled()
        composeTestRule.onNodeWithContentDescription("Save to Downloads").assertIsEnabled()
    }

    @Test
    fun `a wired editor tool becomes clickable and fires`() {
        var adjusts = 0
        composeTestRule.setContent {
            MaterialTheme {
                ImageEditActions(
                    isHd = false,
                    onToggleHd = {},
                    onDownload = {},
                    onAdjust = { adjusts++ },
                )
            }
        }

        composeTestRule.onNodeWithContentDescription("Adjust").assertIsEnabled().performClick()
        assertEquals(1, adjusts)
    }

    @Test
    fun `a video page offers download but neither HD nor the editor tools`() {
        composeTestRule.setContent {
            MaterialTheme {
                ImageEditActions(isHd = null, showEditTools = false, onDownload = {})
            }
        }

        composeTestRule.onNodeWithText("HD").assertDoesNotExist()
        composeTestRule.onNodeWithContentDescription("Adjust").assertDoesNotExist()
        composeTestRule.onNodeWithContentDescription("Draw").assertDoesNotExist()
        composeTestRule.onNodeWithContentDescription("Save to Downloads").assertIsDisplayed()
    }

    @Test
    fun `the HD pill is present for an image and opens the sheet`() {
        var toggles = 0
        composeTestRule.setContent {
            MaterialTheme {
                ImageEditActions(isHd = true, onToggleHd = { toggles++ }, onDownload = {})
            }
        }

        composeTestRule.onNodeWithText("HD").assertHasClickAction().performClick()
        assertEquals(1, toggles)
    }

    // ── History pill ──────────────────────────────────────────────────────────

    @Test
    fun `undo is disabled at the original and redo at the newest step`() {
        composeTestRule.setContent {
            MaterialTheme {
                ImageEditHistory(
                    canUndo = false,
                    canRedo = true,
                    showingOriginal = true,
                    onUndo = {},
                    onRedo = {},
                    onToggleOriginal = {},
                )
            }
        }

        composeTestRule.onNodeWithContentDescription("Undo edit").assertIsNotEnabled()
        composeTestRule.onNodeWithContentDescription("Redo edit").assertIsEnabled()
    }

    @Test
    fun `the original toggle names the direction it will move in`() {
        composeTestRule.setContent {
            MaterialTheme {
                ImageEditHistory(
                    canUndo = true,
                    canRedo = false,
                    showingOriginal = false,
                    onUndo = {},
                    onRedo = {},
                    onToggleOriginal = {},
                )
            }
        }

        // Showing the edited image, so the control offers the original.
        composeTestRule.onNodeWithContentDescription("Show original image").assertIsDisplayed()
        composeTestRule.onNodeWithContentDescription("Redo edit").assertIsNotEnabled()
    }
}
