package com.firestream.chat.ui.call

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.firestream.chat.domain.model.CallParticipant
import com.firestream.chat.domain.model.CallState
import com.firestream.chat.domain.model.CallUiControls
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The stage, drawn from plain state. A video tile is a tagged box here, so the tests say which
 * tile the stage asked for without a camera or a connection.
 */
@RunWith(RobolectricTestRunner::class)
// Stub Application to bypass FireStreamApp's Hilt + Firebase init.
@Config(sdk = [31], application = android.app.Application::class)
class CallStageUiTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    private val alice = CallParticipant(id = "remote1", name = "Alice", avatarUrl = null, connected = true)
    private val remoteVideo = "video_remote1"
    private val selfVideo = "video_$SELF_TILE_ID"

    private fun connected(video: Boolean = false) =
        CallState.Connected("call1", "remote1", "Alice", null, startTime = System.currentTimeMillis(), video = video)

    private fun incoming(video: Boolean) = CallState.IncomingRinging("call1", "remote1", "Alice", null, video = video)

    private fun show(state: CallStageState, callbacks: CallScreenCallbacks = CallScreenCallbacks()) {
        composeTestRule.setContent {
            MaterialTheme {
                CallStage(state, callbacks) { id -> Box(Modifier.fillMaxSize().testTag("video_$id")) }
            }
        }
    }

    private fun connectedStage(
        controls: CallUiControls = CallUiControls(),
        remote: CallParticipant = alice,
    ) = CallStageState(call = connected(), controls = controls, participants = listOf(remote))

    // ── The camera button ───────────────────────────────────────────────────

    @Test
    fun `with the camera off the button turns it on and there is no flip`() {
        val asked = mutableListOf<Boolean>()
        show(connectedStage(), CallScreenCallbacks(onSetCamera = { asked += it }))

        composeTestRule.onNodeWithContentDescription("Flip camera").assertDoesNotExist()
        composeTestRule.onNodeWithContentDescription("Turn camera on").assertIsEnabled().performClick()

        assertEquals(listOf(true), asked)
    }

    @Test
    fun `with the camera on the button turns it off and flip is there`() {
        val asked = mutableListOf<Boolean>()
        show(connectedStage(CallUiControls(cameraOn = true)), CallScreenCallbacks(onSetCamera = { asked += it }))

        composeTestRule.onNodeWithContentDescription("Flip camera").assertIsDisplayed()
        composeTestRule.onNodeWithContentDescription("Turn camera off").performClick()

        assertEquals(listOf(false), asked)
    }

    @Test
    fun `without a video line the camera button is disabled and one line says why`() {
        val asked = mutableListOf<Boolean>()
        show(connectedStage(CallUiControls(videoAvailable = false)), CallScreenCallbacks(onSetCamera = { asked += it }))

        composeTestRule.onNodeWithContentDescription("Turn camera on").assertIsNotEnabled().performClick()
        composeTestRule.onNodeWithText("Alice needs the latest app for video").assertIsDisplayed()

        assertEquals(emptyList<Boolean>(), asked)
    }

    @Test
    fun `with a video line nothing is said about the other side's app`() {
        show(connectedStage())

        composeTestRule.onNodeWithText("Alice needs the latest app for video").assertDoesNotExist()
    }

    // ── Avatar or tile ──────────────────────────────────────────────────────

    @Test
    fun `a camera that is off shows the avatar and asks for no tile`() {
        show(connectedStage())

        composeTestRule.onNodeWithTag(CallStageTags.AVATAR).assertIsDisplayed()
        composeTestRule.onNodeWithTag(remoteVideo).assertDoesNotExist()
    }

    @Test
    fun `a camera that is on keeps the avatar until its first frame has arrived`() {
        show(connectedStage(remote = alice.copy(cameraOn = true, hasFrame = false)))

        // The tile is there for the frame to land in, under the avatar.
        composeTestRule.onNodeWithTag(remoteVideo).assertExists()
        composeTestRule.onNodeWithTag(CallStageTags.AVATAR).assertIsDisplayed()
    }

    @Test
    fun `a camera that has delivered a frame shows the tile and no avatar`() {
        show(connectedStage(remote = alice.copy(cameraOn = true, hasFrame = true)))

        composeTestRule.onNodeWithTag(remoteVideo).assertIsDisplayed()
        composeTestRule.onNodeWithTag(CallStageTags.AVATAR).assertDoesNotExist()
        // The name moves into the top bar.
        composeTestRule.onNodeWithText("Alice").assertIsDisplayed()
    }

    @Test
    fun `the self view floats only while the own camera runs`() {
        show(connectedStage(CallUiControls(cameraOn = true)))
        composeTestRule.onNodeWithTag(CallStageTags.SELF_TILE).assertIsDisplayed()
        composeTestRule.onNodeWithTag(selfVideo).assertExists()
    }

    @Test
    fun `a paused camera has no self view`() {
        show(connectedStage(CallUiControls(cameraOn = true, cameraPaused = true)))

        composeTestRule.onNodeWithTag(CallStageTags.SELF_TILE).assertDoesNotExist()
        composeTestRule.onNodeWithTag(selfVideo).assertDoesNotExist()
    }

    @Test
    fun `a closed microphone on the other side is marked`() {
        show(connectedStage(remote = alice.copy(micOn = false)))

        composeTestRule.onNodeWithText("Muted").assertIsDisplayed()
    }

    // ── The voice call ──────────────────────────────────────────────────────

    @Test
    fun `with both cameras off the stage is the voice call`() {
        show(connectedStage())

        composeTestRule.onNodeWithTag(CallStageTags.AVATAR).assertIsDisplayed()
        composeTestRule.onNodeWithText("Alice").assertIsDisplayed()
        composeTestRule.onNodeWithText("0:00").assertIsDisplayed()
        composeTestRule.onNodeWithText("Muted").assertDoesNotExist()
        composeTestRule.onNodeWithTag(CallStageTags.SELF_TILE).assertDoesNotExist()
        composeTestRule.onNodeWithContentDescription("Minimise").assertIsDisplayed()
        composeTestRule.onNodeWithContentDescription("Hang up").assertIsDisplayed()
    }

    // ── The dock hides only while video shows ───────────────────────────────

    @Test
    fun `while video shows the dock and the top bar hide and a tap brings them back`() {
        show(connectedStage(remote = alice.copy(cameraOn = true, hasFrame = true)))
        composeTestRule.onNodeWithTag(CallStageTags.DOCK).assertIsDisplayed()

        composeTestRule.mainClock.advanceTimeBy(CHROME_HIDE_MILLIS + 1_000)

        composeTestRule.onNodeWithTag(CallStageTags.DOCK).assertDoesNotExist()
        composeTestRule.onNodeWithContentDescription("Minimise").assertDoesNotExist()

        composeTestRule.onNodeWithTag(CallStageTags.STAGE).performClick()

        composeTestRule.onNodeWithTag(CallStageTags.DOCK).assertIsDisplayed()
        composeTestRule.onNodeWithContentDescription("Minimise").assertIsDisplayed()
    }

    @Test
    fun `a voice call keeps the dock and the top bar`() {
        show(connectedStage())

        composeTestRule.mainClock.advanceTimeBy(CHROME_HIDE_MILLIS + 1_000)
        composeTestRule.onNodeWithTag(CallStageTags.STAGE).performClick()

        composeTestRule.onNodeWithTag(CallStageTags.DOCK).assertIsDisplayed()
        composeTestRule.onNodeWithContentDescription("Minimise").assertIsDisplayed()
    }

    // ── The incoming ring ───────────────────────────────────────────────────

    @Test
    fun `a video ring offers decline, voice only and with video`() {
        val answers = mutableListOf<Boolean>()
        show(CallStageState(call = incoming(video = true)), CallScreenCallbacks(onAnswer = { answers += it }))

        composeTestRule.onNodeWithText("Incoming video call").assertIsDisplayed()
        composeTestRule.onNodeWithContentDescription("Decline").assertIsDisplayed()
        composeTestRule.onNodeWithContentDescription("Answer").assertDoesNotExist()
        composeTestRule.onNodeWithContentDescription("With video").performClick()
        composeTestRule.onNodeWithContentDescription("Voice only").performClick()

        assertEquals(listOf(true, false), answers)
    }

    @Test
    fun `a voice ring offers decline and answer`() {
        val answers = mutableListOf<Boolean>()
        var declined = 0
        show(
            CallStageState(call = incoming(video = false)),
            CallScreenCallbacks(onAnswer = { answers += it }, onDecline = { declined++ }),
        )

        composeTestRule.onNodeWithText("Incoming voice call").assertIsDisplayed()
        composeTestRule.onNodeWithContentDescription("With video").assertDoesNotExist()
        composeTestRule.onNodeWithContentDescription("Voice only").assertDoesNotExist()
        composeTestRule.onNodeWithContentDescription("Answer").performClick()
        composeTestRule.onNodeWithContentDescription("Decline").performClick()

        assertEquals(listOf(false), answers)
        assertEquals(1, declined)
    }

    @Test
    fun `on a locked phone a video ring offers only answer, with the camera off`() {
        val answers = mutableListOf<Boolean>()
        show(
            CallStageState(call = incoming(video = true), locked = true),
            CallScreenCallbacks(onAnswer = { answers += it }),
        )

        composeTestRule.onNodeWithContentDescription("With video").assertDoesNotExist()
        composeTestRule.onNodeWithContentDescription("Voice only").assertDoesNotExist()
        composeTestRule.onNodeWithTag(selfVideo).assertDoesNotExist()
        composeTestRule.onNodeWithContentDescription("Answer").performClick()

        assertEquals(listOf(false), answers)
    }

    // ── The outgoing ring ───────────────────────────────────────────────────

    @Test
    fun `an outgoing ring with the camera running shows the own preview and the dock`() {
        show(
            CallStageState(
                call = CallState.OutgoingRinging("call1", "remote1", "Alice", null, video = true),
                controls = CallUiControls(cameraOn = true),
                participants = listOf(alice),
            )
        )

        composeTestRule.onNodeWithTag(selfVideo).assertExists()
        composeTestRule.onNodeWithText("Calling…").assertIsDisplayed()
        composeTestRule.onNodeWithContentDescription("Turn camera off").assertIsDisplayed()
    }

    @Test
    fun `a call that is still being placed shows the callee and only the hang-up button`() {
        var hungUp = 0
        show(
            CallStageState(placing = PlacingCall("remote1", "Alice", null, "chat1", video = true)),
            CallScreenCallbacks(onHangup = { hungUp++ }),
        )

        composeTestRule.onNodeWithText("Alice").assertIsDisplayed()
        composeTestRule.onNodeWithText("Calling…").assertIsDisplayed()
        composeTestRule.onNodeWithContentDescription("Turn camera on").assertDoesNotExist()
        composeTestRule.onNodeWithContentDescription("Minimise").assertDoesNotExist()
        composeTestRule.onNodeWithContentDescription("Hang up").performClick()

        assertEquals(1, hungUp)
    }

    // The state of the call before stays `Ended` until the next call starts.
    @Test
    fun `a call that is being placed shows over the end of the call before`() {
        show(
            CallStageState(
                call = CallState.Ended("call0", com.firestream.chat.domain.model.EndReason.HANGUP),
                placing = PlacingCall("remote1", "Alice", null, "chat1", video = false),
            )
        )

        composeTestRule.onNodeWithText("Calling…").assertIsDisplayed()
        composeTestRule.onNodeWithText("Call ended").assertDoesNotExist()
    }

    @Test
    fun `a call that could not be created says so`() {
        show(CallStageState(placing = PlacingCall("remote1", "Alice", null, "chat1", video = false, failed = true)))

        composeTestRule.onNodeWithText("Call failed").assertIsDisplayed()
        composeTestRule.onNodeWithContentDescription("Hang up").assertDoesNotExist()
    }

    // ── Picture-in-picture ──────────────────────────────────────────────────

    @Test
    fun `the small window draws only the other person`() {
        show(
            connectedStage(
                controls = CallUiControls(cameraOn = true),
                remote = alice.copy(cameraOn = true, hasFrame = true),
            ).copy(inPictureInPicture = true)
        )

        composeTestRule.onNodeWithTag(remoteVideo).assertIsDisplayed()
        composeTestRule.onNodeWithTag(selfVideo).assertDoesNotExist()
        composeTestRule.onNodeWithTag(CallStageTags.DOCK).assertDoesNotExist()
        composeTestRule.onNodeWithContentDescription("Minimise").assertDoesNotExist()
    }
}
