package com.firestream.chat.ui.chat

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import com.firestream.chat.domain.model.Message
import com.firestream.chat.domain.model.MessageStatus
import com.firestream.chat.domain.model.MessageType
import com.firestream.chat.test.TestData
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The type dispatch of [MessageBubble] for `STICKER` and `GIF`: which branch
 * draws the message, what a tap does, and what the long-press menu and a reply
 * preview say about it.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [31], application = android.app.Application::class)
class MessageBubbleStickerGifTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    private val emptyCallbacks = MessageBubbleCallbacks(
        onDelete = null,
        onEdit = null,
        onReply = {},
        onReaction = {},
        onForward = {},
        onInfo = null,
    )

    private val sticker = TestData.message(id = "s1", senderId = "uid2", content = "😀", type = MessageType.STICKER)
        .copy(mediaUrl = "https://cdn.example.com/stickers/abc.webp", stickerId = "abc")

    private val gif = TestData.message(id = "g1", senderId = "uid2", content = "", type = MessageType.GIF)
        .copy(mediaUrl = "https://cdn.example.com/g1.gif", mimeType = "image/gif")

    private fun show(
        message: Message,
        callbacks: MessageBubbleCallbacks = emptyCallbacks,
        replyTo: Message? = null,
    ) {
        composeTestRule.setContent {
            MaterialTheme {
                MessageBubble(
                    message = message,
                    isOwnMessage = false,
                    replyToMessage = replyTo,
                    linkPreview = null,
                    currentUserId = "uid1",
                    callbacks = callbacks,
                )
            }
        }
    }

    @Test
    fun `a sticker draws the sticker and not its emoji as text`() {
        show(sticker)

        composeTestRule.onNodeWithContentDescription("😀 Sticker").assertIsDisplayed()
        composeTestRule.onNodeWithText("😀").assertDoesNotExist()
    }

    @Test
    fun `a tap on a sticker calls onStickerClick and opens no image viewer`() {
        var stickerTaps = 0
        var imageTaps = 0
        show(sticker, emptyCallbacks.copy(onStickerClick = { stickerTaps++ }, onImageClick = { imageTaps++ }))

        composeTestRule.onNodeWithContentDescription("😀 Sticker").performClick()

        assertEquals(1, stickerTaps)
        assertEquals(0, imageTaps)
    }

    @Test
    fun `a sticker's long-press menu offers no copy`() {
        show(sticker)

        composeTestRule.onNodeWithContentDescription("😀 Sticker").performTouchInput { longClick() }

        composeTestRule.onNodeWithText("Forward").assertExists()
        composeTestRule.onNodeWithText("Copy text").assertDoesNotExist()
    }

    @Test
    fun `a sticker with neither a file nor a url says it is unavailable`() {
        show(sticker.copy(mediaUrl = null))

        composeTestRule.onNodeWithContentDescription("Sticker unavailable").assertIsDisplayed()
    }

    @Test
    fun `a deleted sticker is the tombstone bubble`() {
        show(sticker.copy(deletedAt = 1_700_000_000_000L))

        composeTestRule.onNodeWithText("This message was deleted").assertIsDisplayed()
        composeTestRule.onNodeWithContentDescription("😀 Sticker").assertDoesNotExist()
    }

    @Test
    fun `a GIF draws the picture with a badge and its caption`() {
        show(gif.copy(content = "look at this"))

        composeTestRule.onNodeWithContentDescription("GIF").assertIsDisplayed()
        composeTestRule.onNodeWithText("GIF").assertIsDisplayed()
        composeTestRule.onNodeWithText("look at this").assertIsDisplayed()
    }

    @Test
    fun `a GIF without dimensions still has a shape`() {
        show(gif.copy(mediaWidth = null, mediaHeight = null))

        composeTestRule.onNodeWithContentDescription("GIF").assertIsDisplayed()
    }

    @Test
    fun `a tap on a GIF does not open the image viewer`() {
        var opened = false
        show(gif, emptyCallbacks.copy(onImageClick = { opened = true }))

        composeTestRule.onNodeWithContentDescription("GIF").performClick()

        assertFalse(opened)
    }

    @Test
    fun `a tap on a photo still opens the image viewer`() {
        var opened = false
        show(
            gif.copy(type = MessageType.IMAGE, mediaUrl = "https://cdn.example.com/p.jpg"),
            emptyCallbacks.copy(onImageClick = { opened = true }),
        )

        composeTestRule.onNodeWithContentDescription("Image").performClick()

        assertTrue(opened)
        composeTestRule.onNodeWithText("GIF").assertDoesNotExist()
    }

    @Test
    fun `a reply to a sticker is labelled as a sticker`() {
        show(
            TestData.message(id = "m2", senderId = "uid2", content = "ha", status = MessageStatus.SENT),
            replyTo = sticker,
        )

        composeTestRule.onNodeWithText("😀 Sticker").assertIsDisplayed()
    }

    @Test
    fun `a reply to a GIF without a caption is labelled GIF`() {
        show(
            TestData.message(id = "m2", senderId = "uid2", content = "ha", status = MessageStatus.SENT),
            replyTo = gif,
        )

        composeTestRule.onNodeWithText("GIF").assertIsDisplayed()
    }

    @Test
    fun `the still preview types are the photo, the sticker and the GIF`() {
        assertEquals(
            setOf(MessageType.IMAGE, MessageType.STICKER, MessageType.GIF),
            MessageType.entries.filter { it.hasStillPreview }.toSet(),
        )
    }
}
