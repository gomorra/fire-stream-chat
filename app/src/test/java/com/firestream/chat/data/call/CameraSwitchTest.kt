package com.firestream.chat.data.call

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The rule of the own camera: what the user switched, and what holds it back. The permission, the
 * video line, the screens and the foreground type are all inputs here, so each can be refused.
 */
class CameraSwitchTest {

    private val port = FakePort()
    private val switch = CameraSwitch(port)

    /** A switch on a call that a screen shows, with nothing decided yet. */
    private fun onScreen() = switch.onScreen(showing = true, preview = false)

    @Test
    fun `the camera starts off and not running`() {
        assertFalse(switch.isOn)
        assertFalse(switch.isRunning)
        assertTrue(port.running.isEmpty())
    }

    @Test
    fun `switched on while a screen shows the call, the camera runs and the service takes the camera type`() {
        onScreen()

        switch.set(true)

        assertTrue(switch.isOn)
        assertTrue(switch.isRunning)
        assertEquals(listOf(true), port.foregroundCamera)
        assertEquals(listOf(true), port.running)
        assertEquals(Shown(on = true, paused = false), port.shown)
    }

    @Test
    fun `without the camera permission the switch stays off and nothing is asked of the system`() {
        port.permission = false
        onScreen()

        switch.set(true)

        assertFalse(switch.isOn)
        assertTrue(port.foregroundCamera.isEmpty())
        assertTrue(port.running.isEmpty())
        assertEquals(Shown(on = false, paused = false), port.shown)
    }

    @Test
    fun `a call without a video line never switches the camera on`() {
        onScreen()
        switch.onVideoLine(false)

        switch.set(true)

        assertFalse(switch.isOn)
        assertTrue(port.foregroundCamera.isEmpty())
        assertTrue(port.running.isEmpty())
    }

    @Test
    fun `a refused foreground type leaves the camera off`() {
        port.typeAllowed = false
        onScreen()

        switch.set(true)

        assertFalse(switch.isOn)
        assertFalse(switch.isRunning)
        assertTrue(port.running.isEmpty())
        // Asked once. The service keeps the type it had, so there is nothing to drop.
        assertEquals(listOf(true), port.foregroundCamera)
    }

    @Test
    fun `switched off, the camera stops and the service drops the camera type`() {
        onScreen()
        switch.set(true)

        switch.set(false)

        assertFalse(switch.isOn)
        assertEquals(listOf(true, false), port.foregroundCamera)
        assertEquals(listOf(true, false), port.running)
        assertEquals(Shown(on = false, paused = false), port.shown)
    }

    @Test
    fun `a switch that does not change asks nothing of the system`() {
        onScreen()
        switch.set(true)
        switch.set(true)
        switch.set(false)
        switch.set(false)

        assertEquals(listOf(true, false), port.foregroundCamera)
        assertEquals(listOf(true, false), port.running)
    }

    @Test
    fun `off screen the camera pauses with the switch still on, and resumes with the screen`() {
        onScreen()
        switch.set(true)

        switch.onScreen(showing = false, preview = false)

        assertTrue(switch.isOn)
        assertFalse(switch.isRunning)
        assertEquals(Shown(on = true, paused = true), port.shown)
        assertEquals(listOf(true, false), port.running)

        switch.onScreen(showing = true, preview = false)

        assertTrue(switch.isRunning)
        assertEquals(Shown(on = true, paused = false), port.shown)
        assertEquals(listOf(true, false, true), port.running)
        // The type follows the switch, not the pause.
        assertEquals(listOf(true), port.foregroundCamera)
    }

    @Test
    fun `switched on while no screen shows the call, the camera waits for one`() {
        switch.set(true)

        assertTrue(switch.isOn)
        assertFalse(switch.isRunning)
        assertEquals(Shown(on = true, paused = true), port.shown)
        assertTrue(port.running.isEmpty())

        switch.onScreen(showing = true, preview = false)

        assertEquals(listOf(true), port.running)
    }

    @Test
    fun `a video line that turns out missing switches a running camera off`() {
        onScreen()
        switch.set(true)

        switch.onVideoLine(false)

        assertFalse(switch.isOn)
        assertEquals(listOf(true, false), port.running)
        assertEquals(listOf(true, false), port.foregroundCamera)
    }

    @Test
    fun `an agreed video line changes nothing by itself`() {
        onScreen()

        switch.onVideoLine(true)

        assertFalse(switch.isOn)
        assertTrue(port.running.isEmpty())
    }

    @Test
    fun `a camera that fails switches off and the service drops the camera type`() {
        onScreen()
        switch.set(true)

        switch.onCameraFailed()

        assertFalse(switch.isOn)
        assertFalse(switch.isRunning)
        assertEquals(listOf(true, false), port.foregroundCamera)
        assertEquals(Shown(on = false, paused = false), port.shown)
    }

    @Test
    fun `a failure of a camera that is already off changes nothing`() {
        onScreen()

        switch.onCameraFailed()

        assertTrue(port.foregroundCamera.isEmpty())
        assertTrue(port.running.isEmpty())
    }

    @Test
    fun `a foreground type refused after the answer switches the camera off`() {
        onScreen()
        switch.set(true)

        switch.onForegroundRefused()

        assertFalse(switch.isOn)
        assertEquals(listOf(true, false), port.running)
        assertEquals(Shown(on = false, paused = false), port.shown)
    }

    // ── The preview of a ring ───────────────────────────────────────────────

    @Test
    fun `the first screen to show a video ring switches the camera on`() {
        switch.onScreen(showing = true, preview = true)

        assertTrue(switch.isOn)
        assertEquals(listOf(true), port.running)
    }

    @Test
    fun `the preview is offered once, to the first screen only`() {
        switch.onScreen(showing = true, preview = false)
        switch.onScreen(showing = false, preview = false)

        switch.onScreen(showing = true, preview = true)

        assertFalse(switch.isOn)
        assertTrue(port.running.isEmpty())
    }

    @Test
    fun `a switch set before the first screen came is not undone by the preview`() {
        // Answered "voice only" from the notification, before the stage was on screen.
        switch.set(false)

        switch.onScreen(showing = true, preview = true)

        assertFalse(switch.isOn)
        assertTrue(port.running.isEmpty())
    }

    @Test
    fun `a preview needs the permission like any other switch`() {
        port.permission = false

        switch.onScreen(showing = true, preview = true)

        assertFalse(switch.isOn)
        assertTrue(port.running.isEmpty())
    }

    @Test
    fun `a screen that is not showing decides nothing`() {
        switch.onScreen(showing = false, preview = false)

        switch.onScreen(showing = true, preview = true)

        assertTrue(switch.isOn)
    }

    private data class Shown(val on: Boolean, val paused: Boolean)

    private class FakePort : CameraSwitch.Port {
        var permission = true
        var typeAllowed = true

        /** Every change of the foreground type that was asked for, in order. */
        val foregroundCamera = mutableListOf<Boolean>()

        /** Every start and stop asked of the camera, in order. */
        val running = mutableListOf<Boolean>()
        var shown: Shown? = null

        override fun hasCameraPermission() = permission

        override fun setForegroundCamera(camera: Boolean): Boolean {
            foregroundCamera += camera
            return typeAllowed || !camera
        }

        override fun setCameraRunning(running: Boolean) {
            this.running += running
        }

        override fun onSwitchChanged(on: Boolean, paused: Boolean) {
            shown = Shown(on, paused)
        }
    }
}
