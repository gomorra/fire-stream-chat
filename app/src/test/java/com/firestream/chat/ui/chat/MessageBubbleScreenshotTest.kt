package com.firestream.chat.ui.chat

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.unit.dp
import com.firestream.chat.domain.model.MessageStatus
import com.firestream.chat.domain.model.MessageType
import com.firestream.chat.test.TestData
import com.firestream.chat.ui.theme.FireStreamTheme
import com.github.takahirom.roborazzi.captureRoboImage
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Roborazzi screenshot baselines for [MessageBubble].
 *
 * Replaces the value-assertion typography tests originally planned in 2e —
 * a screenshot baseline catches the actual rendering, including:
 *  - TC-03: vertical centering of bubble text (sprint commit `0005a7a`)
 *  - TC-04: 1sp typography reduction (sprint commit `e58c4d4`)
 *  - TC-05: 15.5sp font standardization (sprint commit `ebaff52`)
 *
 * Baselines live in `app/src/test/snapshots/`. Recording with
 * `./gradlew :app:recordRoborazziDebug` (writes new baselines), verification
 * with `./gradlew :app:verifyRoborazziDebug` (compares against the recorded
 * PNGs and fails on diff).
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [29], application = android.app.Application::class, qualifiers = "w360dp-h640dp")
class MessageBubbleScreenshotTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    private val emptyCallbacks = MessageBubbleCallbacks(
        onDelete = null,
        onEdit = null,
        onReply = {},
        onReaction = {},
        onForward = {},
        onInfo = null,
    )

    @Composable
    private fun BubbleStage(content: @Composable () -> Unit) {
        FireStreamTheme {
            Box(
                modifier = Modifier
                    .background(Color.White)
                    .width(360.dp)
                    .padding(12.dp),
            ) { content() }
        }
    }

    @Test
    fun `text bubble baseline`() {
        composeTestRule.setContent {
            BubbleStage {
                MessageBubble(
                    message = TestData.message(
                        id = "m1",
                        senderId = "uid2",
                        content = "Hi! Vertical centering check.",
                        type = MessageType.TEXT,
                        status = MessageStatus.SENT,
                    ),
                    isOwnMessage = false,
                    replyToMessage = null,
                    linkPreview = null,
                    currentUserId = "uid1",
                    callbacks = emptyCallbacks,
                )
            }
        }
        composeTestRule.onRoot().captureRoboImage(filePath = "src/test/snapshots/messagebubble-text.png")
    }

    @Test
    fun `own text bubble baseline`() {
        composeTestRule.setContent {
            BubbleStage {
                MessageBubble(
                    message = TestData.message(
                        id = "m1",
                        senderId = "uid1",
                        content = "Sent by me",
                        status = MessageStatus.READ,
                    ),
                    isOwnMessage = true,
                    replyToMessage = null,
                    linkPreview = null,
                    currentUserId = "uid1",
                    callbacks = emptyCallbacks,
                )
            }
        }
        composeTestRule.onRoot().captureRoboImage(filePath = "src/test/snapshots/messagebubble-own.png")
    }

    @Test
    fun `emoji-only bubble baseline`() {
        composeTestRule.setContent {
            BubbleStage {
                MessageBubble(
                    message = TestData.message(
                        id = "m1",
                        senderId = "uid2",
                        content = "🎉🥳",
                    ),
                    isOwnMessage = false,
                    replyToMessage = null,
                    linkPreview = null,
                    currentUserId = "uid1",
                    callbacks = emptyCallbacks,
                )
            }
        }
        composeTestRule.onRoot().captureRoboImage(filePath = "src/test/snapshots/messagebubble-emoji.png")
    }

    @Test
    fun `deleted bubble baseline`() {
        composeTestRule.setContent {
            BubbleStage {
                MessageBubble(
                    message = TestData.message(
                        id = "m1",
                        senderId = "uid2",
                        content = "Was here",
                    ).copy(deletedAt = 1_700_000_000_000L),
                    isOwnMessage = false,
                    replyToMessage = null,
                    linkPreview = null,
                    currentUserId = "uid1",
                    callbacks = emptyCallbacks,
                )
            }
        }
        composeTestRule.onRoot().captureRoboImage(filePath = "src/test/snapshots/messagebubble-deleted.png")
    }
}
