package com.firestream.chat.ui.chat

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.performClick
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The fullscreen viewers' top-right controls: every granted action is on
 * screen from the start, side by side with Close, and each action is opt-in
 * per host. The chevron-folded tray that briefly hid them (`5406ef3c`) is gone
 * — the first test pins that it stays gone.
 *
 * No image is loaded — a null URL renders the viewer's error state, which is
 * all the controls need underneath them.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29], application = android.app.Application::class)
class FullscreenOverlayControlsTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    @Test
    fun `there is no chevron to unfold`() {
        composeTestRule.setContent {
            FullscreenImageViewer(imageUrl = null, onDismiss = {}, onSaveToDownloads = {}, onEdit = {})
        }

        composeTestRule.onNodeWithContentDescription("More actions").assertDoesNotExist()
        composeTestRule.onNodeWithContentDescription("Hide actions").assertDoesNotExist()
    }

    @Test
    fun `granted actions are displayed immediately, beside close`() {
        composeTestRule.setContent {
            FullscreenImageViewer(imageUrl = null, onDismiss = {}, onSaveToDownloads = {}, onEdit = {})
        }

        composeTestRule.onNodeWithContentDescription("Edit").assertIsDisplayed()
        composeTestRule.onNodeWithContentDescription("Save to Downloads").assertIsDisplayed()
        composeTestRule.onNodeWithContentDescription("Close").assertIsDisplayed()
    }

    @Test
    fun `a host that grants only save gets no edit button`() {
        composeTestRule.setContent {
            FullscreenImageViewer(imageUrl = null, onDismiss = {}, onSaveToDownloads = {})
        }

        composeTestRule.onNodeWithContentDescription("Save to Downloads").assertIsDisplayed()
        composeTestRule.onNodeWithContentDescription("Edit").assertDoesNotExist()
    }

    @Test
    fun `a host that grants nothing shows only close`() {
        composeTestRule.setContent {
            FullscreenImageViewer(imageUrl = null, onDismiss = {})
        }

        composeTestRule.onNodeWithContentDescription("Close").assertIsDisplayed()
        composeTestRule.onNodeWithContentDescription("Edit").assertDoesNotExist()
        composeTestRule.onNodeWithContentDescription("Save to Downloads").assertDoesNotExist()
    }

    @Test
    fun `edit in the gallery is handed the photo on screen, not the first one`() {
        val items = listOf(
            FullscreenMediaItem(imageUrl = null, messageId = "first"),
            FullscreenMediaItem(imageUrl = null, messageId = "second"),
        )
        var edited: FullscreenMediaItem? = null
        composeTestRule.setContent {
            FullscreenImagePager(
                items = items,
                initialIndex = 1,
                onDismiss = {},
                onEdit = { edited = it },
            )
        }

        composeTestRule.onNodeWithContentDescription("Edit").performClick()

        assertEquals("second", edited?.messageId)
    }
}
