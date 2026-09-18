package com.firestream.chat.ui.chat

import android.net.Uri
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.assertIsNotFocused
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performTextInput
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Where a keystroke goes once [ImagePreviewScreen] is open.
 *
 * The preview is an overlay inside `ChatScreen`'s composition, so the chat
 * composer it covers stays composed — and, coming back from the gallery picker,
 * still focused with the keyboard over it. The regression this guards: the
 * caption typed for the photos just picked went into the chat composer behind
 * the black overlay, where the user only found it after sending.
 *
 * The composer here stands in for ChatScreen's: same window, focused first, then
 * covered by the preview.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29], application = android.app.Application::class)
class ImagePreviewScreenFocusTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    private var showPreview by mutableStateOf(false)
    private var keyboardVisible by mutableStateOf(false)

    private fun batch(count: Int) = (1..count).map {
        PendingMedia(Uri.parse("content://media/external/images/$it"), "image/jpeg")
    }

    /**
     * Composes the composer, focuses it the way the user does — by typing into
     * it — and leaves the preview to [openPreview].
     */
    private fun setContent(items: List<PendingMedia> = batch(3)) {
        composeTestRule.setContent {
            MaterialTheme {
                Box {
                    var text by remember { mutableStateOf("") }
                    BasicTextField(
                        value = text,
                        onValueChange = { text = it },
                        modifier = Modifier.testTag(COMPOSER),
                    )
                    if (showPreview) {
                        ImagePreviewScreen(
                            items = items,
                            recentEmojis = emptyList(),
                            defaultIsHd = false,
                            onEmojiUsed = {},
                            onSend = {},
                            onDownload = {},
                            onDismiss = {},
                            keyboardVisible = keyboardVisible,
                        )
                    }
                }
            }
        }
        composeTestRule.onNodeWithTag(COMPOSER).performTextInput("half a sentence")
        composeTestRule.onNodeWithTag(COMPOSER).assertIsFocused()
    }

    private fun openPreview() {
        showPreview = true
        composeTestRule.waitForIdle()
    }

    @Test
    fun `opening over a keyboard hands focus to the caption, not the composer behind`() {
        keyboardVisible = true
        setContent()

        openPreview()

        composeTestRule.onNodeWithTag(COMPOSER).assertIsNotFocused()
        composeTestRule.onNodeWithText(CAPTION_HINT).assertIsFocused()
    }

    @Test
    fun `opening with no keyboard still takes focus off the composer behind`() {
        keyboardVisible = false
        setContent()

        openPreview()

        composeTestRule.onNodeWithTag(COMPOSER).assertIsNotFocused()
    }

    @Test
    fun `with no keyboard up the caption is not focused either, so none is summoned`() {
        keyboardVisible = false
        setContent()

        openPreview()

        composeTestRule.onNodeWithText(CAPTION_HINT).assertIsNotFocused()
    }

    @Test
    fun `a keyboard that arrives after the preview is open lands in the caption`() {
        keyboardVisible = false
        setContent()
        openPreview()

        // The IME is re-shown a frame or two after the picker returns, so the
        // moment the preview composes is too early to settle this once and for all.
        keyboardVisible = true
        composeTestRule.waitForIdle()

        composeTestRule.onNodeWithText(CAPTION_HINT).assertIsFocused()
        composeTestRule.onNodeWithTag(COMPOSER).assertIsNotFocused()
    }

    private companion object {
        const val COMPOSER = "chat-composer"
        const val CAPTION_HINT = "Add a caption to this one..."
    }
}
