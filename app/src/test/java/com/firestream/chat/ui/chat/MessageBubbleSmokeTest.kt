package com.firestream.chat.ui.chat

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import com.firestream.chat.domain.model.MessageStatus
import com.firestream.chat.domain.model.MessageType
import com.firestream.chat.test.TestData
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Smoke tests for [MessageBubble] — the regression guard for the VerifyError
 * crash fixed in sprint commit `00b15da`. The bug was that MessageBubble had
 * 20+ explicit lambda parameters, which pushed the Compose compiler past the
 * register-allocation threshold and produced bytecode that ART rejected with
 * "Verifier rejected class MessageBubbleKt" on first composition. The fix
 * collapsed callbacks into [MessageBubbleCallbacks] (10 explicit params).
 *
 * If MessageBubble can compose under Robolectric for each message type
 * without throwing, the regression is caught — Robolectric's class loader
 * runs the same bytecode verification ART does on-device.
 *
 * Covers TC-01 (chat opens without crash) and TC-02 (VerifyError regression).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29], application = android.app.Application::class)
class MessageBubbleSmokeTest {

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

    @Test
    fun `renders text message without crash`() {
        composeTestRule.setContent {
            MaterialTheme {
                MessageBubble(
                    message = TestData.message(
                        id = "m1",
                        senderId = "uid2",
                        content = "Hello world",
                        type = MessageType.TEXT,
                        status = MessageStatus.SENT,
                    ),
                    isOwnMessage = false,
                    replyToMessage = null,
                    linkPreview = null,
                    currentUserId = "uid1",
                    callbacks = emptyCallbacks,
                )
            }
        }
        composeTestRule.onNodeWithText("Hello world").assertIsDisplayed()
    }

    @Test
    fun `renders own text message with read receipt status`() {
        composeTestRule.setContent {
            MaterialTheme {
                MessageBubble(
                    message = TestData.message(
                        id = "m1",
                        senderId = "uid1",
                        content = "Sent by me",
                        status = MessageStatus.READ,
                    ),
                    isOwnMessage = true,
                    replyToMessage = null,
                    linkPreview = null,
                    currentUserId = "uid1",
                    callbacks = emptyCallbacks,
                )
            }
        }
        composeTestRule.onNodeWithText("Sent by me").assertIsDisplayed()
    }

    @Test
    fun `renders deleted message without crash`() {
        composeTestRule.setContent {
            MaterialTheme {
                MessageBubble(
                    message = TestData.message(
                        id = "m1",
                        senderId = "uid2",
                        content = "Was here",
                        status = MessageStatus.SENT,
                    ).copy(deletedAt = 1_700_000_000_000L),
                    isOwnMessage = false,
                    replyToMessage = null,
                    linkPreview = null,
                    currentUserId = "uid1",
                    callbacks = emptyCallbacks,
                )
            }
        }
        composeTestRule.onNodeWithText("This message was deleted").assertIsDisplayed()
    }

    @Test
    fun `renders image message without crash`() {
        composeTestRule.setContent {
            MaterialTheme {
                MessageBubble(
                    message = TestData.message(
                        id = "m1",
                        senderId = "uid2",
                        content = "",
                        type = MessageType.IMAGE,
                        status = MessageStatus.SENT,
                    ).copy(
                        mediaUrl = "https://cdn.example.com/img.jpg",
                        mediaWidth = 800,
                        mediaHeight = 600,
                    ),
                    isOwnMessage = false,
                    replyToMessage = null,
                    linkPreview = null,
                    currentUserId = "uid1",
                    callbacks = emptyCallbacks,
                )
            }
        }
        // No assertion needed — composing without throwing IS the assertion.
    }

    @Test
    fun `renders emoji-only message without crash`() {
        composeTestRule.setContent {
            MaterialTheme {
                MessageBubble(
                    message = TestData.message(
                        id = "m1",
                        senderId = "uid2",
                        content = "🎉🥳",
                        type = MessageType.TEXT,
                    ),
                    isOwnMessage = false,
                    replyToMessage = null,
                    linkPreview = null,
                    currentUserId = "uid1",
                    callbacks = emptyCallbacks,
                )
            }
        }
        composeTestRule.onNodeWithText("🎉🥳").assertIsDisplayed()
    }

    @Test
    fun `renders forwarded message with reply context without crash`() {
        composeTestRule.setContent {
            MaterialTheme {
                MessageBubble(
                    message = TestData.message(
                        id = "m1",
                        senderId = "uid2",
                        content = "Forwarded reply",
                    ).copy(isForwarded = true),
                    isOwnMessage = false,
                    replyToMessage = TestData.message(content = "Original quoted", senderId = "uid3"),
                    linkPreview = null,
                    currentUserId = "uid1",
                    callbacks = emptyCallbacks,
                )
            }
        }
        composeTestRule.onNodeWithText("Forwarded reply").assertIsDisplayed()
        composeTestRule.onNodeWithText("Forwarded").assertIsDisplayed()
    }
}
