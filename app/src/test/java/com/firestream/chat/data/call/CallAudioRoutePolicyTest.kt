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

class CallAudioRoutePolicyTest {

    private val phone = setOf(EARPIECE, SPEAKER)

    private fun resolve(
        previous: Set<CallAudioRoute>,
        available: Set<CallAudioRoute>,
        current: CallAudioRoute? = null,
        userPick: CallAudioRoute? = null,
    ) = CallAudioRoutePolicy.resolve(previous, available, current, userPick)

    @Test
    fun `default phone with no pick starts on the earpiece`() {
        assertEquals(EARPIECE, resolve(emptySet(), phone))
    }

    @Test
    fun `bluetooth present at start wins`() {
        assertEquals(BLUETOOTH, resolve(emptySet(), phone + BLUETOOTH))
    }

    @Test
    fun `bluetooth appearing mid-call preempts a speaker pick`() {
        assertEquals(
            BLUETOOTH,
            resolve(phone, phone + BLUETOOTH, current = SPEAKER, userPick = SPEAKER),
        )
    }

    @Test
    fun `wired appearing while on bluetooth preempts it`() {
        assertEquals(
            WIRED_HEADSET,
            resolve(phone + BLUETOOTH, phone + BLUETOOTH + WIRED_HEADSET, current = BLUETOOTH),
        )
    }

    @Test
    fun `bluetooth and wired appearing together resolve to bluetooth`() {
        assertEquals(
            BLUETOOTH,
            resolve(phone, phone + BLUETOOTH + WIRED_HEADSET, current = EARPIECE),
        )
    }

    @Test
    fun `an earpiece pick survives a re-emit of the same list with bluetooth connected`() {
        val withBt = phone + BLUETOOTH
        assertEquals(EARPIECE, resolve(withBt, withBt, current = EARPIECE, userPick = EARPIECE))
        // Even while the OS still reports Bluetooth during the switch.
        assertEquals(EARPIECE, resolve(withBt, withBt, current = BLUETOOTH, userPick = EARPIECE))
    }

    @Test
    fun `bluetooth disconnecting while active falls back to the earpiece not the speaker`() {
        assertEquals(EARPIECE, resolve(phone + BLUETOOTH, phone, current = BLUETOOTH))
        // A pick of the vanished route does not count either.
        assertEquals(
            EARPIECE,
            resolve(phone + BLUETOOTH, phone, current = BLUETOOTH, userPick = BLUETOOTH),
        )
    }

    @Test
    fun `a speaker pick is honoured through unrelated device churn`() {
        assertEquals(SPEAKER, resolve(phone, phone, current = SPEAKER, userPick = SPEAKER))
        assertEquals(SPEAKER, resolve(phone, phone, current = EARPIECE, userPick = SPEAKER))
    }

    @Test
    fun `without a pick the current route stays put`() {
        assertEquals(SPEAKER, resolve(phone, phone, current = SPEAKER))
    }

    @Test
    fun `a tablet with only a speaker lands on the speaker`() {
        assertEquals(SPEAKER, resolve(emptySet(), setOf(SPEAKER)))
    }

    @Test
    fun `routeOf maps every communication device type`() {
        val expected = mapOf(
            AudioDeviceInfo.TYPE_BUILTIN_EARPIECE to EARPIECE,
            AudioDeviceInfo.TYPE_BUILTIN_SPEAKER to SPEAKER,
            AudioDeviceInfo.TYPE_BLUETOOTH_SCO to BLUETOOTH,
            AudioDeviceInfo.TYPE_BLE_HEADSET to BLUETOOTH,
            AudioDeviceInfo.TYPE_WIRED_HEADSET to WIRED_HEADSET,
            AudioDeviceInfo.TYPE_WIRED_HEADPHONES to WIRED_HEADSET,
            AudioDeviceInfo.TYPE_USB_HEADSET to WIRED_HEADSET,
            AudioDeviceInfo.TYPE_USB_DEVICE to WIRED_HEADSET,
        )
        expected.forEach { (type, route) ->
            assertEquals("type $type", route, CallAudioRoutePolicy.routeOf(type))
        }
    }

    @Test
    fun `routeOf ignores types calls do not use`() {
        assertNull(CallAudioRoutePolicy.routeOf(AudioDeviceInfo.TYPE_BLUETOOTH_A2DP))
        assertNull(CallAudioRoutePolicy.routeOf(AudioDeviceInfo.TYPE_TELEPHONY))
        assertNull(CallAudioRoutePolicy.routeOf(AudioDeviceInfo.TYPE_UNKNOWN))
    }
}
