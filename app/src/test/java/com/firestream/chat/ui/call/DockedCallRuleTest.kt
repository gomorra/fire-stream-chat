package com.firestream.chat.ui.call

import com.firestream.chat.domain.model.CallState
import com.firestream.chat.domain.model.CallSurface
import com.firestream.chat.domain.model.EndReason
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Which chat draws the running call as a docked card, and when. */
class DockedCallRuleTest {

    private val connected = CallState.Connected("call1", "remote1", "Alice", null, startTime = 1L)
    private val noSurface = emptySet<CallSurface>()

    @Test
    fun `the call docks in its own chat while the stage is off screen`() {
        assertTrue(docksIn("chat1", "chat1", connected, noSurface))
        // The card itself is on screen then.
        assertTrue(docksIn("chat1", "chat1", connected, setOf(CallSurface.DOCK)))
    }

    @Test
    fun `another chat shows nothing of the call`() {
        assertFalse(docksIn("chat2", "chat1", connected, noSurface))
    }

    @Test
    fun `a call whose chat is not known docks nowhere`() {
        assertFalse(docksIn("chat1", null, connected, noSurface))
    }

    @Test
    fun `the card is gone while the stage or its small window is on screen`() {
        assertFalse(docksIn("chat1", "chat1", connected, setOf(CallSurface.STAGE)))
        assertFalse(docksIn("chat1", "chat1", connected, setOf(CallSurface.STAGE, CallSurface.DOCK)))
    }

    @Test
    fun `an outgoing ring and a connecting call dock too`() {
        assertTrue(docksIn("chat1", "chat1", CallState.OutgoingRinging("call1", "remote1", "Alice", null), noSurface))
        assertTrue(docksIn("chat1", "chat1", CallState.Connecting("call1", "remote1", "Alice", null), noSurface))
    }

    @Test
    fun `an incoming ring cannot be docked`() {
        assertFalse(docksIn("chat1", "chat1", CallState.IncomingRinging("call1", "remote1", "Alice", null), noSurface))
    }

    @Test
    fun `no call and an ended call dock nowhere`() {
        assertFalse(docksIn("chat1", "chat1", CallState.Idle, noSurface))
        assertFalse(docksIn("chat1", "chat1", CallState.Ended("call1", EndReason.HANGUP), noSurface))
    }
}
