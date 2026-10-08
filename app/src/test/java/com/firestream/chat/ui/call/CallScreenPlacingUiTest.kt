package com.firestream.chat.ui.call

import android.app.Application
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ApplicationProvider
import com.firestream.chat.data.call.CallStateHolder
import com.firestream.chat.domain.model.CallState
import com.firestream.chat.domain.model.EndReason
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
 * The call screen while a call is being placed, before the call service holds it. The screen used
 * to stay blank until the service took the call over.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [31], application = Application::class)
class CallScreenPlacingUiTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    private val app = ApplicationProvider.getApplicationContext<Application>()
    private val holder = CallStateHolder()

    private fun showPlacing() {
        holder.prepareOutgoingCall("u2", "Alice", null)
        composeTestRule.setContent {
            MaterialTheme {
                CallScreen(
                    onFinish = {},
                    onAnswer = {},
                    viewModel = CallViewModel(holder, mockk(), TestScope(), app),
                )
            }
        }
    }

    @Test
    fun `a call being placed shows who is called`() {
        showPlacing()

        composeTestRule.onNodeWithText("Alice").assertIsDisplayed()
        composeTestRule.onNodeWithText("Calling...").assertIsDisplayed()
    }

    @Test
    fun `cancelling a call being placed ends it without the call service`() {
        showPlacing()

        composeTestRule.onNodeWithContentDescription("Cancel").performClick()

        assertEquals(CallState.Ended("", EndReason.HANGUP), holder.callState.value)
        assertNull(shadowOf(app).nextStartedService)
    }
}
