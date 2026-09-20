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
        userPick: CallAudioRoute? = null
    ) = CallAudioRoutePolicy.resolve(previous, available, current, userPick)

    @Test
    fun `default phone with no pick starts on the earpiece`() {
        assertEquals(EARPIECE, resolve(previous = emptySet(), available = phone))
    }

    @Test
    fun `bluetooth present at call start wins`() {
        assertEquals(BLUETOOTH, resolve(previous = emptySet(), available = phone + BLUETOOTH))
    }

    @Test
    fun `wired headset present at call start wins`() {
        assertEquals(WIRED_HEADSET, resolve(previous = emptySet(), available = phone + WIRED_HEADSET))
    }

    @Test
    fun `bluetooth appearing mid-call preempts a speaker pick`() {
        assertEquals(
            BLUETOOTH,
            resolve(previous = phone, available = phone + BLUETOOTH, current = SPEAKER, userPick = SPEAKER)
        )
    }

    @Test
    fun `wired headset appearing while on bluetooth preempts it`() {
        assertEquals(
            WIRED_HEADSET,
            resolve(
                previous = phone + BLUETOOTH,
                available = phone + BLUETOOTH + WIRED_HEADSET,
                current = BLUETOOTH
            )
        )
    }

    @Test
    fun `bluetooth and wired appearing together resolve to bluetooth`() {
        assertEquals(
            BLUETOOTH,
            resolve(previous = phone, available = phone + BLUETOOTH + WIRED_HEADSET, current = EARPIECE)
        )
    }

    @Test
    fun `earpiece pick survives a re-emit of an unchanged list with bluetooth connected`() {
        val withBt = phone + BLUETOOTH
        assertEquals(
            EARPIECE,
            resolve(previous = withBt, available = withBt, current = EARPIECE, userPick = EARPIECE)
        )
        // The OS still reports Bluetooth while the switch is in flight — the pick holds.
        assertEquals(
            EARPIECE,
            resolve(previous = withBt, available = withBt, current = BLUETOOTH, userPick = EARPIECE)
        )
    }

    @Test
    fun `bluetooth disconnecting while active falls back to the earpiece, not the speaker`() {
        assertEquals(
            EARPIECE,
            resolve(previous = phone + BLUETOOTH, available = phone, current = BLUETOOTH)
        )
        // Same when Bluetooth was the explicit pick.
        assertEquals(
            EARPIECE,
            resolve(previous = phone + BLUETOOTH, available = phone, current = BLUETOOTH, userPick = BLUETOOTH)
        )
    }

    @Test
    fun `speaker pick is honoured through unrelated device churn`() {
        assertEquals(SPEAKER, resolve(previous = phone, available = phone, current = SPEAKER, userPick = SPEAKER))
        // A headset going away is not a new headset.
        assertEquals(
            SPEAKER,
            resolve(previous = phone + WIRED_HEADSET, available = phone, current = SPEAKER, userPick = SPEAKER)
        )
    }

    @Test
    fun `no pick and an unchanged list stays on the current route`() {
        assertEquals(SPEAKER, resolve(previous = phone, available = phone, current = SPEAKER))
    }

    @Test
    fun `tablet with only a speaker resolves to the speaker`() {
        assertEquals(SPEAKER, resolve(previous = emptySet(), available = setOf(SPEAKER)))
    }

    @Test
    fun `one headset leaving falls back to the other`() {
        assertEquals(
            WIRED_HEADSET,
            resolve(
                previous = phone + BLUETOOTH + WIRED_HEADSET,
                available = phone + WIRED_HEADSET,
                current = BLUETOOTH
            )
        )
    }

    @Test
    fun `routeOf maps every call-capable device type`() {
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
        expected.forEach { (type, route) ->
            assertEquals("type $type", route, CallAudioRoutePolicy.routeOf(type))
        }
    }

    @Test
    fun `routeOf ignores device types that cannot carry a call`() {
        assertNull(CallAudioRoutePolicy.routeOf(AudioDeviceInfo.TYPE_BLUETOOTH_A2DP))
        assertNull(CallAudioRoutePolicy.routeOf(AudioDeviceInfo.TYPE_HDMI))
        assertNull(CallAudioRoutePolicy.routeOf(AudioDeviceInfo.TYPE_UNKNOWN))
        assertNull(CallAudioRoutePolicy.routeOf(-1))
    }
}
