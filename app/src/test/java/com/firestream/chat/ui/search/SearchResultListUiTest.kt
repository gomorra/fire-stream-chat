package com.firestream.chat.ui.search

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.firestream.chat.data.remote.LinkPreview
import com.firestream.chat.domain.model.Message
import com.firestream.chat.domain.model.MessageFilterType
import com.firestream.chat.domain.model.MessageType
import com.firestream.chat.test.TestData
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The Links / Docs result rows.
 *
 * Two things about a link row are invisible until it is rendered: the label
 * ("who sent me this, and where?") is resolved by the caller, so a row that
 * silently drops it looks correct in isolation and is wrong on screen; and the
 * preview that makes the row identifiable is only fetched if the row asks for
 * it as it composes.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29], application = android.app.Application::class)
class SearchResultListUiTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    @Test
    fun `a link row shows the sender and chat label alongside the url`() {
        val message = TestData.message(content = "look at https://example.com/article")

        composeTestRule.setContent {
            MaterialTheme {
                SearchResultList(
                    results = listOf(message),
                    filterType = MessageFilterType.LINKS,
                    resultLabel = { "Alice · Weekend Trip" },
                    onResultClick = {},
                    onMediaClick = {},
                )
            }
        }

        composeTestRule.onNodeWithText("Alice · Weekend Trip").assertIsDisplayed()
        composeTestRule.onNodeWithText("https://example.com/article").assertIsDisplayed()
    }

    @Test
    fun `a doc row shows the label and still opens the message`() {
        val message = TestData.message(
            content = "budget.pdf",
            type = MessageType.DOCUMENT,
        )
        var opened = 0

        composeTestRule.setContent {
            MaterialTheme {
                SearchResultList(
                    results = listOf(message),
                    filterType = MessageFilterType.DOCS,
                    resultLabel = { "Bob · Work" },
                    onResultClick = { opened++ },
                    onMediaClick = {},
                )
            }
        }

        composeTestRule.onNodeWithText("Bob · Work").assertIsDisplayed()
        composeTestRule.onNodeWithText("budget.pdf").performClick()

        assertEquals(1, opened)
    }

    @Test
    fun `a link row asks for its preview and then shows the title above the url`() {
        val message = TestData.message(content = "look at https://example.com/article")
        val asked = mutableListOf<Message>()
        val previews = mutableStateMapOf<String, LinkPreview>()

        composeTestRule.setContent {
            MaterialTheme {
                SearchResultList(
                    results = listOf(message),
                    filterType = MessageFilterType.LINKS,
                    resultLabel = { "Alice · Weekend Trip" },
                    onResultClick = {},
                    onMediaClick = {},
                    linkPreviews = previews,
                    onLinkVisible = { asked += it },
                )
            }
        }

        composeTestRule.waitForIdle()
        assertEquals(listOf(message.id), asked.map { it.id })

        // Before the preview resolves the row still stands on its own: the URL
        // is what the user is scanning for, so it must never wait on a fetch.
        composeTestRule.onNodeWithText("https://example.com/article").assertIsDisplayed()

        previews[message.id] = LinkPreview(
            url = "https://example.com/article",
            title = "The harbour at dawn",
            description = "A long description that the row has no room for",
            imageUrl = "https://example.com/hero.png",
        )
        composeTestRule.waitForIdle()

        // Title takes the headline; the URL is demoted, not dropped — a title
        // alone can't answer "is this the shop or the review of it?".
        composeTestRule.onNodeWithText("The harbour at dawn").assertIsDisplayed()
        composeTestRule.onNodeWithText("https://example.com/article").assertIsDisplayed()
        composeTestRule.onNodeWithText("Alice · Weekend Trip").assertIsDisplayed()
    }

    @Test
    fun `a doc row never asks for a link preview`() {
        val message = TestData.message(
            // A filename can look like a URL; only TEXT messages carry links.
            content = "https://example.com/report.pdf",
            type = MessageType.DOCUMENT,
        )
        val asked = mutableListOf<Message>()

        composeTestRule.setContent {
            MaterialTheme {
                SearchResultList(
                    results = listOf(message),
                    filterType = MessageFilterType.DOCS,
                    resultLabel = { "Bob · Work" },
                    onResultClick = {},
                    onMediaClick = {},
                    onLinkVisible = { asked += it },
                )
            }
        }

        composeTestRule.waitForIdle()
        assertTrue(asked.isEmpty())
    }
}
