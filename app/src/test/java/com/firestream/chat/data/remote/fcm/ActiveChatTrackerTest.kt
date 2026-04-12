package com.firestream.chat.data.remote.fcm

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Unit tests for [ActiveChatTracker] (sprint fix `fe502cf`).
 *
 * Covers TC-11, TC-12, TC-13 from the sprint test plan: foreground chat
 * tracking drives FCM notification suppression and unread-counter resets.
 */
class ActiveChatTrackerTest {

    private lateinit var tracker: ActiveChatTracker

    @Before
    fun setUp() {
        tracker = ActiveChatTracker()
    }

    @Test
    fun `isActive returns false when no chat active`() {
        assertFalse(tracker.isActive("chat-1"))
    }

    @Test
    fun `setActive then isActive returns true for matching id`() {
        tracker.setActive("chat-1")
        assertTrue(tracker.isActive("chat-1"))
    }

    @Test
    fun `isActive returns false for non-matching id`() {
        tracker.setActive("chat-1")
        assertFalse(tracker.isActive("chat-2"))
    }

    @Test
    fun `setActive overwrites previous chat`() {
        tracker.setActive("chat-1")
        tracker.setActive("chat-2")
        assertFalse(tracker.isActive("chat-1"))
        assertTrue(tracker.isActive("chat-2"))
    }

    @Test
    fun `clearActive with matching id clears state`() {
        tracker.setActive("chat-1")
        tracker.clearActive("chat-1")
        assertFalse(tracker.isActive("chat-1"))
    }

    @Test
    fun `clearActive with different id is a no-op (race protection)`() {
        // Scenario: chat A is in the process of closing while chat B has
        // already taken over. A's onPause fires clearActive("A") after B's
        // onResume fired setActive("B"). We must NOT clear B.
        tracker.setActive("chat-B")
        tracker.clearActive("chat-A")
        assertTrue(tracker.isActive("chat-B"))
    }

    @Test
    fun `clearActive when no chat active is a no-op`() {
        tracker.clearActive("chat-1") // should not throw
        assertFalse(tracker.isActive("chat-1"))
    }
}
