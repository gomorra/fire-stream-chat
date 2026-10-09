package com.firestream.chat.ui.call

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.click
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeDown
import androidx.compose.ui.test.swipeUp
import androidx.compose.ui.unit.dp
import com.firestream.chat.domain.model.CallParticipant
import com.firestream.chat.domain.model.CallState
import com.firestream.chat.domain.model.CallUiControls
import com.firestream.chat.domain.model.EndReason
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The docked call, drawn from plain state. A video tile is a tagged box here, as in
 * [CallStageUiTest].
 */
@RunWith(RobolectricTestRunner::class)
// Stub Application to bypass FireStreamApp's Hilt + Firebase init.
@Config(sdk = [31], application = android.app.Application::class)
class DockedCallCardUiTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    private val alice = CallParticipant(id = "remote1", name = "Alice", avatarUrl = null, connected = true)
    private val aliceOnVideo = alice.copy(cameraOn = true, hasFrame = true)
    private val remoteVideo = "video_remote1"
    private val selfVideo = "video_$SELF_TILE_ID"

    private val connected =
        CallState.Connected("call1", "remote1", "Alice", null, startTime = System.currentTimeMillis())

    private var fullScreen = 0

    private fun voiceCall(controls: CallUiControls = CallUiControls()) =
        CallStageState(call = connected, controls = controls, participants = listOf(alice))

    private fun videoCall(controls: CallUiControls = CallUiControls(cameraOn = true)) =
        CallStageState(call = connected, controls = controls, participants = listOf(aliceOnVideo))

    private fun show(state: CallStageState, callbacks: CallScreenCallbacks = CallScreenCallbacks()) {
        composeTestRule.setContent {
            MaterialTheme {
                // The card sits at the top of a chat. A root as small as the strip would not grow with it.
                Box(Modifier.fillMaxSize()) {
                    DockedCallCard(state, callbacks, onFullScreen = { fullScreen++ }) { id ->
                        Box(Modifier.fillMaxSize().testTag("video_$id"))
                    }
                }
            }
        }
    }

    private fun assertStrip() {
        composeTestRule.onNodeWithTag(DockedCallTags.STRIP).assertIsDisplayed()
        composeTestRule.onNodeWithTag(DockedCallTags.CARD).assertDoesNotExist()
    }

    private fun assertCard() {
        composeTestRule.onNodeWithTag(DockedCallTags.CARD).assertIsDisplayed()
        composeTestRule.onNodeWithTag(DockedCallTags.STRIP).assertDoesNotExist()
    }

    // ── Which size a call rests in ──────────────────────────────────────────

    @Test
    fun `a voice call rests as the strip`() {
        show(voiceCall())

        assertStrip()
    }

    @Test
    fun `a call with video rests as the card`() {
        show(videoCall())

        assertCard()
    }

    @Test
    fun `a call where only the other side sends video rests as the card`() {
        show(CallStageState(call = connected, participants = listOf(aliceOnVideo)))

        assertCard()
    }

    @Test
    fun `a camera that is on without a frame yet keeps the strip`() {
        show(CallStageState(call = connected, participants = listOf(alice.copy(cameraOn = true))))

        assertStrip()
    }

    @Test
    fun `the call grows into the card when video starts and back when it stops`() {
        var state by mutableStateOf(voiceCall())
        composeTestRule.setContent {
            MaterialTheme { DockedCallCard(state, CallScreenCallbacks(), onFullScreen = {}) { Box(Modifier.fillMaxSize()) } }
        }
        assertStrip()

        state = videoCall()
        composeTestRule.waitForIdle()
        assertCard()

        state = voiceCall()
        composeTestRule.waitForIdle()
        assertStrip()
    }

    // ── The strip ───────────────────────────────────────────────────────────

    @Test
    fun `the strip of a voice call shows the name, camera, microphone and hang up`() {
        val asked = mutableListOf<String>()
        show(
            voiceCall(),
            CallScreenCallbacks(
                onSetCamera = { asked += "camera $it" },
                onToggleMute = { asked += "mute" },
                onHangup = { asked += "hangup" },
            )
        )

        composeTestRule.onNodeWithText("Alice").assertIsDisplayed()
        // The strip is one tap target, so the avatar is merged into it.
        composeTestRule.onNodeWithTag(CallStageTags.AVATAR, useUnmergedTree = true).assertIsDisplayed()
        composeTestRule.onNodeWithContentDescription("Turn camera on").performClick()
        composeTestRule.onNodeWithContentDescription("Mute").performClick()
        composeTestRule.onNodeWithContentDescription("Hang up").performClick()

        assertEquals(listOf("camera true", "mute", "hangup"), asked)
        // The strip has no room for these two.
        composeTestRule.onNodeWithContentDescription("Flip camera").assertDoesNotExist()
        composeTestRule.onNodeWithContentDescription("Full screen").assertDoesNotExist()
        composeTestRule.onNodeWithTag(remoteVideo).assertDoesNotExist()
    }

    @Test
    fun `a tap on the strip opens the card`() {
        show(voiceCall())

        // Beside the buttons: on a narrow screen they reach the middle of the strip.
        composeTestRule.onNodeWithTag(DockedCallTags.STRIP).performTouchInput { click(percentOffset(0.15f, 0.5f)) }

        assertCard()
    }

    @Test
    fun `the strip of an outgoing ring says calling`() {
        show(
            CallStageState(
                call = CallState.OutgoingRinging("call1", "remote1", "Alice", null),
                participants = listOf(alice),
            )
        )

        assertStrip()
        composeTestRule.onNodeWithText("Calling…").assertIsDisplayed()
    }

    @Test
    fun `without a video line the camera button is disabled`() {
        show(voiceCall(CallUiControls(videoAvailable = false)))

        composeTestRule.onNodeWithContentDescription("Turn camera on").assertIsNotEnabled()
    }

    // ── The card ────────────────────────────────────────────────────────────

    @Test
    fun `the card of a video call shows both pictures and every control`() {
        val asked = mutableListOf<String>()
        show(
            videoCall(),
            CallScreenCallbacks(
                onSetCamera = { asked += "camera $it" },
                onFlipCamera = { asked += "flip" },
                onToggleMute = { asked += "mute" },
                onHangup = { asked += "hangup" },
            )
        )

        composeTestRule.onNodeWithTag(remoteVideo).assertIsDisplayed()
        composeTestRule.onNodeWithTag(selfVideo).assertIsDisplayed()
        composeTestRule.onNodeWithTag(CallStageTags.AVATAR).assertDoesNotExist()
        composeTestRule.onNodeWithContentDescription("Turn camera off").performClick()
        composeTestRule.onNodeWithContentDescription("Flip camera").performClick()
        composeTestRule.onNodeWithContentDescription("Mute").performClick()
        composeTestRule.onNodeWithContentDescription("Hang up").performClick()

        assertEquals(listOf("camera false", "flip", "mute", "hangup"), asked)
    }

    @Test
    fun `the card shows the avatar while only the own camera runs`() {
        show(CallStageState(call = connected, controls = CallUiControls(cameraOn = true), participants = listOf(alice)))

        assertCard()
        composeTestRule.onNodeWithTag(CallStageTags.AVATAR).assertIsDisplayed()
        composeTestRule.onNodeWithTag(remoteVideo).assertDoesNotExist()
        composeTestRule.onNodeWithTag(selfVideo).assertIsDisplayed()
    }

    @Test
    fun `the card marks a closed microphone on the other side`() {
        show(videoCall().copy(participants = listOf(aliceOnVideo.copy(micOn = false))))

        composeTestRule.onNodeWithContentDescription("Muted").assertIsDisplayed()
    }

    @Test
    fun `the full screen button goes back to the stage`() {
        show(videoCall())

        composeTestRule.onNodeWithContentDescription("Full screen").performClick()

        assertEquals(1, fullScreen)
    }

    @Test
    fun `pulling the card down goes back to the stage`() {
        show(videoCall())

        composeTestRule.onNodeWithTag(DockedCallTags.CARD).performTouchInput {
            swipeDown(startY = top + 40.dp.toPx(), endY = top + 240.dp.toPx())
        }

        assertEquals(1, fullScreen)
    }

    @Test
    fun `pushing the card up leaves the strip`() {
        show(videoCall())

        composeTestRule.onNodeWithTag(DockedCallTags.CARD).performTouchInput {
            swipeUp(startY = top + 120.dp.toPx(), endY = top + 20.dp.toPx())
        }

        assertStrip()
        assertEquals(0, fullScreen)
    }

    // ── Ended ───────────────────────────────────────────────────────────────

    @Test
    fun `an ended call says so and has nothing left to tap`() {
        show(CallStageState(call = CallState.Ended("call1", EndReason.REMOTE_HANGUP), participants = listOf(aliceOnVideo)))

        assertStrip()
        composeTestRule.onNodeWithText("Call ended").assertIsDisplayed()
        composeTestRule.onNodeWithContentDescription("Hang up").assertDoesNotExist()
    }
}
