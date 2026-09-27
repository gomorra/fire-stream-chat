package com.firestream.chat.ui.chat

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
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

    private fun show(preview: FilePreview, message: Message = message()) {
        composeTestRule.setContent {
            MaterialTheme {
                FileMessageBubble(
                    message = message,
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

    @Test
    fun `a PDF shows its page count over the first page and an excerpt below`() {
        val thumbnail = File.createTempFile("thumb", ".jpg")
        try {
            show(
                FilePreview.Pdf(
                    thumbnailPath = thumbnail.absolutePath,
                    thumbnailAspect = 0.707f,
                    pageCount = 12,
                    text = FilePreview.Text("Quarterly report\nRevenue grew", truncated = true),
                ),
                message().copy(fileName = "report.pdf", mimeType = "application/pdf"),
            )

            composeTestRule.onNodeWithText("12 pages").assertIsDisplayed()
            composeTestRule.onNodeWithText("Quarterly report\nRevenue grew").assertIsDisplayed()
            composeTestRule.onNodeWithText("Show more").assertExists()
        } finally {
            thumbnail.delete()
        }
    }

    @Test
    fun `an audio file on the device plays inline, with its length`() {
        show(
            FilePreview.None,
            message().copy(fileName = "song.mp3", mimeType = "audio/mpeg", duration = 205),
        )

        composeTestRule.onNodeWithContentDescription("Play").assertIsDisplayed()
        composeTestRule.onNodeWithText("3:25").assertIsDisplayed()
    }

    @Test
    fun `an audio file not yet downloaded shows only the card`() {
        show(
            FilePreview.None,
            message().copy(fileName = "song.mp3", mimeType = "audio/mpeg", duration = 205, localUri = null),
        )

        composeTestRule.onNodeWithContentDescription("Play").assertDoesNotExist()
        composeTestRule.onNodeWithText("MP3 · 2 KB · Tap to download").assertIsDisplayed()
    }

    // Regression: the voice player only ever saw app-recorded AAC, so prepare()
    // had no guard; an audio file it cannot play (a .wma, a corrupt .mp3) threw
    // on the main thread and took the app down.
    @Test
    fun `pressing play on an audio file the player cannot read does not crash`() {
        show(
            FilePreview.None,
            message().copy(fileName = "broken.mp3", mimeType = "audio/mpeg", duration = 12),
        )

        composeTestRule.onNodeWithContentDescription("Play").performClick()
        composeTestRule.waitForIdle()

        composeTestRule.onNodeWithContentDescription("Play").assertIsDisplayed()
        composeTestRule.onNodeWithText("Can't play this file").assertIsDisplayed()
    }
}
