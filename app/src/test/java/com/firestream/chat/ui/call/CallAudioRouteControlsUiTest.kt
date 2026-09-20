package com.firestream.chat.ui.call

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.firestream.chat.domain.model.CallAudioRoute
import com.firestream.chat.domain.model.CallAudioRoute.BLUETOOTH
import com.firestream.chat.domain.model.CallAudioRoute.EARPIECE
import com.firestream.chat.domain.model.CallAudioRoute.SPEAKER
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
// Stub Application to bypass FireStreamApp's Hilt + Firebase init.
@Config(sdk = [31], application = android.app.Application::class)
class CallAudioRouteControlsUiTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    private val picks = mutableListOf<CallAudioRoute>()

    private fun setButton(current: CallAudioRoute, available: List<CallAudioRoute>) {
        composeTestRule.setContent {
            MaterialTheme {
                CallAudioRouteButton(
                    audioRoute = current,
                    availableRoutes = available,
                    onSelectRoute = { picks += it }
                )
            }
        }
    }

    @Test
    fun `two routes - the button toggles earpiece to speaker without a sheet`() {
        setButton(EARPIECE, listOf(EARPIECE, SPEAKER))

        composeTestRule.onNodeWithContentDescription("Audio output: Phone").performClick()

        assertEquals(listOf(SPEAKER), picks)
        composeTestRule.onAllNodesWithTag(CALL_ROUTE_ROW_TAG).assertCountEquals(0)
    }

    @Test
    fun `two routes - the button toggles speaker back to earpiece`() {
        setButton(SPEAKER, listOf(EARPIECE, SPEAKER))

        composeTestRule.onNodeWithContentDescription("Audio output: Speaker").performClick()

        assertEquals(listOf(EARPIECE), picks)
    }

    @Test
    fun `empty route list - the button still toggles and does not crash`() {
        setButton(EARPIECE, emptyList())

        composeTestRule.onNodeWithContentDescription("Audio output: Phone").performClick()

        assertEquals(listOf(SPEAKER), picks)
    }

    @Test
    fun `three routes - the button opens a sheet with three rows and selects nothing`() {
        setButton(BLUETOOTH, listOf(EARPIECE, SPEAKER, BLUETOOTH))

        composeTestRule.onNodeWithContentDescription("Audio output: Bluetooth").performClick()

        composeTestRule.onAllNodesWithTag(CALL_ROUTE_ROW_TAG).assertCountEquals(3)
        assertEquals(emptyList<CallAudioRoute>(), picks)
    }

    @Test
    fun `route list - tapping a row selects that route`() {
        composeTestRule.setContent {
            MaterialTheme {
                CallAudioRouteList(
                    audioRoute = BLUETOOTH,
                    availableRoutes = listOf(EARPIECE, SPEAKER, BLUETOOTH),
                    onSelectRoute = { picks += it }
                )
            }
        }

        composeTestRule.onNodeWithText("Speaker").performClick()

        assertEquals(listOf(SPEAKER), picks)
    }
}
