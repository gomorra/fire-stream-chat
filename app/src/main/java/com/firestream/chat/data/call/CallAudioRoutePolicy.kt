package com.firestream.chat.data.call

import android.media.AudioDeviceInfo
import com.firestream.chat.domain.model.CallAudioRoute

/**
 * Pure decision logic for call audio routing. No state, no `AudioManager`, no coroutines —
 * everything the OS reports comes in as a parameter so the whole policy is unit-testable.
 *
 * The Android side (`CallAudioRouter`, step 3) owns the listeners and the
 * `setCommunicationDevice()` call; it holds `previousAvailable` and `userPick` and asks this
 * object what the route should be.
 */
object CallAudioRoutePolicy {

    /** Routes that preempt whatever is playing when they appear mid-call. */
    private val HEADSET_ROUTES = setOf(CallAudioRoute.BLUETOOTH, CallAudioRoute.WIRED_HEADSET)

    /** Fallback preference when nothing else decides. SPEAKER last: a disconnect lands on the earpiece. */
    private val AUTO_PREFERENCE = listOf(
        CallAudioRoute.BLUETOOTH,
        CallAudioRoute.WIRED_HEADSET,
        CallAudioRoute.EARPIECE,
        CallAudioRoute.SPEAKER
    )

    /**
     * Resolve the route to use.
     *
     * Rules, in priority order:
     * 1. A **headset** route present in [available] but not in [previousAvailable] wins — the
     *    "plugged in mid-call" preemption, Bluetooth first if both appeared at once. The caller
     *    clears its stored user pick when the result differs from [userPick].
     * 2. Else [userPick], if it is still available.
     * 3. Else [current], if it is still available — nothing changed, stay put. This is what makes
     *    the policy idempotent, so re-running it on an unchanged device list is a no-op.
     * 4. Else [AUTO_PREFERENCE].
     *
     * @param previousAvailable the device list the last call to this function saw; empty at call start.
     * @param available what the OS offers right now.
     * @param current the route the OS reports as active, null before the first pick.
     * @param userPick the last explicit tap, null if the user has not chosen.
     */
    fun resolve(
        previousAvailable: Set<CallAudioRoute>,
        available: Set<CallAudioRoute>,
        current: CallAudioRoute?,
        userPick: CallAudioRoute?
    ): CallAudioRoute {
        AUTO_PREFERENCE.firstOrNull { it in HEADSET_ROUTES && it in available && it !in previousAvailable }
            ?.let { return it }

        if (userPick != null && userPick in available) return userPick
        if (current != null && current in available) return current

        // `available` is empty only if the OS hands us nothing at all; stay where we are rather
        // than jumping to speaker.
        return AUTO_PREFERENCE.firstOrNull { it in available } ?: current ?: CallAudioRoute.EARPIECE
    }

    /**
     * Map an [AudioDeviceInfo] device type to a route, or null for a type calls cannot use.
     *
     * Takes the raw `Int` rather than an `AudioDeviceInfo` so it stays a pure function: the
     * `TYPE_*` constants are Java compile-time constants and inline, so no Android class is
     * loaded when this runs on the JVM.
     */
    fun routeOf(type: Int): CallAudioRoute? = when (type) {
        AudioDeviceInfo.TYPE_BUILTIN_EARPIECE -> CallAudioRoute.EARPIECE
        AudioDeviceInfo.TYPE_BUILTIN_SPEAKER -> CallAudioRoute.SPEAKER
        AudioDeviceInfo.TYPE_BLUETOOTH_SCO,
        AudioDeviceInfo.TYPE_BLE_HEADSET -> CallAudioRoute.BLUETOOTH
        AudioDeviceInfo.TYPE_WIRED_HEADSET,
        AudioDeviceInfo.TYPE_WIRED_HEADPHONES,
        AudioDeviceInfo.TYPE_USB_HEADSET,
        AudioDeviceInfo.TYPE_USB_DEVICE -> CallAudioRoute.WIRED_HEADSET
        else -> null
    }
}
