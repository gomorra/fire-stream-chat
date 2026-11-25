package com.firestream.chat.ui.settings

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
// Stub Application to bypass FireStreamApp's Hilt + Firebase init.
@Config(sdk = [31], application = android.app.Application::class)
class EndToEndEncryptionRowUiTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    @Test
    fun `status row shows Always on badge without a toggle switch`() {
        composeTestRule.setContent {
            MaterialTheme {
                EndToEndEncryptionRow(onClick = {})
            }
        }

        composeTestRule.onNodeWithText("End-to-End Encryption")
            .assertIsDisplayed()
            .assertIsEnabled()
        composeTestRule.onNodeWithText("Always on for 1:1 chats").assertIsDisplayed()
        composeTestRule.onNodeWithText("Always on").assertIsDisplayed()
        composeTestRule.onNode(
            SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.Switch)
        ).assertDoesNotExist()
    }

    @Test
    fun `tapping status row opens 1-to-1 vs group chats info dialog and OK dismisses it`() {
        composeTestRule.setContent {
            MaterialTheme {
                var showDialog by remember { mutableStateOf(false) }
                EndToEndEncryptionRow(onClick = { showDialog = true })
                if (showDialog) {
                    EndToEndEncryptionInfoDialog(onDismiss = { showDialog = false })
                }
            }
        }

        composeTestRule.onNodeWithText("Group and broadcast chats", substring = true).assertDoesNotExist()

        composeTestRule.onNodeWithText("End-to-End Encryption").performClick()

        composeTestRule.onNodeWithText("Signal Protocol", substring = true).assertIsDisplayed()
        composeTestRule.onNodeWithText("AES-256-GCM", substring = true).assertIsDisplayed()
        composeTestRule.onNodeWithText("Group and broadcast chats", substring = true).assertIsDisplayed()

        composeTestRule.onNodeWithText("OK").performClick()

        composeTestRule.onNodeWithText("Group and broadcast chats", substring = true).assertDoesNotExist()
    }
}
