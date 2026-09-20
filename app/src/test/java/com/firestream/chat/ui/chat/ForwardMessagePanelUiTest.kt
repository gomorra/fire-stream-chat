package com.firestream.chat.ui.chat

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import com.firestream.chat.domain.model.Chat
import com.firestream.chat.domain.model.ChatType
import com.firestream.chat.domain.model.Message
import com.firestream.chat.test.TestData
import com.firestream.chat.ui.components.CHAT_PICKER_SEND_TAG
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * "Forward to…" as a panel rather than a dialog.
 *
 * The behaviour that changed with it: a forward is picked and *then* sent, to as
 * many chats as were ticked, and the panel stays out of the way until a message
 * is handed to it.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [31], application = android.app.Application::class)
class ForwardMessagePanelUiTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    private val me = "user-1"
    private val alice = TestData.chat(id = "c1", participants = listOf(me, "user-2"))
    private val group = TestData.chat(id = "c2", type = ChatType.GROUP, name = "Weekend Trip")
    private val profiles = mapOf("user-2" to TestData.user(uid = "user-2", displayName = "Alice"))

    private fun setPanel(
        message: Message?,
        onForward: (Message, List<Chat>) -> Unit = { _, _ -> },
        onDismiss: () -> Unit = {},
    ) {
        composeTestRule.setContent {
            MaterialTheme {
                ForwardMessagePanel(
                    target = message,
                    chats = listOf(alice, group),
                    currentUserId = me,
                    participants = profiles,
                    onDismiss = onDismiss,
                    onForward = onForward,
                )
            }
        }
    }

    @Test
    fun `nothing is drawn while no message has been picked`() {
        setPanel(message = null)

        composeTestRule.onNodeWithText("Forward to…").assertDoesNotExist()
    }

    @Test
    fun `it previews the message above the chats it can go to`() {
        setPanel(message = TestData.message(content = "see you at eight"))

        composeTestRule.onNodeWithText("Forward to…").assertIsDisplayed()
        composeTestRule.onNodeWithText("see you at eight").assertIsDisplayed()
        composeTestRule.onNodeWithText("Alice").assertIsDisplayed()
        composeTestRule.onNodeWithText("Weekend Trip").assertIsDisplayed()
    }

    @Test
    fun `ticking two chats forwards to both of them at once`() {
        var forwarded: List<Chat>? = null
        setPanel(message = TestData.message(), onForward = { _, targets -> forwarded = targets })

        composeTestRule.onNodeWithText("Alice").performClick()
        composeTestRule.onNodeWithText("Weekend Trip").performClick()
        composeTestRule.onNodeWithTag(CHAT_PICKER_SEND_TAG).performClick()

        assertEquals(listOf("c1", "c2"), forwarded?.map { it.id })
    }

    @Test
    fun `tapping a picked chat again unpicks it`() {
        var forwarded: List<Chat>? = null
        setPanel(message = TestData.message(), onForward = { _, targets -> forwarded = targets })

        composeTestRule.onNodeWithText("Alice").performClick()
        composeTestRule.onNodeWithText("Weekend Trip").performClick()
        composeTestRule.onNodeWithText("Alice").performClick()
        composeTestRule.onNodeWithTag(CHAT_PICKER_SEND_TAG).performClick()

        assertEquals(listOf("c2"), forwarded?.map { it.id })
    }

    @Test
    fun `a second tap on send does not forward the message twice`() {
        var sends = 0
        setPanel(message = TestData.message(), onForward = { _, _ -> sends++ })

        composeTestRule.onNodeWithText("Alice").performClick()
        composeTestRule.onNodeWithTag(CHAT_PICKER_SEND_TAG).performClick()
        composeTestRule.onNodeWithTag(CHAT_PICKER_SEND_TAG).performClick()

        // The panel and its button stay on screen for the length of the slide-out.
        assertEquals(1, sends)
    }

    @Test
    fun `searching narrows the chats without losing what is already ticked`() {
        var forwarded: List<Chat>? = null
        setPanel(message = TestData.message(), onForward = { _, targets -> forwarded = targets })

        composeTestRule.onNodeWithText("Alice").performClick()
        composeTestRule.onNodeWithText("Search chats").performTextInput("Weekend")

        composeTestRule.onNodeWithText("Alice").assertDoesNotExist()
        composeTestRule.onNodeWithText("Weekend Trip").performClick()
        composeTestRule.onNodeWithTag(CHAT_PICKER_SEND_TAG).performClick()

        assertEquals(listOf("c1", "c2"), forwarded?.map { it.id })
    }

    @Test
    fun `reopening the panel starts from an empty selection`() {
        var target by mutableStateOf<Message?>(TestData.message())
        var forwarded: List<Chat>? = null
        composeTestRule.setContent {
            MaterialTheme {
                ForwardMessagePanel(
                    target = target,
                    chats = listOf(alice, group),
                    currentUserId = me,
                    participants = profiles,
                    onDismiss = { target = null },
                    onForward = { _, targets -> forwarded = targets },
                )
            }
        }

        composeTestRule.onNodeWithText("Alice").performClick()
        target = null
        composeTestRule.waitForIdle()
        target = TestData.message(id = "msg-2")
        composeTestRule.waitForIdle()

        composeTestRule.onNodeWithText("Weekend Trip").performClick()
        composeTestRule.onNodeWithTag(CHAT_PICKER_SEND_TAG).performClick()

        assertEquals(listOf("c2"), forwarded?.map { it.id })
    }
}
