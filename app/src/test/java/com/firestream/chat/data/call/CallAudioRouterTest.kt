package com.firestream.chat.data.call

import android.media.AudioDeviceCallback
import android.media.AudioDeviceInfo
import android.media.AudioManager
import com.firestream.chat.domain.model.CallAudioRoute.BLUETOOTH
import com.firestream.chat.domain.model.CallAudioRoute.EARPIECE
import com.firestream.chat.domain.model.CallAudioRoute.SPEAKER
import io.mockk.Runs
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import java.util.concurrent.Executor

/**
 * Drives [CallAudioRouter] through a mocked `AudioManager`: the two listeners are captured at
 * registration and invoked directly, which is what the framework does on the main thread.
 */
class CallAudioRouterTest {

    private val audioManager = mockk<AudioManager>(relaxed = true)

    private val earpiece = device(AudioDeviceInfo.TYPE_BUILTIN_EARPIECE)
    private val speaker = device(AudioDeviceInfo.TYPE_BUILTIN_SPEAKER)
    private val bluetooth = device(AudioDeviceInfo.TYPE_BLUETOOTH_SCO)

    /** What `getAvailableCommunicationDevices()` answers; reassigned to simulate a hotplug. */
    private var devices = listOf(earpiece, speaker)

    /** What `getCommunicationDevice()` answers — the OS's actual route, which lags a request. */
    private var currentDevice: AudioDeviceInfo? = earpiece

    private val deviceCallback = slot<AudioDeviceCallback>()
    private val communicationListener = slot<AudioManager.OnCommunicationDeviceChangedListener>()

    private lateinit var router: CallAudioRouter

    @Before
    fun setUp() {
        every { audioManager.availableCommunicationDevices } answers { devices }
        every { audioManager.communicationDevice } answers { currentDevice }
        every { audioManager.setCommunicationDevice(any()) } returns true
        every { audioManager.registerAudioDeviceCallback(capture(deviceCallback), any()) } just Runs
        every {
            audioManager.addOnCommunicationDeviceChangedListener(
                any<Executor>(),
                capture(communicationListener),
            )
        } just Runs
        // Never used: the two listeners are captured at registration and invoked directly.
        router = CallAudioRouter(audioManager, Executor { it.run() })
    }

    @Test
    fun `a headset connected before the call is selected on start`() {
        devices = listOf(earpiece, speaker, bluetooth)

        router.start()

        verify { audioManager.setCommunicationDevice(bluetooth) }
        assertEquals(setOf(EARPIECE, SPEAKER, BLUETOOTH), router.state.value.available)
        // The OS is still on the earpiece — the UI must not claim Bluetooth before SCO is up.
        assertEquals(EARPIECE, router.state.value.current)
    }

    @Test
    fun `start on a plain phone leaves the route alone`() {
        router.start()

        verify(exactly = 0) { audioManager.setCommunicationDevice(any()) }
        assertEquals(setOf(EARPIECE, SPEAKER), router.state.value.available)
        assertEquals(EARPIECE, router.state.value.current)
    }

    @Test
    fun `a headset connecting mid-call preempts the pick and does not hand it back`() {
        router.start()
        router.select(SPEAKER)
        osSwitchedTo(speaker)

        connect(bluetooth)

        verify { audioManager.setCommunicationDevice(bluetooth) }
        // Still on the speaker until the OS says otherwise.
        assertEquals(SPEAKER, router.state.value.current)
        assertEquals(setOf(EARPIECE, SPEAKER, BLUETOOTH), router.state.value.available)

        osSwitchedTo(bluetooth)
        assertEquals(BLUETOOTH, router.state.value.current)

        // Churn that changes nothing must not resurrect the overruled speaker pick.
        deviceCallback.captured.onAudioDevicesAdded(emptyArray())
        verify(exactly = 1) { audioManager.setCommunicationDevice(speaker) }
    }

    @Test
    fun `a headset disconnecting falls back to the earpiece not the speaker`() {
        devices = listOf(earpiece, speaker, bluetooth)
        router.start()
        osSwitchedTo(bluetooth)

        disconnect(bluetooth)

        verify { audioManager.setCommunicationDevice(earpiece) }
        verify(exactly = 0) { audioManager.setCommunicationDevice(speaker) }
    }

    @Test
    fun `selecting a route that vanished re-runs the policy instead`() {
        router.start()

        router.select(BLUETOOTH)

        verify(exactly = 0) { audioManager.setCommunicationDevice(any()) }
        assertEquals(EARPIECE, router.state.value.current)

        // The dropped pick must not come back when the device list churns.
        connect(bluetooth)
        verify { audioManager.setCommunicationDevice(bluetooth) }
    }

    @Test
    fun `a disconnect the OS reports as no device stops publishing the vanished route`() {
        // The regression: onCommunicationDeviceChanged(null) used to be dropped and refresh() used
        // to keep the last published route, so current stayed BLUETOOTH after the headset was gone
        // — a route the sheet could not offer, and a current != EARPIECE that kept the proximity
        // lock released while the audio was already back on the earpiece.
        devices = listOf(earpiece, speaker, bluetooth)
        router.start()
        osSwitchedTo(bluetooth)

        devices = listOf(earpiece, speaker)
        currentDevice = null
        communicationListener.captured.onCommunicationDeviceChanged(null)

        // Null, not EARPIECE: the earpiece has been asked for but the OS has not confirmed it.
        assertNull(router.state.value.current)
        assertEquals(setOf(EARPIECE, SPEAKER), router.state.value.available)
        verify { audioManager.setCommunicationDevice(earpiece) }

        osSwitchedTo(earpiece)
        assertEquals(EARPIECE, router.state.value.current)
    }

    @Test
    fun `a device removal the OS has not caught up with does not publish the vanished route`() {
        devices = listOf(earpiece, speaker, bluetooth)
        router.start()
        osSwitchedTo(bluetooth)

        // The device list has lost Bluetooth but getCommunicationDevice() reports nothing yet.
        currentDevice = null
        disconnect(bluetooth)

        assertNull(router.state.value.current)
    }

    @Test
    fun `a route the OS names before the device list has caught up is not published`() {
        // onCommunicationDeviceChanged can beat onAudioDevicesAdded. Publishing Bluetooth while
        // availableRoutes has no Bluetooth row would make the sheet disagree with the button.
        router.start()

        communicationListener.captured.onCommunicationDeviceChanged(bluetooth)

        assertNull(router.state.value.current)
        assertEquals(setOf(EARPIECE, SPEAKER), router.state.value.available)
    }

    @Test
    fun `a device calls do not model leaves the published route alone`() {
        router.start()
        osSwitchedTo(speaker)

        // A cellular call interrupting routes to TYPE_TELEPHONY, which maps to no route.
        communicationListener.captured
            .onCommunicationDeviceChanged(device(AudioDeviceInfo.TYPE_TELEPHONY))

        assertEquals(SPEAKER, router.state.value.current)
    }

    @Test
    fun `select is ignored before start and after stop`() {
        router.select(SPEAKER)
        verify(exactly = 0) { audioManager.setCommunicationDevice(any()) }

        router.start()
        router.stop()
        router.select(SPEAKER)

        verify(exactly = 0) { audioManager.setCommunicationDevice(any()) }
    }

    @Test
    fun `stop clears the communication device once and unregisters both listeners`() {
        router.start()

        router.stop()
        router.stop()

        verify(exactly = 1) { audioManager.clearCommunicationDevice() }
        verify(exactly = 1) { audioManager.unregisterAudioDeviceCallback(any()) }
        verify(exactly = 1) { audioManager.removeOnCommunicationDeviceChangedListener(any()) }
        assertEquals(CallRouteState(), router.state.value)
    }

    // ── helpers ──────────────────────────────────────────────────────────────

    private fun device(type: Int): AudioDeviceInfo = mockk {
        every { this@mockk.type } returns type
    }

    private fun connect(device: AudioDeviceInfo) {
        devices = devices + device
        deviceCallback.captured.onAudioDevicesAdded(arrayOf(device))
    }

    private fun disconnect(device: AudioDeviceInfo) {
        devices = devices - device
        deviceCallback.captured.onAudioDevicesRemoved(arrayOf(device))
    }

    private fun osSwitchedTo(device: AudioDeviceInfo) {
        currentDevice = device
        communicationListener.captured.onCommunicationDeviceChanged(device)
    }
}
