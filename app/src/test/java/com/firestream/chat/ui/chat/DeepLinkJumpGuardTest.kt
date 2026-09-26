package com.firestream.chat.ui.chat

import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.Text
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * A deep-link target can land seconds after the chat opened; the jump to it
 * only happens while the user is still within 10% of a screen of the bottom.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [31], application = android.app.Application::class)
class DeepLinkJumpGuardTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    private lateinit var state: LazyListState
    private var viewportHeight = 0

    @Before
    fun setUp() {
        composeTestRule.setContent {
            state = rememberLazyListState()
            LazyColumn(
                state = state,
                reverseLayout = true,
                modifier = Modifier.size(width = 200.dp, height = 500.dp),
            ) {
                items(50) { index ->
                    Box(modifier = Modifier.fillMaxWidth().height(50.dp)) { Text("Item $index") }
                }
            }
        }
        composeTestRule.waitForIdle()
        viewportHeight = state.layoutInfo.viewportSize.height
        assertTrue("Viewport height should be > 0", viewportHeight > 0)
    }

    private fun scrollUpBy(fraction: Float) {
        composeTestRule.runOnIdle { runBlocking { state.scrollBy(viewportHeight * fraction) } }
        composeTestRule.waitForIdle()
    }

    private fun jumps() = shouldJumpToDeepLinkTarget(state, totalItems = 50)

    @Test
    fun `at the bottom the jump happens`() {
        assertTrue(jumps())
    }

    @Test
    fun `a nudge under 10 percent still jumps`() {
        scrollUpBy(0.05f)
        assertTrue(jumps())
    }

    @Test
    fun `scrolling past 10 percent keeps the user's place`() {
        scrollUpBy(0.15f)
        assertFalse(jumps())
    }

    // "Up to 10%" is inclusive: exactly at the line still jumps, and no FAB.
    @Test
    fun `exactly 10 percent still jumps and shows no FAB`() {
        val thresholdPx = viewportHeight * SCROLLED_AWAY_THRESHOLD
        assumeTrue("threshold must be a whole pixel to land on it", thresholdPx % 1f == 0f)
        composeTestRule.runOnIdle { runBlocking { state.scrollBy(thresholdPx) } }
        composeTestRule.waitForIdle()

        assertTrue(jumps())
        assertFalse(isScrolledUpPastThreshold(state, totalItems = 50))
    }

    // One threshold, two behaviours: wherever the list sits, exactly one of
    // "jump to the target" and "show the scroll-to-bottom FAB" holds.
    @Test
    fun `the jump and the scroll-to-bottom FAB never both hold or both fail`() {
        for (step in listOf(0f, 0.03f, 0.05f, 0.04f, 0.1f, 0.3f)) {
            scrollUpBy(step)
            val fab = isScrolledUpPastThreshold(state, totalItems = 50)
            assertTrue("jump=${jumps()} fab=$fab after +$step", jumps() != fab)
        }
    }

    @Test
    fun `scrolling away and back to the bottom jumps again`() {
        scrollUpBy(0.5f)
        assertFalse(jumps())
        composeTestRule.runOnIdle { runBlocking { state.scrollToItem(0, 0) } }
        composeTestRule.waitForIdle()
        assertTrue(jumps())
    }
}
