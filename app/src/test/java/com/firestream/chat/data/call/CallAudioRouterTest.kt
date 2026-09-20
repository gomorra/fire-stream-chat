package com.firestream.chat.data.call

import android.media.AudioDeviceCallback
import android.media.AudioDeviceInfo
import android.media.AudioManager
import com.firestream.chat.domain.model.CallAudioRoute
import io.mockk.Runs
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import java.util.concurrent.Executor

/**
 * Drives [CallAudioRouter] over a mocked `AudioManager`: the device list and the two listeners
 * are ours, so the wrapper's own behaviour (re-query, apply, clear the pick, release) is under
 * test even though the real routing is not. The decision table itself is
 * [CallAudioRoutePolicyTest].
 */
class CallAudioRouterTest {

    private val audioManager: AudioManager = mockk(relaxed = true)
    private val deviceCallback = slot<AudioDeviceCallback>()
    private val deviceChangedListener = slot<AudioManager.OnCommunicationDeviceChangedListener>()

    private val earpiece = device(AudioDeviceInfo.TYPE_BUILTIN_EARPIECE)
    private val speaker = device(AudioDeviceInfo.TYPE_BUILTIN_SPEAKER)
    private val bluetooth = device(AudioDeviceInfo.TYPE_BLUETOOTH_SCO)
    private val wired = device(AudioDeviceInfo.TYPE_WIRED_HEADSET)

    private lateinit var router: CallAudioRouter

    @Before
    fun setUp() {
        every { audioManager.registerAudioDeviceCallback(capture(deviceCallback), any()) } just Runs
        every {
            audioManager.addOnCommunicationDeviceChangedListener(any(), capture(deviceChangedListener))
        } just Runs
        every { audioManager.setCommunicationDevice(any()) } returns true
        // relaxed would hand back a mock, not null, for these two — stub both explicitly.
        every { audioManager.availableCommunicationDevices } returns listOf(earpiece, speaker)
        every { audioManager.communicationDevice } returns earpiece
        router = CallAudioRouter(audioManager, Executor { it.run() })
    }

    // ── start ────────────────────────────────────────────────────────────────

    @Test
    fun `a plain phone starts on the earpiece and asks for nothing`() {
        router.start()

        assertEquals(setOf(CallAudioRoute.EARPIECE, CallAudioRoute.SPEAKER), router.state.value.available)
        assertEquals(CallAudioRoute.EARPIECE, router.state.value.current)
        verify(exactly = 0) { audioManager.setCommunicationDevice(any()) }
    }

    @Test
    fun `a headset already connected at start is selected`() {
        devices(earpiece, speaker, bluetooth)

        router.start()

        verify { audioManager.setCommunicationDevice(bluetooth) }
        assertEquals(
            setOf(CallAudioRoute.EARPIECE, CallAudioRoute.SPEAKER, CallAudioRoute.BLUETOOTH),
            router.state.value.available,
        )
    }

    @Test
    fun `current follows the OS, not the request`() {
        devices(earpiece, speaker, bluetooth)
        router.start()

        // The request went out, but SCO is not up yet — the UI must still say earpiece.
        assertEquals(CallAudioRoute.EARPIECE, router.state.value.current)

        deviceChangedListener.captured.onCommunicationDeviceChanged(bluetooth)

        assertEquals(CallAudioRoute.BLUETOOTH, router.state.value.current)
    }

    @Test
    fun `start is idempotent`() {
        router.start()
        router.start()

        verify(exactly = 1) { audioManager.registerAudioDeviceCallback(any(), any()) }
    }

    // ── device changes ───────────────────────────────────────────────────────

    @Test
    fun `a headset connected mid-call preempts the user's pick`() {
        router.start()
        router.select(CallAudioRoute.SPEAKER)
        verify { audioManager.setCommunicationDevice(speaker) }
        every { audioManager.communicationDevice } returns speaker

        devices(earpiece, speaker, bluetooth)
        deviceCallback.captured.onAudioDevicesAdded(arrayOf(bluetooth))

        verify { audioManager.setCommunicationDevice(bluetooth) }
    }

    @Test
    fun `the pick is cleared by the preemption, so a later re-emit stays on the headset`() {
        router.start()
        router.select(CallAudioRoute.SPEAKER)
        devices(earpiece, speaker, bluetooth)
        deviceCallback.captured.onAudioDevicesAdded(arrayOf(bluetooth))
        every { audioManager.communicationDevice } returns bluetooth

        // An unrelated device-list churn must not send us back to the dropped pick.
        devices(earpiece, speaker, bluetooth, wired)
        deviceCallback.captured.onAudioDevicesAdded(arrayOf(wired))

        verify { audioManager.setCommunicationDevice(wired) }
        verify(exactly = 1) { audioManager.setCommunicationDevice(speaker) }
    }

    @Test
    fun `a disconnect falls back to the earpiece, never the speaker`() {
        devices(earpiece, speaker, bluetooth)
        router.start()
        every { audioManager.communicationDevice } returns bluetooth
        deviceChangedListener.captured.onCommunicationDeviceChanged(bluetooth)

        devices(earpiece, speaker)
        every { audioManager.communicationDevice } returns null
        deviceCallback.captured.onAudioDevicesRemoved(arrayOf(bluetooth))

        verify { audioManager.setCommunicationDevice(earpiece) }
        verify(exactly = 0) { audioManager.setCommunicationDevice(speaker) }
    }

    @Test
    fun `the available list is re-queried, not read from the callback`() {
        router.start()

        // The callback reports every device, communication-capable or not; the router must
        // ignore its array and ask AudioManager again.
        deviceCallback.captured.onAudioDevicesAdded(arrayOf(bluetooth))

        assertEquals(setOf(CallAudioRoute.EARPIECE, CallAudioRoute.SPEAKER), router.state.value.available)
        verify(exactly = 0) { audioManager.setCommunicationDevice(bluetooth) }
    }

    // ── select ───────────────────────────────────────────────────────────────

    @Test
    fun `a pick for a route that vanished falls back to the policy`() {
        router.start()

        router.select(CallAudioRoute.BLUETOOTH)

        verify(exactly = 0) { audioManager.setCommunicationDevice(bluetooth) }
        assertEquals(CallAudioRoute.EARPIECE, router.state.value.current)
    }

    @Test
    fun `a pick survives device churn that changes nothing`() {
        router.start()
        router.select(CallAudioRoute.SPEAKER)
        every { audioManager.communicationDevice } returns speaker

        deviceCallback.captured.onAudioDevicesAdded(arrayOf(earpiece))

        verify(exactly = 1) { audioManager.setCommunicationDevice(speaker) }
        verify(exactly = 0) { audioManager.setCommunicationDevice(earpiece) }
    }

    @Test
    fun `select before start does nothing`() {
        router.select(CallAudioRoute.SPEAKER)

        verify(exactly = 0) { audioManager.setCommunicationDevice(any()) }
    }

    // ── stop ─────────────────────────────────────────────────────────────────

    @Test
    fun `stop releases the device and both listeners`() {
        router.start()

        router.stop()

        verify { audioManager.clearCommunicationDevice() }
        verify { audioManager.unregisterAudioDeviceCallback(any()) }
        verify { audioManager.removeOnCommunicationDeviceChangedListener(any()) }
    }

    @Test
    fun `stop is idempotent and stops a never-started router from clearing`() {
        router.stop()
        verify(exactly = 0) { audioManager.clearCommunicationDevice() }

        router.start()
        router.stop()
        router.stop()

        verify(exactly = 1) { audioManager.clearCommunicationDevice() }
    }

    @Test
    fun `stop leaves the published state alone`() {
        devices(earpiece, speaker, bluetooth)
        router.start()
        deviceChangedListener.captured.onCommunicationDeviceChanged(bluetooth)

        router.stop()

        assertEquals(CallAudioRoute.BLUETOOTH, router.state.value.current)
    }

    @Test
    fun `a device change after stop is ignored`() {
        router.start()
        val callback = deviceCallback.captured
        router.stop()

        devices(earpiece, speaker, bluetooth)
        callback.onAudioDevicesAdded(arrayOf(bluetooth))

        verify(exactly = 0) { audioManager.setCommunicationDevice(bluetooth) }
    }

    private fun devices(vararg devices: AudioDeviceInfo) {
        every { audioManager.availableCommunicationDevices } returns devices.toList()
    }

    private fun device(deviceType: Int): AudioDeviceInfo {
        val info: AudioDeviceInfo = mockk()
        every { info.type } returns deviceType
        return info
    }
}
