package com.firestream.chat.ui.chat

import android.net.Uri
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Multi-image behaviour of [ImagePreviewScreen]: the batch the user reviews is
 * the batch that gets sent, with per-item captions and removals applied.
 *
 * Composing it at all is also the VerifyError guard — Robolectric's class loader
 * runs the same bytecode verification ART does on first composition, which is
 * what caught the MessageBubble crash (`00b15da`).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29], application = android.app.Application::class)
class ImagePreviewScreenMultiTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    private fun batch(count: Int) = (1..count).map {
        PendingMedia(Uri.parse("content://media/external/images/$it"), "image/jpeg")
    }

    private fun setContent(
        items: List<PendingMedia>,
        defaultIsHd: Boolean = false,
        onSend: (List<PendingMedia>) -> Unit = {},
        onDownload: (PendingMedia) -> Unit = {},
        onDismiss: () -> Unit = {},
    ) {
        composeTestRule.setContent {
            MaterialTheme {
                ImagePreviewScreen(
                    items = items,
                    recentEmojis = emptyList(),
                    defaultIsHd = defaultIsHd,
                    onEmojiUsed = {},
                    onSend = onSend,
                    onDownload = onDownload,
                    onDismiss = onDismiss,
                )
            }
        }
    }

    @Test
    fun `a batch shows a page counter and a single pick does not`() {
        setContent(batch(3))
        composeTestRule.onNodeWithText("1 / 3").assertIsDisplayed()
    }

    @Test
    fun `sending hands back every item in the batch`() {
        var sent: List<PendingMedia>? = null
        setContent(batch(4), onSend = { sent = it })

        composeTestRule.onNodeWithContentDescription("Send 4 items").performClick()

        assertEquals(4, sent?.size)
    }

    @Test
    fun `the caption is written onto the item being viewed, not the whole batch`() {
        var sent: List<PendingMedia>? = null
        setContent(batch(3), onSend = { sent = it })

        composeTestRule.onNodeWithText("Add a caption to this one...").performTextInput("first only")
        composeTestRule.onNodeWithContentDescription("Send 3 items").performClick()

        assertEquals("first only", sent?.get(0)?.caption)
        assertEquals("", sent?.get(1)?.caption)
        assertEquals("", sent?.get(2)?.caption)
    }

    @Test
    fun `removing a thumbnail drops just that item`() {
        var sent: List<PendingMedia>? = null
        val items = batch(3)
        setContent(items, onSend = { sent = it })

        composeTestRule.onNodeWithContentDescription("Remove item 2").performClick()
        composeTestRule.onNodeWithText("1 / 2").assertIsDisplayed()
        composeTestRule.onNodeWithContentDescription("Send 2 items").performClick()

        assertEquals(listOf(items[0].uri, items[2].uri), sent?.map { it.uri })
    }

    @Test
    fun `removing the last remaining item dismisses instead of showing an empty screen`() {
        var dismissed = false
        setContent(batch(2), onDismiss = { dismissed = true })

        composeTestRule.onNodeWithContentDescription("Remove item 2").performClick()
        // Down to one item: the strip is gone, so cancel out of the last one.
        composeTestRule.onNodeWithContentDescription("Back").performClick()

        assert(dismissed)
    }
}
