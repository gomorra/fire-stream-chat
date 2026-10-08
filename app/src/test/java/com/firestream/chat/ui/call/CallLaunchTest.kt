package com.firestream.chat.ui.call

import androidx.lifecycle.Lifecycle
import com.firestream.chat.domain.model.CallState
import com.firestream.chat.domain.model.EndReason
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CallLaunchTest {

    @Test
    fun `a fresh launch places or answers the call it was opened for`() {
        assertEquals(CallLaunch.PLACE_CALL, callLaunchFor("outgoing", launchedFromHistory = false, callOngoing = false))
        assertEquals(CallLaunch.ANSWER, callLaunchFor("answer", launchedFromHistory = false, callOngoing = true))
    }

    @Test
    fun `a ring launch rings the call it names`() {
        // The fallback notification opens the screen this way, when the push could not start the service.
        assertEquals(CallLaunch.RING, callLaunchFor("ring", launchedFromHistory = false, callOngoing = false))
    }

    @Test
    fun `Recents never rings a call again`() {
        assertEquals(CallLaunch.CLOSE, callLaunchFor("ring", launchedFromHistory = true, callOngoing = false))
    }

    @Test
    fun `a launch without an action shows the call`() {
        // The full-screen intent and the ongoing-call notification open the screen this way.
        assertEquals(CallLaunch.SHOW, callLaunchFor(null, launchedFromHistory = false, callOngoing = true))
    }

    @Test
    fun `Recents does not place a finished call again`() {
        assertEquals(CallLaunch.CLOSE, callLaunchFor("outgoing", launchedFromHistory = true, callOngoing = false))
    }

    @Test
    fun `Recents does not answer a call again`() {
        assertEquals(CallLaunch.CLOSE, callLaunchFor("answer", launchedFromHistory = true, callOngoing = false))
    }

    @Test
    fun `Recents returns to a call that is still going`() {
        assertEquals(CallLaunch.SHOW, callLaunchFor("outgoing", launchedFromHistory = true, callOngoing = true))
    }

    @Test
    fun `a call that ends while its screen is in the background closes the screen at once`() {
        val ended = CallState.Ended("c1", EndReason.REMOTE_HANGUP)

        assertTrue(closesUnseen(ended, Lifecycle.State.CREATED))
    }

    @Test
    fun `a shown screen shows the end before it closes`() {
        val ended = CallState.Ended("c1", EndReason.REMOTE_HANGUP)

        assertFalse(closesUnseen(ended, Lifecycle.State.STARTED))
        assertFalse(closesUnseen(ended, Lifecycle.State.RESUMED))
    }

    @Test
    fun `only the end of the call closes a screen in the background`() {
        assertFalse(closesUnseen(CallState.Connected("c1", "u2", "Alice", null, startTime = 1_000L), Lifecycle.State.CREATED))
    }
}
