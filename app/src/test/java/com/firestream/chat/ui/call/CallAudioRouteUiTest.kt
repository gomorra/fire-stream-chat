package com.firestream.chat.ui.call

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotSelected
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.firestream.chat.domain.model.CallAudioRoute
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Compose UI tests for the in-call audio-route control.
 *
 * The rows are exercised through [CallAudioRouteList] rather than through
 * [CallAudioRouteSheet]: the sheet is a `ModalBottomSheet` wrapper with no logic of its own,
 * and the list is what carries the labels, the selection and the taps.
 */
@RunWith(RobolectricTestRunner::class)
// Stub Application to bypass FireStreamApp's Hilt + Firebase init.
@Config(sdk = [31], application = android.app.Application::class)
class CallAudioRouteUiTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    @Test
    fun `the list shows one row per available route`() {
        composeTestRule.setContent {
            MaterialTheme {
                CallAudioRouteList(
                    current = CallAudioRoute.BLUETOOTH,
                    available = listOf(
                        CallAudioRoute.EARPIECE,
                        CallAudioRoute.SPEAKER,
                        CallAudioRoute.BLUETOOTH,
                    ),
                    onSelect = {},
                )
            }
        }

        composeTestRule.onNodeWithText("Phone").assertIsDisplayed()
        composeTestRule.onNodeWithText("Speaker").assertIsDisplayed()
        composeTestRule.onNodeWithText("Bluetooth").assertIsDisplayed()
    }

    @Test
    fun `only the current route's row is selected`() {
        composeTestRule.setContent {
            MaterialTheme {
                CallAudioRouteList(
                    current = CallAudioRoute.BLUETOOTH,
                    available = listOf(
                        CallAudioRoute.EARPIECE,
                        CallAudioRoute.SPEAKER,
                        CallAudioRoute.BLUETOOTH,
                    ),
                    onSelect = {},
                )
            }
        }

        composeTestRule.onNodeWithText("Bluetooth").assertIsSelected()
        composeTestRule.onNodeWithText("Phone").assertIsNotSelected()
        composeTestRule.onNodeWithText("Speaker").assertIsNotSelected()
    }

    @Test
    fun `tapping a row reports that route`() {
        val picked = mutableListOf<CallAudioRoute>()
        composeTestRule.setContent {
            MaterialTheme {
                CallAudioRouteList(
                    current = CallAudioRoute.EARPIECE,
                    available = listOf(
                        CallAudioRoute.EARPIECE,
                        CallAudioRoute.SPEAKER,
                        CallAudioRoute.WIRED_HEADSET,
                    ),
                    onSelect = { picked += it },
                )
            }
        }

        composeTestRule.onNodeWithText("Headphones").performClick()

        assertEquals(listOf(CallAudioRoute.WIRED_HEADSET), picked)
    }

    @Test
    fun `a route not offered by the OS gets no row`() {
        composeTestRule.setContent {
            MaterialTheme {
                CallAudioRouteList(
                    current = CallAudioRoute.EARPIECE,
                    available = listOf(CallAudioRoute.EARPIECE, CallAudioRoute.BLUETOOTH),
                    onSelect = {},
                )
            }
        }

        composeTestRule.onNodeWithText("Speaker").assertDoesNotExist()
        composeTestRule.onNodeWithText("Headphones").assertDoesNotExist()
    }

    @Test
    fun `with two routes the button toggles earpiece to speaker directly`() {
        val picked = mutableListOf<CallAudioRoute>()
        composeTestRule.setContent {
            MaterialTheme {
                CallAudioRouteButton(
                    audioRoute = CallAudioRoute.EARPIECE,
                    availableRoutes = listOf(CallAudioRoute.EARPIECE, CallAudioRoute.SPEAKER),
                    onSelectRoute = { picked += it },
                )
            }
        }

        composeTestRule.onNodeWithContentDescription("Call audio: Phone").performClick()

        assertEquals(listOf(CallAudioRoute.SPEAKER), picked)
    }

    @Test
    fun `with two routes the button toggles speaker back to earpiece`() {
        val picked = mutableListOf<CallAudioRoute>()
        composeTestRule.setContent {
            MaterialTheme {
                CallAudioRouteButton(
                    audioRoute = CallAudioRoute.SPEAKER,
                    availableRoutes = listOf(CallAudioRoute.EARPIECE, CallAudioRoute.SPEAKER),
                    onSelectRoute = { picked += it },
                )
            }
        }

        composeTestRule.onNodeWithContentDescription("Call audio: Speaker").performClick()

        assertEquals(listOf(CallAudioRoute.EARPIECE), picked)
    }

    /**
     * `availableRoutes` is whatever the OS reports and is empty until the router's first
     * update, so the direct-toggle branch has to cover sizes 0 and 1 without indexing.
     */
    @Test
    fun `an empty route list still toggles rather than crashing`() {
        val picked = mutableListOf<CallAudioRoute>()
        composeTestRule.setContent {
            MaterialTheme {
                CallAudioRouteButton(
                    audioRoute = CallAudioRoute.EARPIECE,
                    availableRoutes = emptyList(),
                    onSelectRoute = { picked += it },
                )
            }
        }

        composeTestRule.onNodeWithContentDescription("Call audio: Phone").performClick()

        assertEquals(listOf(CallAudioRoute.SPEAKER), picked)
    }

    @Test
    fun `with three routes a tap opens the sheet instead of selecting`() {
        val picked = mutableListOf<CallAudioRoute>()
        composeTestRule.setContent {
            MaterialTheme {
                CallAudioRouteButton(
                    audioRoute = CallAudioRoute.EARPIECE,
                    availableRoutes = listOf(
                        CallAudioRoute.EARPIECE,
                        CallAudioRoute.SPEAKER,
                        CallAudioRoute.BLUETOOTH,
                    ),
                    onSelectRoute = { picked += it },
                )
            }
        }

        composeTestRule.onNodeWithContentDescription("Call audio: Phone").performClick()

        assertTrue("the tap must not pick a route by itself", picked.isEmpty())
        composeTestRule.onNodeWithText("Call audio").assertIsDisplayed()
    }

    /**
     * The button renders the route the OS reports, which lags a Bluetooth tap by up to a
     * second — so its icon and description follow `audioRoute`, never the pick.
     */
    @Test
    fun `the button describes the route that is playing`() {
        composeTestRule.setContent {
            MaterialTheme {
                CallAudioRouteButton(
                    audioRoute = CallAudioRoute.BLUETOOTH,
                    availableRoutes = listOf(
                        CallAudioRoute.EARPIECE,
                        CallAudioRoute.SPEAKER,
                        CallAudioRoute.BLUETOOTH,
                    ),
                    onSelectRoute = {},
                )
            }
        }

        composeTestRule.onNodeWithContentDescription("Call audio: Bluetooth").assertIsDisplayed()
    }
}
