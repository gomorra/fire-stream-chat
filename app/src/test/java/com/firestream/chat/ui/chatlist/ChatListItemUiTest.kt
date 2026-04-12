package com.firestream.chat.ui.chatlist

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.firestream.chat.domain.model.ChatType
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
 * Compose UI tests for [ChatListItem] running on the JVM via Robolectric.
 *
 * Covers the avatar-tap regression fix from sprint commit `7fdf0f9`:
 *  - TC-24: tapping avatar with image fires `onAvatarClick`, NOT `onClick`
 *  - TC-25: tapping the row body fires `onClick`, NOT `onAvatarClick`
 *  - TC-26: tapping avatar without image falls through to `onClick`
 *
 * These are the project's first Compose UI tests; they establish the
 * Robolectric + createComposeRule pattern for future UI tests.
 */
@RunWith(RobolectricTestRunner::class)
// Stub Application to bypass FireStreamApp's Hilt + Firebase init.
@Config(sdk = [29], application = android.app.Application::class)
class ChatListItemUiTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    @Test
    fun `tapping avatar with image fires onAvatarClick not onClick`() {
        var avatarClicks = 0
        var rowClicks = 0
        val chat = TestData.chat(
            id = "chat1",
            type = ChatType.INDIVIDUAL,
            avatarUrl = "https://example.com/a.jpg",
        )

        composeTestRule.setContent {
            MaterialTheme {
                ChatListItem(
                    chat = chat,
                    currentUserId = "uid1",
                    onClick = { rowClicks++ },
                    onAvatarClick = { avatarClicks++ },
                )
            }
        }

        // The avatar's contentDescription is the chat's display name (or "Chat").
        composeTestRule.onNodeWithContentDescription("Chat").performClick()

        assertEquals("avatar tap should fire onAvatarClick", 1, avatarClicks)
        assertEquals("avatar tap should NOT fire onClick", 0, rowClicks)
    }

    @Test
    fun `tapping row body fires onClick not onAvatarClick`() {
        var avatarClicks = 0
        var rowClicks = 0
        val chat = TestData.chat(
            id = "chat1",
            type = ChatType.INDIVIDUAL,
            avatarUrl = "https://example.com/a.jpg",
            name = "Bob",
        ).copy(
            lastMessage = TestData.message(
                id = "m1",
                senderId = "uid2",
                content = "row body preview",
            ),
        )

        composeTestRule.setContent {
            MaterialTheme {
                ChatListItem(
                    chat = chat,
                    currentUserId = "uid1",
                    onClick = { rowClicks++ },
                    onAvatarClick = { avatarClicks++ },
                )
            }
        }

        // The row body contains the last-message preview text.
        composeTestRule.onNodeWithText("row body preview").performClick()

        assertEquals("row body tap should fire onClick", 1, rowClicks)
        assertEquals("row body tap should NOT fire onAvatarClick", 0, avatarClicks)
    }

    // TC-26 (icon-only avatar falls through to onClick) is intentionally
    // not automated: the icon avatar branch in UserAvatar passes
    // contentDescription = null, so there is no semantic anchor to tap
    // without adding a Modifier.testTag in production code. The fall-through
    // path is exercised manually as part of the smoke test in the docs.

    @Test
    fun `group chat with custom avatar fires onAvatarClick when tapped`() {
        // Regression guard for the group avatar tap fix in 7fdf0f9 — group
        // chats use chat.avatarUrl directly (no recipientId).
        var avatarClicks = 0
        var rowClicks = 0
        val chat = TestData.chat(
            id = "chat1",
            type = ChatType.GROUP,
            name = "Group A",
            avatarUrl = "https://example.com/group.jpg",
            participants = listOf("uid1", "uid2", "uid3"),
        )

        composeTestRule.setContent {
            MaterialTheme {
                ChatListItem(
                    chat = chat,
                    currentUserId = "uid1",
                    onClick = { rowClicks++ },
                    onAvatarClick = { avatarClicks++ },
                )
            }
        }

        composeTestRule.onNodeWithContentDescription("Group A").performClick()

        assertEquals(1, avatarClicks)
        assertEquals(0, rowClicks)
    }

    @Test
    fun `displays chat name and last message`() {
        val chat = TestData.chat(
            id = "chat1",
            type = ChatType.INDIVIDUAL,
            name = "Alice",
        ).copy(
            lastMessage = TestData.message(content = "hi there", senderId = "uid2"),
        )

        composeTestRule.setContent {
            MaterialTheme {
                ChatListItem(chat = chat, currentUserId = "uid1", onClick = {})
            }
        }

        composeTestRule.onNodeWithText("Alice").assertIsDisplayed()
        composeTestRule.onNodeWithText("hi there").assertIsDisplayed()
    }
}
