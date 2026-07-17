package com.firestream.chat.ui.chat

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ChatContentReadyTest {

    @Test
    fun `loading blocks the content area regardless of scroll state`() {
        assertFalse(isChatContentReady(isLoading = true, fromNotification = false, hasSavedScrollIndex = false, persistedScrollResolved = true))
        assertFalse(isChatContentReady(isLoading = true, fromNotification = true, hasSavedScrollIndex = true, persistedScrollResolved = true))
    }

    @Test
    fun `content shows once loaded and the persisted scroll read resolved`() {
        // Content-first regression guard: the ONLY wait beyond loading is the
        // (prefetched, parallel) persisted-scroll read — never a transition.
        assertTrue(isChatContentReady(isLoading = false, fromNotification = false, hasSavedScrollIndex = false, persistedScrollResolved = true))
    }

    @Test
    fun `unresolved persisted read blocks only the process-death restore path`() {
        assertFalse(isChatContentReady(isLoading = false, fromNotification = false, hasSavedScrollIndex = false, persistedScrollResolved = false))
    }

    @Test
    fun `notification opens and same-process re-entry skip the persisted read`() {
        // Notification taps land on the newest message; SavedStateHandle
        // indices are synchronous — neither needs the DataStore value.
        assertTrue(isChatContentReady(isLoading = false, fromNotification = true, hasSavedScrollIndex = false, persistedScrollResolved = false))
        assertTrue(isChatContentReady(isLoading = false, fromNotification = false, hasSavedScrollIndex = true, persistedScrollResolved = false))
    }
}
