package com.firestream.chat.ui.main

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
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
class FullScreenAccessPromptUiTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    @Test
    fun `the prompt says why and offers both answers`() {
        composeTestRule.setContent {
            MaterialTheme { FullScreenAccessPrompt(visible = true, onOpenSettings = {}, onNotNow = {}) }
        }

        composeTestRule.onNodeWithTag(FULL_SCREEN_ACCESS_PROMPT_TAG).assertIsDisplayed()
        composeTestRule.onNodeWithText("incoming calls and timer alarms", substring = true).assertIsDisplayed()
        composeTestRule.onNodeWithText("Open settings").assertIsDisplayed()
        composeTestRule.onNodeWithText("Not now").assertIsDisplayed()
    }

    @Test
    fun `nothing is drawn with the access on`() {
        composeTestRule.setContent {
            MaterialTheme { FullScreenAccessPrompt(visible = false, onOpenSettings = {}, onNotNow = {}) }
        }

        composeTestRule.onNodeWithTag(FULL_SCREEN_ACCESS_PROMPT_TAG).assertDoesNotExist()
    }

    @Test
    fun `the button opens the settings and leaves the prompt up`() {
        var opened = 0
        var putOff = 0
        composeTestRule.setContent {
            MaterialTheme {
                FullScreenAccessPrompt(visible = true, onOpenSettings = { opened++ }, onNotNow = { putOff++ })
            }
        }

        composeTestRule.onNodeWithText("Open settings").performClick()

        assertEquals(1, opened)
        assertEquals(0, putOff)
        composeTestRule.onNodeWithTag(FULL_SCREEN_ACCESS_PROMPT_TAG).assertIsDisplayed()
    }

    @Test
    fun `Not now hides the prompt`() {
        var visible by mutableStateOf(true)
        var opened = 0
        composeTestRule.setContent {
            MaterialTheme {
                FullScreenAccessPrompt(
                    visible = visible,
                    onOpenSettings = { opened++ },
                    onNotNow = { visible = false },
                )
            }
        }

        composeTestRule.onNodeWithText("Not now").performClick()

        assertEquals(0, opened)
        composeTestRule.onNodeWithTag(FULL_SCREEN_ACCESS_PROMPT_TAG).assertDoesNotExist()
    }
}
