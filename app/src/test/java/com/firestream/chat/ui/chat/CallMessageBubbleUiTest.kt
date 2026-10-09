package com.firestream.chat.ui.chat

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import com.firestream.chat.domain.model.MessageType
import com.firestream.chat.test.TestData
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The label a call message shows. Only the caller writes the message, so these bubbles are the
 * callee's view whenever `isOwnMessage` is false. `CallsScreenUiTest` checks that the Calls tab
 * labels the same calls the same way.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [31], application = android.app.Application::class)
class CallMessageBubbleUiTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    private val callbacks = MessageBubbleCallbacks(
        onDelete = null,
        onEdit = null,
        onReply = {},
        onReaction = {},
        onForward = {},
        onInfo = null,
    )

    private fun showReceivedCall(endReason: String, durationSeconds: Int) =
        showCall(isOwnMessage = false, endReason = endReason, durationSeconds = durationSeconds)

    private fun showOwnCall(endReason: String, durationSeconds: Int) =
        showCall(isOwnMessage = true, endReason = endReason, durationSeconds = durationSeconds)

    private fun showCall(isOwnMessage: Boolean, endReason: String, durationSeconds: Int) {
        val message = TestData.message(
            id = "m1",
            senderId = "caller",
            content = endReason,
            type = MessageType.CALL,
        ).copy(duration = durationSeconds)
        composeTestRule.setContent {
            MaterialTheme {
                MessageBubble(
                    message = message,
                    isOwnMessage = isOwnMessage,
                    replyToMessage = null,
                    linkPreview = null,
                    currentUserId = if (isOwnMessage) "caller" else "callee",
                    callbacks = callbacks,
                )
            }
        }
    }

    @Test
    fun `a call the caller cancelled while it rang reads as missed`() {
        showReceivedCall(endReason = "hangup", durationSeconds = 0)

        composeTestRule.onNodeWithText("Missed call").assertIsDisplayed()
    }

    @Test
    fun `a connected call reads as incoming with its duration`() {
        showReceivedCall(endReason = "hangup", durationSeconds = 65)

        composeTestRule.onNodeWithText("Incoming call").assertIsDisplayed()
        composeTestRule.onNodeWithText("1m 5s").assertIsDisplayed()
    }

    @Test
    fun `a declined call still reads as declined`() {
        showReceivedCall(endReason = "declined", durationSeconds = 0)

        composeTestRule.onNodeWithText("Declined").assertIsDisplayed()
    }

    @Test
    fun `my connected call reads as outgoing with its duration`() {
        showOwnCall(endReason = "hangup", durationSeconds = 65)

        composeTestRule.onNodeWithText("Outgoing call").assertIsDisplayed()
        composeTestRule.onNodeWithText("1m 5s").assertIsDisplayed()
    }

    @Test
    fun `my call that I cancelled while it rang reads as no answer`() {
        showOwnCall(endReason = "hangup", durationSeconds = 0)

        composeTestRule.onNodeWithText("No answer").assertIsDisplayed()
    }

    @Test
    fun `my call nobody answered reads as no answer`() {
        showOwnCall(endReason = "timeout", durationSeconds = 0)

        composeTestRule.onNodeWithText("No answer").assertIsDisplayed()
    }

    @Test
    fun `my call that the other person declined reads as declined`() {
        showOwnCall(endReason = "declined", durationSeconds = 0)

        composeTestRule.onNodeWithText("Declined").assertIsDisplayed()
    }
}
