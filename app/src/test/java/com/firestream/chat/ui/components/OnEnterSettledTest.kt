package com.firestream.chat.ui.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.dp
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * [OnEnterSettled] fires once the overlay is fully in — never while it is
 * still fading, and exactly once per open. The clock is driven by hand so the
 * fade's duration is what the assertions are about.
 *
 * The overlay is composed hidden and then shown, the way `ChatScreen`'s
 * preview is: only an `AnimatedVisibility` that *becomes* visible animates in.
 * One composed visible from the start is drawn in place, which is the
 * rotation case and gets its own test.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29], application = android.app.Application::class)
class OnEnterSettledTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    private var visible by mutableStateOf(false)
    private var settled = 0

    private fun setContent() {
        composeTestRule.mainClock.autoAdvance = false
        composeTestRule.setContent {
            AnimatedVisibility(
                visible = visible,
                enter = fadeIn(tween(FADE_MS)),
                exit = fadeOut(tween(FADE_MS)),
            ) {
                OnEnterSettled { settled++ }
                Box(Modifier.size(10.dp))
            }
        }
    }

    @Test
    fun `does not fire while the fade is still running`() {
        setContent()

        visible = true
        composeTestRule.waitForIdle()
        composeTestRule.mainClock.advanceTimeBy(FADE_MS / 2L)

        composeTestRule.waitForIdle()
        assertEquals(0, settled)
    }

    @Test
    fun `fires once when the fade has landed`() {
        setContent()

        visible = true
        composeTestRule.waitForIdle()
        composeTestRule.mainClock.advanceTimeBy(FADE_MS * 3L)

        composeTestRule.waitForIdle()
        assertEquals(1, settled)
    }

    @Test
    fun `an enter cut short by a dismissal still counts as landed`() {
        setContent()
        visible = true
        composeTestRule.waitForIdle()
        composeTestRule.mainClock.advanceTimeBy(FADE_MS / 3L)

        visible = false
        composeTestRule.waitForIdle()
        composeTestRule.mainClock.advanceTimeBy(FADE_MS * 3L)

        composeTestRule.waitForIdle()
        assertEquals(1, settled)
    }

    @Test
    fun `an overlay composed visible from the start has nothing to wait for`() {
        // What a restore after rotation looks like: no fade, drawn in place, so
        // whatever was to happen once it is opaque happens at once.
        visible = true
        setContent()

        composeTestRule.mainClock.advanceTimeByFrame()

        composeTestRule.waitForIdle()
        assertEquals(1, settled)
    }

    private companion object {
        const val FADE_MS = 300
    }
}
