package com.firestream.chat.ui.chat

import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.unit.dp
import com.firestream.chat.domain.model.Message
import com.firestream.chat.domain.model.MessageType
import com.firestream.chat.domain.model.StickerFormat
import com.firestream.chat.test.LottieFixtures.animation
import com.firestream.chat.test.LottieFixtures.tgs
import com.firestream.chat.test.TestData
import com.firestream.chat.test.fakes.testSticker
import com.firestream.chat.ui.components.LOTTIE_STICKER_TAG
import com.firestream.chat.ui.components.LibraryStickerImage
import com.firestream.chat.ui.components.StickerImage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

/**
 * The format switch: which renderer draws a Lottie sticker in the bubble, in a
 * grid and in a preview, and what stands in while its file is not on the device.
 */
@RunWith(RobolectricTestRunner::class)
// Stub Application to bypass FireStreamApp's Hilt + Firebase init.
@Config(sdk = [31], application = android.app.Application::class)
class LottieStickerUiTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    @get:Rule
    val folder = TemporaryFolder()

    private val emptyCallbacks = MessageBubbleCallbacks(
        onDelete = null,
        onEdit = null,
        onReply = {},
        onReaction = {},
        onForward = {},
        onInfo = null,
    )

    /** A stored Lottie sticker, and its first frame when [withStill]. */
    private fun lottieFile(withStill: Boolean = true): File {
        val file = folder.newFile("sticker.tgs").apply { writeBytes(tgs(animation())) }
        if (withStill) File(file.path + ".png").writeBytes(byteArrayOf(1))
        return file
    }

    private fun lottieMessage(localUri: String?) =
        TestData.message(id = "s1", senderId = "uid2", content = "🚌", type = MessageType.STICKER).copy(
            mediaUrl = "https://cdn.example.com/stickers/abc.tgs",
            mimeType = StickerFormat.LOTTIE.mimeType,
            stickerId = "abc",
            localUri = localUri,
        )

    // Unmerged: the bubble's click target merges its children, and a test tag does not survive that.
    private fun lottieNodes() = composeTestRule.onAllNodesWithTag(LOTTIE_STICKER_TAG, useUnmergedTree = true)

    private fun awaitLottie() {
        composeTestRule.waitUntil(5_000) { lottieNodes().fetchSemanticsNodes().isNotEmpty() }
    }

    private fun showBubble(message: Message) {
        composeTestRule.setContent {
            MaterialTheme {
                MessageBubble(
                    message = message,
                    isOwnMessage = false,
                    replyToMessage = null,
                    linkPreview = null,
                    currentUserId = "uid1",
                    callbacks = emptyCallbacks,
                )
            }
        }
    }

    @Test
    fun `a Lottie sticker bubble plays the animation from its library file`() {
        showBubble(lottieMessage(localUri = lottieFile().path))

        awaitLottie()
        composeTestRule.onNodeWithTag(LOTTIE_STICKER_TAG, useUnmergedTree = true).assertIsDisplayed()
        composeTestRule.onNodeWithContentDescription("🚌 Sticker").assertIsDisplayed()
    }

    @Test
    fun `a Lottie sticker whose file has not arrived shows a placeholder and never its url`() {
        showBubble(lottieMessage(localUri = null))

        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithContentDescription("🚌 Sticker").assertIsDisplayed()
        composeTestRule.onNodeWithTag(LOTTIE_STICKER_TAG, useUnmergedTree = true).assertDoesNotExist()
    }

    @Test
    fun `a WebP model is not handed to Lottie`() {
        composeTestRule.setContent {
            MaterialTheme { StickerImage(model = lottieFile().path, modifier = Modifier.size(64.dp)) }
        }

        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithContentDescription("Sticker").assertIsDisplayed()
        composeTestRule.onNodeWithTag(LOTTIE_STICKER_TAG, useUnmergedTree = true).assertDoesNotExist()
    }

    @Test
    fun `a grid draws a Lottie sticker from its first-frame picture`() {
        val file = lottieFile(withStill = true)
        val sticker = testSticker("bus").copy(format = StickerFormat.LOTTIE, localPath = file.path)
        composeTestRule.setContent {
            MaterialTheme { LibraryStickerImage(sticker = sticker, modifier = Modifier.size(64.dp)) }
        }

        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithContentDescription("Sticker").assertIsDisplayed()
        composeTestRule.onNodeWithTag(LOTTIE_STICKER_TAG, useUnmergedTree = true).assertDoesNotExist()
    }

    @Test
    fun `a grid draws the animation itself when the first-frame picture is missing`() {
        val file = lottieFile(withStill = false)
        val sticker = testSticker("bus").copy(format = StickerFormat.LOTTIE, localPath = file.path)
        composeTestRule.setContent {
            MaterialTheme { LibraryStickerImage(sticker = sticker, modifier = Modifier.size(64.dp)) }
        }

        awaitLottie()
        composeTestRule.onNodeWithTag(LOTTIE_STICKER_TAG, useUnmergedTree = true).assertIsDisplayed()
    }

    // --- What a reply, forward or starred preview draws ---

    private fun stillModelOf(message: Message): Any? {
        var model: Any? = "unset"
        composeTestRule.setContent { model = rememberMessageStillModel(message) }
        composeTestRule.waitForIdle()
        return model
    }

    @Test
    fun `a preview of a Lottie sticker draws its first-frame picture`() {
        val file = lottieFile(withStill = true)

        assertEquals(File(file.path + ".png"), stillModelOf(lottieMessage(localUri = file.path)))
    }

    @Test
    fun `a preview of a Lottie sticker without a first-frame picture has nothing to draw`() {
        assertNull(stillModelOf(lottieMessage(localUri = lottieFile(withStill = false).path)))
    }

    @Test
    fun `a preview of a Lottie sticker that is not downloaded does not draw its url`() {
        assertNull(stillModelOf(lottieMessage(localUri = null)))
    }

    @Test
    fun `a preview of a WebP sticker draws the sticker's own file`() {
        val file = folder.newFile("cat.webp")

        assertEquals(file, stillModelOf(lottieMessage(localUri = file.path).copy(mimeType = StickerFormat.WEBP.mimeType)))
    }

    @Test
    fun `a preview of a WebP sticker that is not downloaded draws its url`() {
        val webp = lottieMessage(localUri = null).copy(mimeType = StickerFormat.WEBP.mimeType)

        assertEquals(webp.mediaUrl, stillModelOf(webp))
    }

    @Test
    fun `the file on this device says what a sticker is, whatever mime type the sender wrote`() {
        assertEquals(StickerFormat.LOTTIE, lottieMessage("/stickers/abc.tgs").copy(mimeType = "image/webp").stickerFormat)
        assertEquals(StickerFormat.WEBP, lottieMessage("/stickers/abc.webp").stickerFormat)
    }

    @Test
    fun `a Lottie sticker whose file cannot be read shows the placeholder`() {
        val broken = folder.newFile("broken.tgs").apply { writeBytes(byteArrayOf(1, 2, 3)) }
        composeTestRule.setContent {
            MaterialTheme {
                StickerImage(model = broken, modifier = Modifier.size(64.dp), format = StickerFormat.LOTTIE)
            }
        }

        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithContentDescription("Sticker").assertIsDisplayed()
        composeTestRule.onNodeWithTag(LOTTIE_STICKER_TAG, useUnmergedTree = true).assertDoesNotExist()
    }

    @Test
    fun `a sticker message without a known mime type is a WebP, as older builds send it`() {
        assertEquals(StickerFormat.WEBP, lottieMessage(null).copy(mimeType = null).stickerFormat)
        assertEquals(StickerFormat.WEBP, lottieMessage(null).copy(mimeType = "image/png").stickerFormat)
        assertEquals(StickerFormat.LOTTIE, lottieMessage(null).stickerFormat)
    }
}
