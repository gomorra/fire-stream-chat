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
 * The fullscreen viewers' top-right controls: Close always on screen, every
 * other action folded behind a chevron, and each action opt-in per host.
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
    fun `a viewer with no actions shows close and no chevron`() {
        composeTestRule.setContent {
            FullscreenImageViewer(imageUrl = null, onDismiss = {})
        }

        composeTestRule.onNodeWithContentDescription("Close").assertIsDisplayed()
        composeTestRule.onNodeWithContentDescription("More actions").assertDoesNotExist()
    }

    @Test
    fun `actions start folded behind the chevron`() {
        composeTestRule.setContent {
            FullscreenImageViewer(imageUrl = null, onDismiss = {}, onSaveToDownloads = {}, onEdit = {})
        }

        composeTestRule.onNodeWithContentDescription("Close").assertIsDisplayed()
        composeTestRule.onNodeWithContentDescription("More actions").assertIsDisplayed()
        composeTestRule.onNodeWithContentDescription("Edit").assertDoesNotExist()
        composeTestRule.onNodeWithContentDescription("Save to Downloads").assertDoesNotExist()
    }

    @Test
    fun `the chevron opens the tray and closes it again`() {
        composeTestRule.setContent {
            FullscreenImageViewer(imageUrl = null, onDismiss = {}, onSaveToDownloads = {}, onEdit = {})
        }

        composeTestRule.onNodeWithContentDescription("More actions").performClick()
        composeTestRule.onNodeWithContentDescription("Edit").assertIsDisplayed()
        composeTestRule.onNodeWithContentDescription("Save to Downloads").assertIsDisplayed()

        composeTestRule.onNodeWithContentDescription("Hide actions").performClick()
        composeTestRule.onNodeWithContentDescription("Edit").assertDoesNotExist()
        composeTestRule.onNodeWithContentDescription("More actions").assertIsDisplayed()
    }

    @Test
    fun `a host that grants only save gets no edit button`() {
        composeTestRule.setContent {
            FullscreenImageViewer(imageUrl = null, onDismiss = {}, onSaveToDownloads = {})
        }

        composeTestRule.onNodeWithContentDescription("More actions").performClick()
        composeTestRule.onNodeWithContentDescription("Save to Downloads").assertIsDisplayed()
        composeTestRule.onNodeWithContentDescription("Edit").assertDoesNotExist()
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

        composeTestRule.onNodeWithContentDescription("More actions").performClick()
        composeTestRule.onNodeWithContentDescription("Edit").performClick()

        assertEquals("second", edited?.messageId)
    }
}
