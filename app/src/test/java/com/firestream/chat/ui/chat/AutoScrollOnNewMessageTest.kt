package com.firestream.chat.ui.chat

import com.firestream.chat.domain.model.Message
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Regression tests for [shouldAutoScrollToNewest] — the rule that decides whether an
 * arriving message pulls the chat list down to the newest bubble.
 *
 * The bug: a reaction overlay is anchored to one specific bubble, so scrolling the list
 * out from under it is wrong twice over. The swipe panel is a [androidx.compose.ui.window.Popup]
 * positioned against its bubble and ChatScreen's `isScrollInProgress` collector clears
 * `swipeReactMessage` on *any* scroll — programmatic ones included — so an auto-scroll
 * silently destroyed the panel the user had just opened. The bottom-sheet picker survives,
 * but reacts against a conversation that jumped underneath it. Either way the list must
 * stay put while a reaction is open.
 */
class AutoScrollOnNewMessageTest {

    private val target = Message(id = "m1", chatId = "c1", content = "hi")

    // A viewport showing ~8 items with the newest (reversed index 0) on screen.
    private val atBottomFirstVisible = 0
    private val visibleCount = 8

    @Test
    fun `scrolls to the newest message when near the bottom and no reaction is open`() {
        assertTrue(
            shouldAutoScrollToNewest(
                firstVisibleIndex = atBottomFirstVisible,
                visibleItemCount = visibleCount,
                reactionPickerTarget = null,
                swipeReactTarget = null,
            )
        )
    }

    @Test
    fun `stays put when the reaction picker sheet is open`() {
        assertFalse(
            shouldAutoScrollToNewest(
                firstVisibleIndex = atBottomFirstVisible,
                visibleItemCount = visibleCount,
                reactionPickerTarget = target,
                swipeReactTarget = null,
            )
        )
    }

    @Test
    fun `stays put when the swipe reaction panel is open`() {
        assertFalse(
            shouldAutoScrollToNewest(
                firstVisibleIndex = atBottomFirstVisible,
                visibleItemCount = visibleCount,
                reactionPickerTarget = null,
                swipeReactTarget = target,
            )
        )
    }

    @Test
    fun `stays put when the swipe panel handed off to the picker sheet`() {
        // onPlusClick clears one and sets the other; a recomposition between the two
        // writes must not open a frame in which auto-scroll is allowed again.
        assertFalse(
            shouldAutoScrollToNewest(
                firstVisibleIndex = atBottomFirstVisible,
                visibleItemCount = visibleCount,
                reactionPickerTarget = target,
                swipeReactTarget = target,
            )
        )
    }

    @Test
    fun `does not scroll when the user has scrolled far up, reaction open or not`() {
        // firstVisibleIndex well past one screen of items — the user is reading history.
        assertFalse(
            shouldAutoScrollToNewest(
                firstVisibleIndex = 40,
                visibleItemCount = visibleCount,
                reactionPickerTarget = null,
                swipeReactTarget = null,
            )
        )
        assertFalse(
            shouldAutoScrollToNewest(
                firstVisibleIndex = 40,
                visibleItemCount = visibleCount,
                reactionPickerTarget = target,
                swipeReactTarget = null,
            )
        )
    }

    @Test
    fun `near-bottom window is one screen of items inclusive`() {
        // Boundary of the pre-existing `firstVisible <= visibleCount` rule, pinned so the
        // reaction guard cannot be mistaken for a change in scroll-distance behaviour.
        assertTrue(
            shouldAutoScrollToNewest(
                firstVisibleIndex = visibleCount,
                visibleItemCount = visibleCount,
                reactionPickerTarget = null,
                swipeReactTarget = null,
            )
        )
        assertFalse(
            shouldAutoScrollToNewest(
                firstVisibleIndex = visibleCount + 1,
                visibleItemCount = visibleCount,
                reactionPickerTarget = null,
                swipeReactTarget = null,
            )
        )
    }

    @Test
    fun `reaction guard wins over an empty viewport's default first index`() {
        // visibleItemsInfo empty -> ChatScreen passes firstVisibleIndex 0 / count 0, which
        // satisfies the near-bottom rule. The reaction guard must still hold.
        assertTrue(
            shouldAutoScrollToNewest(
                firstVisibleIndex = 0,
                visibleItemCount = 0,
                reactionPickerTarget = null,
                swipeReactTarget = null,
            )
        )
        assertFalse(
            shouldAutoScrollToNewest(
                firstVisibleIndex = 0,
                visibleItemCount = 0,
                reactionPickerTarget = null,
                swipeReactTarget = target,
            )
        )
    }
}
