package com.firestream.chat.ui.settings

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
// Stub Application to bypass FireStreamApp's Hilt + Firebase init.
@Config(sdk = [31], application = android.app.Application::class)
class FullScreenAlertsRowUiTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    @Test
    fun `the row says On with the access`() {
        composeTestRule.setContent {
            MaterialTheme { FullScreenAlertsRow(granted = true, onClick = {}) }
        }

        composeTestRule.onNodeWithText("Full-screen call alerts").assertIsDisplayed()
        composeTestRule.onNodeWithText("On").assertIsDisplayed()
    }

    @Test
    fun `the row says Off without the access, and what that costs`() {
        composeTestRule.setContent {
            MaterialTheme { FullScreenAlertsRow(granted = false, onClick = {}) }
        }

        composeTestRule.onNodeWithText("Off.", substring = true).assertIsDisplayed()
        composeTestRule.onNodeWithText("only as a notification", substring = true).assertIsDisplayed()
    }

    @Test
    fun `a tap on the row opens the settings page`() {
        var opened = 0
        composeTestRule.setContent {
            MaterialTheme { FullScreenAlertsRow(granted = false, onClick = { opened++ }) }
        }

        composeTestRule.onNodeWithText("Full-screen call alerts").performClick()

        assertEquals(1, opened)
    }
}
