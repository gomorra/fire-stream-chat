package com.firestream.chat.ui.call

import android.app.Application
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ApplicationProvider
import com.firestream.chat.data.call.CallStateHolder
import com.firestream.chat.domain.model.CallState
import io.mockk.mockk
import kotlinx.coroutines.test.TestScope
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/**
 * The call screen's answer buttons. Answering moves the call service to a microphone foreground
 * service, which Android 14+ refuses without RECORD_AUDIO, so the tap must reach the host
 * activity's permission check rather than the service.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [31], application = Application::class)
class CallScreenAnswerUiTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    private val app = ApplicationProvider.getApplicationContext<Application>()

    /** Every answer the host was handed: true for *With video*. */
    private val answers = mutableListOf<Boolean>()

    private fun showRing(video: Boolean) {
        val holder = CallStateHolder().apply {
            updateState(CallState.IncomingRinging("c1", "u2", "Alice", null, video = video))
        }
        composeTestRule.setContent {
            MaterialTheme {
                CallScreen(
                    locked = false,
                    inPictureInPicture = false,
                    onAnswer = { withVideo -> answers += withVideo },
                    onSetCamera = {},
                    onMinimise = {},
                    onFinish = {},
                    viewModel = CallViewModel(holder, mockk(), mockk(relaxed = true), TestScope(), app),
                )
            }
        }
    }

    @Test
    fun `answering hands the tap to the host instead of starting the service`() {
        showRing(video = false)

        composeTestRule.onNodeWithContentDescription("Answer").performClick()

        assertEquals(listOf(false), answers)
        assertNull(shadowOf(app).nextStartedService)
    }

    @Test
    fun `both answers of a video ring go to the host too, with the camera the user chose`() {
        showRing(video = true)

        composeTestRule.onNodeWithContentDescription("With video").performClick()
        composeTestRule.onNodeWithContentDescription("Voice only").performClick()

        assertEquals(listOf(true, false), answers)
        assertNull(shadowOf(app).nextStartedService)
    }
}
