package com.firestream.chat.ui.search

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.firestream.chat.domain.model.MessageFilterType
import com.firestream.chat.domain.model.MessageType
import com.firestream.chat.test.TestData
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The Links / Docs result rows, which until now showed a bare URL: a global hit
 * is only useful if it also says who sent it and in which chat, and that label
 * is resolved by the caller, so a row that silently ignores it looks correct in
 * isolation and is wrong on screen.
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
}
