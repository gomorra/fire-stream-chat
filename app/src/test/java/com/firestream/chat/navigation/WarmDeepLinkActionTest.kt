package com.firestream.chat.navigation

import org.junit.Assert.assertEquals
import org.junit.Test

/** What a chat deep link does when the graph is already showing something. */
class WarmDeepLinkActionTest {

    @Test
    fun `a notification for the chat on screen re-enters it, so the chat jumps to its message`() {
        assertEquals(
            WarmDeepLinkAction.REENTER,
            warmDeepLinkAction(keepPlace = false, onTop = true, nearestChatInStack = true)
        )
    }

    @Test
    fun `a notification for another chat opens it on top`() {
        assertEquals(
            WarmDeepLinkAction.OPEN,
            warmDeepLinkAction(keepPlace = false, onTop = false, nearestChatInStack = false)
        )
    }

    @Test
    fun `a notification for a chat under another screen opens it on top, as it always did`() {
        assertEquals(
            WarmDeepLinkAction.OPEN,
            warmDeepLinkAction(keepPlace = false, onTop = false, nearestChatInStack = true)
        )
    }

    @Test
    fun `a call that docks into the chat on screen moves nothing`() {
        // The regression: re-entering the chat scrolled a thread the user was reading to its end.
        assertEquals(
            WarmDeepLinkAction.STAY,
            warmDeepLinkAction(keepPlace = true, onTop = true, nearestChatInStack = true)
        )
    }

    @Test
    fun `a call that docks into a chat under another screen goes back to it`() {
        // The regression: the chat was stacked a second time over the screen opened from it.
        assertEquals(
            WarmDeepLinkAction.POP_TO_CHAT,
            warmDeepLinkAction(keepPlace = true, onTop = false, nearestChatInStack = true)
        )
    }

    @Test
    fun `a call that docks while another chat is open opens its own chat on top`() {
        assertEquals(
            WarmDeepLinkAction.OPEN,
            warmDeepLinkAction(keepPlace = true, onTop = false, nearestChatInStack = false)
        )
    }
}
