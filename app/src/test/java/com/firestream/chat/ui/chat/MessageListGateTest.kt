package com.firestream.chat.ui.chat

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MessageListGateTest {

    @Test
    fun `loading blocks composition regardless of transition state`() {
        assertFalse(shouldComposeMessageList(isLoading = true, enterTransitionSettled = true, hadMessagesAtEntry = false))
        assertFalse(shouldComposeMessageList(isLoading = true, enterTransitionSettled = false, hadMessagesAtEntry = false))
        assertFalse(shouldComposeMessageList(isLoading = true, enterTransitionSettled = true, hadMessagesAtEntry = true))
    }

    @Test
    fun `first population waits for the enter transition to settle`() {
        // Regression: composing the full message list mid-slide dropped frames
        // and made the chat-list -> chat transition stutter.
        assertFalse(shouldComposeMessageList(isLoading = false, enterTransitionSettled = false, hadMessagesAtEntry = false))
        assertTrue(shouldComposeMessageList(isLoading = false, enterTransitionSettled = true, hadMessagesAtEntry = false))
    }

    @Test
    fun `pop-return with warm content bypasses the transition gate`() {
        // Returning from message info / profile must not hide the already
        // loaded list behind a spinner while the pop animation runs.
        assertTrue(shouldComposeMessageList(isLoading = false, enterTransitionSettled = false, hadMessagesAtEntry = true))
    }
}
