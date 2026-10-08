package com.firestream.chat.ui.calls

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.firestream.chat.domain.model.Chat
import com.firestream.chat.domain.model.ChatType
import com.firestream.chat.domain.model.Message
import com.firestream.chat.domain.model.MessageType
import com.firestream.chat.domain.model.User
import com.firestream.chat.domain.repository.AuthRepository
import com.firestream.chat.domain.repository.ChatRepository
import com.firestream.chat.domain.repository.ContactRepository
import com.firestream.chat.domain.repository.MessageRepository
import com.firestream.chat.domain.repository.UserRepository
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.flowOf
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * How the Calls tab labels a call. It must read the way the call's chat bubble reads, which
 * `CallMessageBubbleUiTest` covers. Only the caller writes a call message, so a message from the
 * other person is a call the viewer received.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [31], application = android.app.Application::class)
class CallsScreenUiTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    private val me = "me"
    private val them = "them"
    private val chat = Chat(id = "c1", type = ChatType.INDIVIDUAL, participants = listOf(me, them))

    private fun showCall(senderId: String, endReason: String, durationSeconds: Int) {
        val message = Message(
            id = "m1", chatId = chat.id, senderId = senderId,
            type = MessageType.CALL, content = endReason, duration = durationSeconds
        )
        val viewModel = CallsViewModel(
            messageRepository = mockk<MessageRepository> {
                every { getCallLog() } returns flowOf(listOf(message))
            },
            chatRepository = mockk<ChatRepository> {
                every { getChats() } returns flowOf(listOf(chat))
            },
            authRepository = mockk<AuthRepository> {
                every { currentUserId } returns me
            },
            contactRepository = mockk<ContactRepository> {
                every { getContacts() } returns flowOf(emptyList())
            },
            userRepository = mockk<UserRepository> {
                every { observeUser(them) } returns flowOf(
                    User(uid = them, displayName = "Alice", phoneNumber = "+1234")
                )
            }
        )
        composeTestRule.setContent {
            MaterialTheme {
                CallsScreen(onMessageClick = { _, _ -> }, viewModel = viewModel)
            }
        }
    }

    @Test
    fun `a call I declined reads as declined`() {
        showCall(senderId = them, endReason = "declined", durationSeconds = 0)

        composeTestRule.onNodeWithText("Declined").assertIsDisplayed()
    }

    @Test
    fun `my call that the other person declined reads as declined`() {
        showCall(senderId = me, endReason = "declined", durationSeconds = 0)

        composeTestRule.onNodeWithText("Declined").assertIsDisplayed()
    }

    @Test
    fun `the details of a call I declined say it was declined`() {
        showCall(senderId = them, endReason = "declined", durationSeconds = 0)

        composeTestRule.onNodeWithText("Alice").performClick()

        composeTestRule.onNodeWithText("Declined call").assertIsDisplayed()
    }

    @Test
    fun `a received call nobody answered reads as missed`() {
        showCall(senderId = them, endReason = "timeout", durationSeconds = 0)

        composeTestRule.onNodeWithText("Missed").assertIsDisplayed()
    }

    @Test
    fun `my call nobody answered reads as no answer`() {
        showCall(senderId = me, endReason = "timeout", durationSeconds = 0)

        composeTestRule.onNodeWithText("No answer").assertIsDisplayed()
    }

    @Test
    fun `my call that I cancelled while it rang reads as no answer`() {
        showCall(senderId = me, endReason = "hangup", durationSeconds = 0)

        composeTestRule.onNodeWithText("No answer").assertIsDisplayed()
    }

    @Test
    fun `a connected call shows its duration`() {
        showCall(senderId = them, endReason = "hangup", durationSeconds = 65)

        composeTestRule.onNodeWithText("1m 5s").assertIsDisplayed()
    }
}
