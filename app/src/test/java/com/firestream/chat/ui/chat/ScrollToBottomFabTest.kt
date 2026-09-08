package com.firestream.chat.ui.chat

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListLayoutInfo
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SmallFloatingActionButton
import androidx.compose.material3.Text
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Tests for the scroll-to-bottom FAB visibility threshold:
 * The FAB appears when the chat list is scrolled up by at least 20% of the
 * chat screen's height from the newest message (bottom).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29], application = android.app.Application::class)
class ScrollToBottomFabTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    @Test
    fun `isScrolledUpPastThreshold returns false for empty list or zero viewport`() {
        val state = mockk<LazyListState>()
        val layoutInfo = mockk<LazyListLayoutInfo>()
        every { state.layoutInfo } returns layoutInfo
        every { layoutInfo.totalItemsCount } returns 0
        every { layoutInfo.viewportSize } returns IntSize(1080, 0)
        every { layoutInfo.visibleItemsInfo } returns emptyList()

        assertFalse(isScrolledUpPastThreshold(state, totalItems = 0))
    }

    @Test
    fun `isScrolledUpPastThreshold detects 20 percent threshold correctly`() {
        lateinit var state: LazyListState
        val itemHeights = mutableMapOf<Int, Int>()

        composeTestRule.setContent {
            state = rememberLazyListState()
            LazyColumn(
                state = state,
                reverseLayout = true,
                modifier = Modifier.size(width = 200.dp, height = 500.dp)
            ) {
                items(50) { index ->
                    Box(modifier = Modifier.fillMaxWidth().height(50.dp)) {
                        Text("Item $index")
                    }
                }
            }
        }

        composeTestRule.waitForIdle()

        // Viewport height in Robolectric with height 500.dp:
        val viewportHeight = state.layoutInfo.viewportSize.height
        assertTrue("Viewport height should be > 0", viewportHeight > 0)
        val threshold = viewportHeight * 0.2f

        // At bottom: 0 offset -> false
        assertFalse(isScrolledUpPastThreshold(state, totalItems = 50, itemHeights = itemHeights))

        // Scroll up by less than 20%
        val scrollBelowThreshold = threshold * 0.5f
        composeTestRule.runOnIdle {
            runBlocking { state.scrollBy(scrollBelowThreshold) }
        }
        composeTestRule.waitForIdle()
        assertFalse(isScrolledUpPastThreshold(state, totalItems = 50, itemHeights = itemHeights))

        // Scroll further up so total scroll >= 20%
        val scrollPastThreshold = threshold * 0.7f
        composeTestRule.runOnIdle {
            runBlocking { state.scrollBy(scrollPastThreshold) }
        }
        composeTestRule.waitForIdle()
        assertTrue(isScrolledUpPastThreshold(state, totalItems = 50, itemHeights = itemHeights))

        // Scroll back down to bottom
        composeTestRule.runOnIdle {
            runBlocking { state.scrollToItem(0, 0) }
        }
        composeTestRule.waitForIdle()
        assertFalse(isScrolledUpPastThreshold(state, totalItems = 50, itemHeights = itemHeights))
    }

    @Test
    fun `fab appears after scrolling 20 percent and clicking scrolls to bottom`() {
        lateinit var listState: LazyListState

        composeTestRule.setContent {
            MaterialTheme {
                listState = rememberLazyListState()
                val scope = rememberCoroutineScope()
                val totalItems = 50
                val itemHeights = remember { mutableMapOf<Int, Int>() }
                val showScrollToBottom by remember(totalItems) {
                    derivedStateOf {
                        isScrolledUpPastThreshold(
                            listState = listState,
                            totalItems = totalItems,
                            itemHeights = itemHeights,
                            thresholdFraction = 0.2f
                        )
                    }
                }

                Box(modifier = Modifier.size(width = 300.dp, height = 500.dp)) {
                    LazyColumn(
                        state = listState,
                        reverseLayout = true,
                        modifier = Modifier.fillMaxSize()
                    ) {
                        items(totalItems) { index ->
                            Box(modifier = Modifier.fillMaxWidth().height(40.dp)) {
                                Text("Message $index")
                            }
                        }
                    }

                    AnimatedVisibility(
                        visible = showScrollToBottom,
                        modifier = Modifier
                            .align(Alignment.BottomEnd)
                            .padding(12.dp),
                        enter = fadeIn() + scaleIn(),
                        exit = fadeOut() + scaleOut()
                    ) {
                        SmallFloatingActionButton(
                            onClick = {
                                scope.launch {
                                    listState.scrollToItem(0)
                                }
                            }
                        ) {
                            Icon(
                                imageVector = Icons.Default.KeyboardArrowDown,
                                contentDescription = "Scroll to bottom"
                            )
                        }
                    }
                }
            }
        }

        composeTestRule.waitForIdle()

        // Initially at bottom: FAB is not displayed
        composeTestRule.onNodeWithContentDescription("Scroll to bottom").assertDoesNotExist()

        val viewportHeight = listState.layoutInfo.viewportSize.height
        val threshold = viewportHeight * 0.2f

        // Scroll up by less than 20%
        composeTestRule.runOnIdle {
            runBlocking { listState.scrollBy(threshold * 0.5f) }
        }
        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithContentDescription("Scroll to bottom").assertDoesNotExist()

        // Scroll up past 20%
        composeTestRule.runOnIdle {
            runBlocking { listState.scrollBy(threshold * 0.7f) }
        }
        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithContentDescription("Scroll to bottom").assertIsDisplayed()

        // Click the scroll to bottom button
        composeTestRule.onNodeWithContentDescription("Scroll to bottom").performClick()
        composeTestRule.waitForIdle()

        // Now back at bottom: FAB is hidden
        composeTestRule.onNodeWithContentDescription("Scroll to bottom").assertDoesNotExist()
    }
}
