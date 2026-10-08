package com.firestream.chat.ui.call

import android.app.Application
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ApplicationProvider
import com.firestream.chat.data.call.CallStateHolder
import com.firestream.chat.domain.model.CallState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/**
 * The call screen's Answer button. Answering moves the call service to a microphone foreground
 * service, which Android 14+ refuses without RECORD_AUDIO, so the tap must reach the host
 * activity's permission check rather than the service.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [31], application = Application::class)
class CallScreenAnswerUiTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    private val app = ApplicationProvider.getApplicationContext<Application>()

    @Test
    fun `answering hands the tap to the host instead of starting the service`() {
        val holder = CallStateHolder().apply {
            updateState(CallState.IncomingRinging("c1", "u2", "Alice", null))
        }
        var answers = 0
        composeTestRule.setContent {
            MaterialTheme {
                CallScreen(
                    onFinish = {},
                    onAnswer = { answers++ },
                    viewModel = CallViewModel(holder, app),
                )
            }
        }

        composeTestRule.onNodeWithContentDescription("Answer").performClick()

        assertEquals(1, answers)
        assertNull(shadowOf(app).nextStartedService)
    }
}
