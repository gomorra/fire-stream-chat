package com.firestream.chat.ui.chat

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.firestream.chat.domain.model.Message
import com.firestream.chat.domain.model.MessageStatus
import com.firestream.chat.domain.model.MessageType
import com.firestream.chat.domain.util.FileKind
import com.firestream.chat.domain.util.FilePreview
import com.firestream.chat.domain.util.FilePreviewSource
import org.junit.After
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [31], application = android.app.Application::class)
class FileMessageBubbleUiTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    private val local = File.createTempFile("notes", ".txt").apply { writeText("placeholder") }

    @After
    fun tearDown() {
        local.delete()
    }

    private fun message() = Message(
        id = "d1", chatId = "chat1", senderId = "uid2", type = MessageType.DOCUMENT, status = MessageStatus.SENT,
        mediaUrl = "https://cdn.example.com/d1.txt", localUri = local.absolutePath,
        fileName = "notes.txt", fileSize = 2_048L, mimeType = "text/plain",
    )

    private fun source(preview: FilePreview) = object : FilePreviewSource {
        override fun cached(path: String): FilePreview? = null
        override suspend fun load(path: String, kind: FileKind): FilePreview = preview
    }

    private fun show(preview: FilePreview) {
        composeTestRule.setContent {
            MaterialTheme {
                FileMessageBubble(
                    message = message(),
                    textColor = Color.Black,
                    transfer = FileTransfer.NONE,
                    uploadProgress = null,
                    onOpen = {},
                    onLongPress = {},
                    previews = source(preview),
                )
            }
        }
    }

    @Test
    fun `a local text file shows its first lines above the card, with no download hint`() {
        show(FilePreview.Text("hello from the file", truncated = false))

        composeTestRule.onNodeWithText("hello from the file").assertIsDisplayed()
        composeTestRule.onNodeWithText("TXT · 2 KB").assertIsDisplayed()
        composeTestRule.onNodeWithText("Show more").assertDoesNotExist()
    }

    @Test
    fun `a long text expands in place and says where the preview ends`() {
        val text = (1..40).joinToString("\n") { "line $it" }
        show(FilePreview.Text(text, truncated = true))

        composeTestRule.onNodeWithText("Show more").performClick()

        composeTestRule.onNodeWithText("Show less").assertExists()
        composeTestRule.onNodeWithText("Preview ends here — open the file to read the rest").assertExists()
    }
}
