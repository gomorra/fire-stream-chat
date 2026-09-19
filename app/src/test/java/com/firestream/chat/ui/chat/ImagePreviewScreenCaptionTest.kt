package com.firestream.chat.ui.chat

import android.net.Uri
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTextInputSelection
import androidx.compose.ui.text.TextRange
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The caption bar's emoji panel writes at the caret.
 *
 * The bug: every pick was appended to the end of the caption, so an emoji could
 * never be put between two words, and the panel's backspace key ate the last
 * character rather than the one before the caret. The logic itself is covered
 * by [ComposerEditTest]; what this test guards is the wiring — the caret state
 * the panel reads has to be the one the text field writes.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [31], application = android.app.Application::class)
class ImagePreviewScreenCaptionTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    private val pick = PendingMedia(Uri.parse("content://media/external/images/1"), "image/jpeg")

    private fun setContent(onSend: (List<PendingMedia>) -> Unit) {
        composeTestRule.setContent {
            MaterialTheme {
                ImagePreviewScreen(
                    items = listOf(pick),
                    recentEmojis = listOf("😀"),
                    defaultIsHd = false,
                    onEmojiUsed = {},
                    onSend = onSend,
                    onDownload = {},
                    onDismiss = {},
                )
            }
        }
    }

    private fun typeCaptionAndPlaceCaret(caption: String, caret: Int) {
        composeTestRule.onNode(hasSetTextAction()).performTextInput(caption)
        composeTestRule.onNode(hasSetTextAction()).performTextInputSelection(TextRange(caret))
    }

    private fun openEmojiPanel() {
        composeTestRule.onNodeWithContentDescription("Emoji").performClick()
        composeTestRule.waitForIdle()
    }

    @Test
    fun `an emoji picked with the caret between two words lands there`() {
        var sent: List<PendingMedia>? = null
        setContent { sent = it }

        typeCaptionAndPlaceCaret("hello world", caret = 6)
        openEmojiPanel()
        composeTestRule.onAllNodesWithText("😀").onFirst().performClick()
        composeTestRule.onNodeWithContentDescription("Send").performClick()

        assertEquals("hello 😀world", sent?.single()?.caption)
    }

    @Test
    fun `the panel's backspace deletes in front of the caret, not at the end`() {
        var sent: List<PendingMedia>? = null
        setContent { sent = it }

        typeCaptionAndPlaceCaret("hello world", caret = 6)
        openEmojiPanel()
        composeTestRule.onNodeWithContentDescription("Backspace").performClick()
        composeTestRule.onNodeWithContentDescription("Send").performClick()

        assertEquals("helloworld", sent?.single()?.caption)
    }
}
