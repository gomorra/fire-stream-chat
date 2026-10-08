package com.firestream.chat.ui.call

import org.junit.Assert.assertEquals
import org.junit.Test

class CallLaunchTest {

    @Test
    fun `a fresh launch places or answers the call it was opened for`() {
        assertEquals(CallLaunch.PLACE_CALL, callLaunchFor("outgoing", launchedFromHistory = false, callOngoing = false))
        assertEquals(CallLaunch.ANSWER, callLaunchFor("answer", launchedFromHistory = false, callOngoing = true))
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
}
