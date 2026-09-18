package com.firestream.chat.ui.components

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.firestream.chat.domain.model.Chat
import com.firestream.chat.domain.model.ChatType
import com.firestream.chat.test.TestData
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The shared chat picker — the panel behind "Share to…", "Forward to…" and
 * "Share list to…".
 *
 * What is worth holding still: the send button exists only once something is
 * selected (a panel that offers to send to nobody is the old dialog's "Cancel"
 * in disguise), a row reports a *toggle* rather than an immediate send, and the
 * two empty states say different things — "no chats" and "nothing matched what
 * you typed" are different problems for the user.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29], application = android.app.Application::class)
class ChatPickerPanelUiTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    private val me = "user-1"
    private val alice = TestData.chat(id = "c1", participants = listOf(me, "user-2"))
    private val group = TestData.chat(id = "c2", type = ChatType.GROUP, name = "Weekend Trip")
    private val profiles = mapOf("user-2" to TestData.user(uid = "user-2", displayName = "Alice"))

    private fun setPanel(
        chats: List<Chat> = listOf(alice, group),
        selected: Set<String> = emptySet(),
        searchQuery: String = "",
        onToggleChat: (String) -> Unit = {},
        onSend: () -> Unit = {},
    ) {
        composeTestRule.setContent {
            MaterialTheme {
                ChatPickerPanel(
                    state = ChatPickerState(
                        title = "Forward to…",
                        chats = chats,
                        currentUserId = me,
                        participants = profiles,
                        selectedChatIds = selected,
                        searchQuery = searchQuery,
                    ),
                    callbacks = ChatPickerCallbacks(
                        onBack = {},
                        onSearchQueryChange = {},
                        onToggleChat = onToggleChat,
                        onSend = onSend,
                    ),
                ) {
                    Text("the thing being sent")
                }
            }
        }
    }

    @Test
    fun `it shows the title, the preview and a row per chat`() {
        setPanel()

        composeTestRule.onNodeWithText("Forward to…").assertIsDisplayed()
        composeTestRule.onNodeWithText("the thing being sent").assertIsDisplayed()
        composeTestRule.onNodeWithText("Alice").assertIsDisplayed()
        composeTestRule.onNodeWithText("Weekend Trip").assertIsDisplayed()
    }

    @Test
    fun `tapping a row toggles it instead of sending`() {
        var toggled: String? = null
        var sends = 0
        setPanel(onToggleChat = { toggled = it }, onSend = { sends++ })

        composeTestRule.onNodeWithText("Alice").performClick()

        assertEquals("c1", toggled)
        assertEquals(0, sends)
    }

    @Test
    fun `there is nothing to send until a chat is selected`() {
        setPanel()

        composeTestRule.onNodeWithTag(CHAT_PICKER_SEND_TAG).assertDoesNotExist()
    }

    @Test
    fun `the send button reports the selection count and fires onSend`() {
        var sends = 0
        setPanel(selected = setOf("c1", "c2"), onSend = { sends++ })

        composeTestRule.onNodeWithText("2 chat(s) selected").assertIsDisplayed()
        composeTestRule.onNodeWithTag(CHAT_PICKER_SEND_TAG).performClick()

        assertEquals(1, sends)
    }

    @Test
    fun `an empty list with no query says there are no chats`() {
        setPanel(chats = emptyList())

        composeTestRule.onNodeWithText("No chats yet").assertIsDisplayed()
    }

    @Test
    fun `an empty list under a query names the query`() {
        setPanel(chats = emptyList(), searchQuery = "zz")

        composeTestRule.onNodeWithText("No results for \"zz\"").assertIsDisplayed()
    }
}
