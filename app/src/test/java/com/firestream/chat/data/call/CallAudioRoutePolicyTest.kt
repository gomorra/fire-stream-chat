package com.firestream.chat.data.call

import android.media.AudioDeviceInfo
import com.firestream.chat.domain.model.CallAudioRoute
import com.firestream.chat.domain.model.CallAudioRoute.BLUETOOTH
import com.firestream.chat.domain.model.CallAudioRoute.EARPIECE
import com.firestream.chat.domain.model.CallAudioRoute.SPEAKER
import com.firestream.chat.domain.model.CallAudioRoute.WIRED_HEADSET
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Pure policy tests — no Robolectric. The `AudioDeviceInfo.TYPE_*` constants used here are Java
 * compile-time constants, so they inline and no Android class is ever loaded.
 */
class CallAudioRoutePolicyTest {

    private val phone = setOf(EARPIECE, SPEAKER)

    @Test
    fun `default phone with no pick stays on the earpiece`() {
        assertEquals(
            EARPIECE,
            CallAudioRoutePolicy.resolve(
                previousAvailable = emptySet(),
                available = phone,
                current = null,
                userPick = null
            )
        )
    }

    @Test
    fun `bluetooth connected before the call wins at start`() {
        // Call start: nothing seen before, the OS has not picked yet, the user has not tapped.
        assertEquals(
            BLUETOOTH,
            CallAudioRoutePolicy.resolve(
                previousAvailable = emptySet(),
                available = phone + BLUETOOTH,
                current = null,
                userPick = null
            )
        )
    }

    @Test
    fun `bluetooth appearing mid-call preempts an explicit speaker pick`() {
        assertEquals(
            BLUETOOTH,
            CallAudioRoutePolicy.resolve(
                previousAvailable = phone,
                available = phone + BLUETOOTH,
                current = SPEAKER,
                userPick = SPEAKER
            )
        )
    }

    @Test
    fun `a wired headset plugged in while on bluetooth preempts bluetooth`() {
        assertEquals(
            WIRED_HEADSET,
            CallAudioRoutePolicy.resolve(
                previousAvailable = phone + BLUETOOTH,
                available = phone + BLUETOOTH + WIRED_HEADSET,
                current = BLUETOOTH,
                userPick = null
            )
        )
    }

    @Test
    fun `bluetooth wins when both headsets appear at once`() {
        assertEquals(
            BLUETOOTH,
            CallAudioRoutePolicy.resolve(
                previousAvailable = phone,
                available = phone + BLUETOOTH + WIRED_HEADSET,
                current = EARPIECE,
                userPick = null
            )
        )
    }

    @Test
    fun `a re-emit of an unchanged device list does not re-fire the headset preemption`() {
        assertEquals(
            EARPIECE,
            CallAudioRoutePolicy.resolve(
                previousAvailable = phone + BLUETOOTH,
                available = phone + BLUETOOTH,
                current = EARPIECE,
                userPick = EARPIECE
            )
        )
    }

    @Test
    fun `a speaker that only enumerates late does not preempt anything`() {
        assertEquals(
            EARPIECE,
            CallAudioRoutePolicy.resolve(
                previousAvailable = setOf(EARPIECE),
                available = phone,
                current = EARPIECE,
                userPick = null
            )
        )
    }

    @Test
    fun `a bluetooth disconnect falls back to the earpiece, never the speaker`() {
        assertEquals(
            EARPIECE,
            CallAudioRoutePolicy.resolve(
                previousAvailable = phone + BLUETOOTH,
                available = phone,
                current = BLUETOOTH,
                userPick = null
            )
        )
    }

    @Test
    fun `an explicit speaker pick survives unrelated device list churn`() {
        assertEquals(
            SPEAKER,
            CallAudioRoutePolicy.resolve(
                previousAvailable = phone + BLUETOOTH,
                available = phone,
                current = SPEAKER,
                userPick = SPEAKER
            )
        )
    }

    @Test
    fun `a tablet without an earpiece lands on the speaker`() {
        assertEquals(
            SPEAKER,
            CallAudioRoutePolicy.resolve(
                previousAvailable = emptySet(),
                available = setOf(SPEAKER),
                current = null,
                userPick = null
            )
        )
    }

    @Test
    fun `an empty device list keeps the current route`() {
        assertEquals(
            SPEAKER,
            CallAudioRoutePolicy.resolve(
                previousAvailable = phone,
                available = emptySet(),
                current = SPEAKER,
                userPick = null
            )
        )
    }

    @Test
    fun `an empty device list with no current route falls back to the earpiece`() {
        assertEquals(
            EARPIECE,
            CallAudioRoutePolicy.resolve(
                previousAvailable = emptySet(),
                available = emptySet(),
                current = null,
                userPick = null
            )
        )
    }

    @Test
    fun `routeOf maps every device type calls can use`() {
        val expected = mapOf(
            AudioDeviceInfo.TYPE_BUILTIN_EARPIECE to EARPIECE,
            AudioDeviceInfo.TYPE_BUILTIN_SPEAKER to SPEAKER,
            AudioDeviceInfo.TYPE_BLUETOOTH_SCO to BLUETOOTH,
            AudioDeviceInfo.TYPE_BLE_HEADSET to BLUETOOTH,
            AudioDeviceInfo.TYPE_WIRED_HEADSET to WIRED_HEADSET,
            AudioDeviceInfo.TYPE_WIRED_HEADPHONES to WIRED_HEADSET,
            AudioDeviceInfo.TYPE_USB_HEADSET to WIRED_HEADSET,
            AudioDeviceInfo.TYPE_USB_DEVICE to WIRED_HEADSET
        )
        expected.forEach { (type, route: CallAudioRoute) ->
            assertEquals("type $type", route, CallAudioRoutePolicy.routeOf(type))
        }
    }

    @Test
    fun `routeOf ignores device types calls cannot use`() {
        // A2DP-only headphones and HDMI are never communication devices.
        assertNull(CallAudioRoutePolicy.routeOf(AudioDeviceInfo.TYPE_BLUETOOTH_A2DP))
        assertNull(CallAudioRoutePolicy.routeOf(AudioDeviceInfo.TYPE_HDMI))
        assertNull(CallAudioRoutePolicy.routeOf(AudioDeviceInfo.TYPE_UNKNOWN))
    }
}
