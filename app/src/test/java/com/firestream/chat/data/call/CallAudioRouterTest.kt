package com.firestream.chat.data.call

import android.media.AudioDeviceCallback
import android.media.AudioDeviceInfo
import android.media.AudioManager
import com.firestream.chat.data.call.CallAudioRouter.RouteState
import com.firestream.chat.domain.model.CallAudioRoute.BLUETOOTH
import com.firestream.chat.domain.model.CallAudioRoute.EARPIECE
import com.firestream.chat.domain.model.CallAudioRoute.SPEAKER
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The router's sequencing — what it remembers between device callbacks — against a mocked
 * [AudioManager]. Which route wins for one given input is [CallAudioRoutePolicyTest]'s job.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [31], manifest = Config.NONE, application = android.app.Application::class)
class CallAudioRouterTest {

    private val earpiece = device(AudioDeviceInfo.TYPE_BUILTIN_EARPIECE)
    private val speaker = device(AudioDeviceInfo.TYPE_BUILTIN_SPEAKER)
    private val bluetooth = device(AudioDeviceInfo.TYPE_BLUETOOTH_SCO)

    private val audioManager = mockk<AudioManager>(relaxed = true)
    private val deviceCallback = slot<AudioDeviceCallback>()
    private val communicationListener = slot<AudioManager.OnCommunicationDeviceChangedListener>()
    private var devices = listOf(earpiece, speaker)

    private lateinit var router: CallAudioRouter

    @Before
    fun setUp() {
        every { audioManager.availableCommunicationDevices } answers { devices }
        every { audioManager.communicationDevice } returns null
        every { audioManager.setCommunicationDevice(any()) } returns true
        every { audioManager.registerAudioDeviceCallback(capture(deviceCallback), any()) } returns Unit
        every {
            audioManager.addOnCommunicationDeviceChangedListener(any(), capture(communicationListener))
        } returns Unit
        router = CallAudioRouter(audioManager) { it.run() }
    }

    @Test
    fun `a plain phone starts on the earpiece`() {
        router.start()

        verify(exactly = 1) { audioManager.setCommunicationDevice(earpiece) }
        assertEquals(RouteState(listOf(EARPIECE, SPEAKER), EARPIECE), router.state.value)
    }

    @Test
    fun `a Bluetooth request that is still coming up survives the registration callback`() {
        devices = listOf(earpiece, speaker, bluetooth)
        router.start()

        // The OS has not switched yet; the callback that follows registration re-runs the policy.
        deviceCallback.captured.onAudioDevicesAdded(arrayOf(earpiece, speaker, bluetooth))

        verify(exactly = 1) { audioManager.setCommunicationDevice(any()) }
        verify { audioManager.setCommunicationDevice(bluetooth) }
        assertEquals(EARPIECE, router.state.value.current)

        communicationListener.captured.onCommunicationDeviceChanged(bluetooth)
        assertEquals(BLUETOOTH, router.state.value.current)
    }

    @Test
    fun `a refused headset is asked for again on the next device callback`() {
        router.start()
        devices = listOf(earpiece, speaker, bluetooth)
        every { audioManager.setCommunicationDevice(bluetooth) } returns false
        deviceCallback.captured.onAudioDevicesAdded(arrayOf(bluetooth))

        every { audioManager.setCommunicationDevice(bluetooth) } returns true
        deviceCallback.captured.onAudioDevicesAdded(arrayOf(bluetooth))

        verify(exactly = 2) { audioManager.setCommunicationDevice(bluetooth) }
    }

    @Test
    fun `a refused tap keeps the pick for the next device callback`() {
        router.start()
        every { audioManager.setCommunicationDevice(speaker) } returns false
        router.select(SPEAKER)

        every { audioManager.setCommunicationDevice(speaker) } returns true
        deviceCallback.captured.onAudioDevicesRemoved(emptyArray())

        verify(exactly = 2) { audioManager.setCommunicationDevice(speaker) }
    }

    @Test
    fun `a headset that connects mid-call beats the pick, and its disconnect lands on the earpiece`() {
        router.start()
        router.select(SPEAKER)

        devices = listOf(earpiece, speaker, bluetooth)
        deviceCallback.captured.onAudioDevicesAdded(arrayOf(bluetooth))
        verify { audioManager.setCommunicationDevice(bluetooth) }

        devices = listOf(earpiece, speaker)
        deviceCallback.captured.onAudioDevicesRemoved(arrayOf(bluetooth))

        // The speaker pick was cleared by the preemption, so it is the earpiece, asked for a second time.
        verify(exactly = 2) { audioManager.setCommunicationDevice(earpiece) }
        assertEquals(listOf(EARPIECE, SPEAKER), router.state.value.available)
    }

    @Test
    fun `a pick of the earpiece holds while the headset stays connected`() {
        devices = listOf(earpiece, speaker, bluetooth)
        router.start()
        router.select(EARPIECE)

        deviceCallback.captured.onAudioDevicesAdded(arrayOf(bluetooth))

        verify(exactly = 1) { audioManager.setCommunicationDevice(bluetooth) }
        verify(exactly = 1) { audioManager.setCommunicationDevice(earpiece) }
    }

    @Test
    fun `the OS dropping the communication device re-applies the route`() {
        router.start()

        communicationListener.captured.onCommunicationDeviceChanged(null)

        verify(exactly = 2) { audioManager.setCommunicationDevice(earpiece) }
    }

    @Test
    fun `stop clears the communication device and drops callbacks that were already queued`() {
        router.start()
        router.stop()
        router.stop()

        devices = listOf(earpiece, speaker, bluetooth)
        deviceCallback.captured.onAudioDevicesAdded(arrayOf(bluetooth))
        communicationListener.captured.onCommunicationDeviceChanged(bluetooth)
        router.select(SPEAKER)

        verify(exactly = 1) { audioManager.clearCommunicationDevice() }
        verify(exactly = 1) { audioManager.setCommunicationDevice(any()) }
        assertEquals(EARPIECE, router.state.value.current)
    }

    private fun device(type: Int): AudioDeviceInfo = mockk { every { this@mockk.type } returns type }
}
