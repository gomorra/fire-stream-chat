package com.firestream.chat.ui.chat

import android.net.Uri
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.performTextInput
import com.firestream.chat.domain.util.MAX_DOCUMENT_BYTES
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [31], application = android.app.Application::class)
class SendFileSheetUiTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    private fun file(size: Long) = PendingFile(Uri.parse("content://pick/1"), "application/pdf", "Report.pdf", size)

    @Test
    fun `the sheet names the file and sends it with its caption`() {
        var sent: String? = null
        composeTestRule.setContent {
            MaterialTheme { SendFileSheet(file = file(1_468_006L), onDismiss = {}, onSend = { sent = it }) }
        }

        composeTestRule.onNodeWithText("Report.pdf").assertExists()
        composeTestRule.onNodeWithText("PDF · 1.4 MB").assertExists()
        composeTestRule.onNodeWithText("Add a caption").performTextInput("  for Monday ")
        // The semantics action, not a pointer tap: under Robolectric the modal sheet
        // can sit partly below the window, and a tap there lands on nothing.
        composeTestRule.onNodeWithText("Send").performSemanticsAction(SemanticsActions.OnClick)
        composeTestRule.waitForIdle()

        assertEquals("for Monday", sent)
    }

    @Test
    fun `a file over the limit says so and cannot be sent`() {
        composeTestRule.setContent {
            MaterialTheme { SendFileSheet(file = file(MAX_DOCUMENT_BYTES + 1), onDismiss = {}, onSend = {}) }
        }

        composeTestRule.onNodeWithText("Files over 100 MB can't be sent.").assertExists()
        composeTestRule.onNodeWithText("Send").assertIsNotEnabled()
    }

    @Test
    fun `the pending file survives a rotation`() {
        val original = file(2_048L)
        val restored = with(PendingFile.Saver) {
            restore(androidx.compose.runtime.saveable.SaverScope { true }.save(original)!!)
        }
        assertEquals(original, restored)
    }
}
