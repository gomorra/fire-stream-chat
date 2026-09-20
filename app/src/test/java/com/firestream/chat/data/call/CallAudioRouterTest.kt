package com.firestream.chat.data.call

import android.media.AudioDeviceCallback
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.os.Handler
import com.firestream.chat.domain.model.CallAudioRoute
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test

/**
 * The routing *decision* is covered by `CallAudioRoutePolicyTest`; this covers the bookkeeping the
 * policy cannot see — which device object is handed to `setCommunicationDevice`, when a user pick
 * is spent, and that start/stop are idempotent.
 *
 * Plain JUnit, no Robolectric: `AudioManager` and `AudioDeviceInfo` are MockK'd, and
 * `unitTests.isReturnDefaultValues` lets the abstract `AudioDeviceCallback` be subclassed on the JVM.
 */
class CallAudioRouterTest {

    private val audioManager: AudioManager = mockk(relaxed = true)
    private val handler: Handler = mockk(relaxed = true)

    private val deviceCallback = slot<AudioDeviceCallback>()
    private val communicationListener = slot<AudioManager.OnCommunicationDeviceChangedListener>()

    private val earpiece = device(AudioDeviceInfo.TYPE_BUILTIN_EARPIECE)
    private val speaker = device(AudioDeviceInfo.TYPE_BUILTIN_SPEAKER)
    private val bluetooth = device(AudioDeviceInfo.TYPE_BLUETOOTH_SCO)
    private val wired = device(AudioDeviceInfo.TYPE_WIRED_HEADSET)

    /** What the OS offers right now; reassign, then call [deviceListChanged]. */
    private var offered: List<AudioDeviceInfo> = listOf(earpiece, speaker)

    private lateinit var router: CallAudioRouter

    @Before
    fun setUp() {
        every { audioManager.availableCommunicationDevices } answers { offered }
        every { audioManager.registerAudioDeviceCallback(capture(deviceCallback), any()) } returns Unit
        every {
            audioManager.addOnCommunicationDeviceChangedListener(any(), capture(communicationListener))
        } returns Unit
        router = CallAudioRouter(audioManager, handler)
    }

    @Test
    fun `start on a plain phone routes to the earpiece and publishes both routes`() {
        router.start()

        verify { audioManager.setCommunicationDevice(earpiece) }
        assertEquals(
            listOf(CallAudioRoute.EARPIECE, CallAudioRoute.SPEAKER),
            router.state.value.available
        )
        // Requested, not confirmed: the OS has not called back yet.
        assertNull(router.state.value.current)
    }

    @Test
    fun `available routes are published in display order, not device order`() {
        offered = listOf(speaker, bluetooth, earpiece)

        router.start()

        assertEquals(
            listOf(CallAudioRoute.EARPIECE, CallAudioRoute.SPEAKER, CallAudioRoute.BLUETOOTH),
            router.state.value.available
        )
    }

    @Test
    fun `a headset connected before the call wins immediately`() {
        offered = listOf(earpiece, speaker, bluetooth)

        router.start()

        verify { audioManager.setCommunicationDevice(bluetooth) }
        verify(exactly = 0) { audioManager.setCommunicationDevice(earpiece) }
    }

    @Test
    fun `current follows the OS, not the request`() {
        offered = listOf(earpiece, speaker, bluetooth)
        router.start()

        // Requested Bluetooth, but SCO is not up yet: nothing is claimed about the live route.
        assertNull(router.state.value.current)

        communicationListener.captured.onCommunicationDeviceChanged(bluetooth)

        assertEquals(CallAudioRoute.BLUETOOTH, router.state.value.current)
        // And the device list it was published with survives the current-route update.
        assertEquals(
            listOf(CallAudioRoute.EARPIECE, CallAudioRoute.SPEAKER, CallAudioRoute.BLUETOOTH),
            router.state.value.available
        )
    }

    @Test
    fun `an unknown communication device reads as no route at all`() {
        router.start()

        communicationListener.captured.onCommunicationDeviceChanged(device(AudioDeviceInfo.TYPE_HDMI))

        assertNull(router.state.value.current)
    }

    @Test
    fun `select routes to the tapped device`() {
        router.start()

        router.select(CallAudioRoute.SPEAKER)

        verify { audioManager.setCommunicationDevice(speaker) }
        assertEquals(
            listOf(CallAudioRoute.EARPIECE, CallAudioRoute.SPEAKER),
            router.state.value.available
        )
    }

    @Test
    fun `select falls back to the policy when the route vanished before the tap landed`() {
        router.start()

        router.select(CallAudioRoute.BLUETOOTH)

        verify(exactly = 0) { audioManager.setCommunicationDevice(bluetooth) }
        // Back through the policy: no pick survives, so the earpiece is re-selected.
        verify(exactly = 2) { audioManager.setCommunicationDevice(earpiece) }
    }

    @Test
    fun `a headset appearing mid-call preempts the user's pick`() {
        router.start()
        router.select(CallAudioRoute.SPEAKER)

        offered = listOf(earpiece, speaker, bluetooth)
        deviceListChanged()

        verify { audioManager.setCommunicationDevice(bluetooth) }
    }

    @Test
    fun `a preempted pick is spent - unplugging the headset lands on the earpiece`() {
        router.start()
        router.select(CallAudioRoute.SPEAKER)

        offered = listOf(earpiece, speaker, bluetooth)
        deviceListChanged()
        offered = listOf(earpiece, speaker)
        deviceListChanged()

        // Not back to the speaker the user once tapped, and never the speaker on a disconnect.
        verify(exactly = 1) { audioManager.setCommunicationDevice(speaker) }
        verify(exactly = 2) { audioManager.setCommunicationDevice(earpiece) }
    }

    @Test
    fun `a pick made while a headset is connected survives an unchanged device list`() {
        offered = listOf(earpiece, speaker, bluetooth)
        router.start()

        router.select(CallAudioRoute.EARPIECE)
        deviceListChanged()

        // Bluetooth was already there at the tap, so its rule-1 preemption must not re-fire.
        verify(exactly = 1) { audioManager.setCommunicationDevice(bluetooth) }
        verify(exactly = 2) { audioManager.setCommunicationDevice(earpiece) }
    }

    @Test
    fun `a wired headset plugged in while on Bluetooth wins`() {
        offered = listOf(earpiece, speaker, bluetooth)
        router.start()

        offered = listOf(earpiece, speaker, bluetooth, wired)
        deviceListChanged()

        verify { audioManager.setCommunicationDevice(wired) }
    }

    @Test
    fun `start twice registers one set of listeners and routes once`() {
        router.start()
        router.start()

        verify(exactly = 1) { audioManager.registerAudioDeviceCallback(any(), any()) }
        verify(exactly = 1) { audioManager.addOnCommunicationDeviceChangedListener(any(), any()) }
        verify(exactly = 1) { audioManager.setCommunicationDevice(earpiece) }
    }

    @Test
    fun `stop hands the device back and unregisters, once`() {
        router.start()

        router.stop()
        router.stop()

        verify(exactly = 1) { audioManager.clearCommunicationDevice() }
        verify(exactly = 1) { audioManager.unregisterAudioDeviceCallback(any()) }
        verify(exactly = 1) { audioManager.removeOnCommunicationDeviceChangedListener(any()) }
    }

    @Test
    fun `nothing is routed after stop`() {
        router.start()
        router.stop()

        router.select(CallAudioRoute.SPEAKER)
        deviceListChanged()
        communicationListener.captured.onCommunicationDeviceChanged(speaker)

        verify(exactly = 0) { audioManager.setCommunicationDevice(speaker) }
        assertNull(router.state.value.current)
    }

    /** Fire the `AudioDeviceCallback` the way the OS does when devices come and go. */
    private fun deviceListChanged() {
        deviceCallback.captured.onAudioDevicesAdded(emptyArray())
    }

    private fun device(deviceType: Int): AudioDeviceInfo = mockk {
        every { type } returns deviceType
    }
}
