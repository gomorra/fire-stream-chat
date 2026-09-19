package com.firestream.chat.data.call

import com.firestream.chat.domain.model.CallAudioRoute

/**
 * Decides which audio route a call should be on. Pure: no Android imports, so the whole
 * decision table is unit-tested on the JVM. The Android side only feeds it device lists
 * and applies the result.
 */
object CallAudioRoutePolicy {

    // Values of the AudioDeviceInfo.TYPE_* constants, copied so this file stays Android-free.
    private const val TYPE_BUILTIN_EARPIECE = 1
    private const val TYPE_BUILTIN_SPEAKER = 2
    private const val TYPE_WIRED_HEADSET = 3
    private const val TYPE_WIRED_HEADPHONES = 4
    private const val TYPE_BLUETOOTH_SCO = 7
    private const val TYPE_USB_DEVICE = 11
    private const val TYPE_USB_HEADSET = 22
    private const val TYPE_BLE_HEADSET = 26

    private val autoPreference = listOf(
        CallAudioRoute.BLUETOOTH,
        CallAudioRoute.WIRED_HEADSET,
        CallAudioRoute.EARPIECE,
        CallAudioRoute.SPEAKER,
    )

    /**
     * @param current what the OS reports as active, null before the first pick
     * @param userPick the last explicit tap, null = none. The caller clears it when the
     *   result differs from it (a newly connected headset preempts the pick).
     */
    fun resolve(
        previousAvailable: Set<CallAudioRoute>,
        available: Set<CallAudioRoute>,
        current: CallAudioRoute?,
        userPick: CallAudioRoute?,
    ): CallAudioRoute {
        // A headset that just appeared always wins, Bluetooth over wired.
        val appeared = available - previousAvailable
        if (CallAudioRoute.BLUETOOTH in appeared) return CallAudioRoute.BLUETOOTH
        if (CallAudioRoute.WIRED_HEADSET in appeared) return CallAudioRoute.WIRED_HEADSET

        if (userPick != null && userPick in available) return userPick
        if (current != null && current in available) return current

        // SPEAKER is last, so a headset disconnect lands on the earpiece; only a device
        // without one (a tablet) ends up on the speaker.
        return autoPreference.firstOrNull { it in available } ?: CallAudioRoute.EARPIECE
    }

    /** Maps an `AudioDeviceInfo.TYPE_*` int to a route, null for a type calls do not use. */
    fun routeOf(type: Int): CallAudioRoute? = when (type) {
        TYPE_BUILTIN_EARPIECE -> CallAudioRoute.EARPIECE
        TYPE_BUILTIN_SPEAKER -> CallAudioRoute.SPEAKER
        TYPE_BLUETOOTH_SCO, TYPE_BLE_HEADSET -> CallAudioRoute.BLUETOOTH
        TYPE_WIRED_HEADSET, TYPE_WIRED_HEADPHONES, TYPE_USB_HEADSET, TYPE_USB_DEVICE ->
            CallAudioRoute.WIRED_HEADSET
        else -> null
    }
}
