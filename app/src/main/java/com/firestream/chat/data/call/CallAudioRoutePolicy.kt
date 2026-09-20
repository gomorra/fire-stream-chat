package com.firestream.chat.data.call

import com.firestream.chat.domain.model.CallAudioRoute

/**
 * Decides which audio route a call should be on. Pure: no Android imports, so the whole
 * decision table runs on the JVM (`CallAudioRoutePolicyTest`). `CallAudioRouter` feeds it the
 * device list and applies the answer.
 */
object CallAudioRoutePolicy {

    // AudioDeviceInfo.TYPE_* values, copied so this file stays Android-free. The test pins
    // them against the framework constants.
    private const val TYPE_BUILTIN_EARPIECE = 1
    private const val TYPE_BUILTIN_SPEAKER = 2
    private const val TYPE_WIRED_HEADSET = 3
    private const val TYPE_WIRED_HEADPHONES = 4
    private const val TYPE_BLUETOOTH_SCO = 7
    private const val TYPE_USB_DEVICE = 11
    private const val TYPE_USB_HEADSET = 22
    private const val TYPE_BLE_HEADSET = 26

    /** Headsets first, and the earpiece before the speaker: a disconnect never lands on speaker. */
    private val autoPreference = listOf(
        CallAudioRoute.BLUETOOTH,
        CallAudioRoute.WIRED_HEADSET,
        CallAudioRoute.EARPIECE,
        CallAudioRoute.SPEAKER
    )

    /**
     * @param previousAvailable the routes of the previous run, empty on the first one
     * @param current what the OS reports as active, null before the first pick
     * @param userPick the last explicit tap, null = none. The caller clears it when the result
     *   differs from it (a headset that connected mid-call has preempted the pick).
     */
    fun resolve(
        previousAvailable: Set<CallAudioRoute>,
        available: Set<CallAudioRoute>,
        current: CallAudioRoute?,
        userPick: CallAudioRoute?
    ): CallAudioRoute {
        val appeared = available - previousAvailable
        if (CallAudioRoute.BLUETOOTH in appeared) return CallAudioRoute.BLUETOOTH
        if (CallAudioRoute.WIRED_HEADSET in appeared) return CallAudioRoute.WIRED_HEADSET
        if (userPick != null && userPick in available) return userPick
        if (current != null && current in available) return current
        return autoPreference.firstOrNull { it in available } ?: CallAudioRoute.EARPIECE
    }

    /** Maps an `AudioDeviceInfo.getType()` value to a route; null = not a call route, ignore. */
    fun routeOf(type: Int): CallAudioRoute? = when (type) {
        TYPE_BUILTIN_EARPIECE -> CallAudioRoute.EARPIECE
        TYPE_BUILTIN_SPEAKER -> CallAudioRoute.SPEAKER
        TYPE_BLUETOOTH_SCO, TYPE_BLE_HEADSET -> CallAudioRoute.BLUETOOTH
        TYPE_WIRED_HEADSET, TYPE_WIRED_HEADPHONES, TYPE_USB_HEADSET, TYPE_USB_DEVICE ->
            CallAudioRoute.WIRED_HEADSET
        else -> null
    }
}
