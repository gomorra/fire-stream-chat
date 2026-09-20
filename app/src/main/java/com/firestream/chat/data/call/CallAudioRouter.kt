package com.firestream.chat.data.call

import android.media.AudioDeviceCallback
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.util.Log
import com.firestream.chat.domain.model.CallAudioRoute
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.concurrent.Executor

/**
 * What the call's audio routing looks like right now.
 *
 * [current] is what the OS says it is routing to — never what was last requested, so Bluetooth
 * appears here only once SCO is actually carrying the call. It is null while the OS names nothing
 * calls can use: before the first pick, during the beat between a headset vanishing and its
 * replacement being selected, and while a cellular call holds the device. A route is never
 * published unless it is in [available].
 */
data class CallRouteState(
    val available: Set<CallAudioRoute> = emptySet(),
    val current: CallAudioRoute? = null,
)

/**
 * Selects the output device a call's audio goes to, via `AudioManager.setCommunicationDevice()`.
 * Every decision is [CallAudioRoutePolicy]'s; this class only feeds it device lists and applies
 * the answer.
 *
 * Owned by [CallService] for the length of one call: [start] once the audio mode is
 * `MODE_IN_COMMUNICATION`, [stop] before it is restored. [stop] must run on every exit path —
 * without `clearCommunicationDevice()` the next media app is stuck on the 8 kHz SCO link.
 *
 * Threading: [start], [select] and [stop] arrive on whatever thread the service is on (ICE state
 * changes come in on WebRTC's signalling thread), while both listeners fire on [callbackExecutor]'s
 * thread, so the mutable bookkeeping is guarded by this object's monitor. [state] is the only thing
 * that leaves it.
 */
class CallAudioRouter(
    private val audioManager: AudioManager,
    /** Must be the main thread's: see the plan's trap list. */
    private val callbackExecutor: Executor,
) {

    private val _state = MutableStateFlow(CallRouteState())
    val state: StateFlow<CallRouteState> = _state.asStateFlow()

    private var started = false

    /** The last explicit tap, cleared as soon as the policy overrules it. */
    private var userPick: CallAudioRoute? = null

    // Reports every device on the phone, communication-capable or not, so the list is re-queried
    // rather than read out of the callback's array.
    private val deviceCallback = object : AudioDeviceCallback() {
        override fun onAudioDevicesAdded(addedDevices: Array<out AudioDeviceInfo>?) = refresh()
        override fun onAudioDevicesRemoved(removedDevices: Array<out AudioDeviceInfo>?) = refresh()
    }

    private val communicationDeviceListener =
        AudioManager.OnCommunicationDeviceChangedListener { device -> onCurrentDeviceChanged(device) }

    @Synchronized
    fun start() {
        if (started) return
        started = true
        userPick = null
        audioManager.addOnCommunicationDeviceChangedListener(callbackExecutor, communicationDeviceListener)
        // A null handler means the main looper's, which is the thread callbackExecutor posts to.
        audioManager.registerAudioDeviceCallback(deviceCallback, null)
        // Registering fires the device callback once with the current list, but that arrives a main
        // thread hop later; the call's audio must be on the right device before then.
        refresh()
    }

    /** Applies an explicit tap. A route that vanished in between falls back to the policy. */
    @Synchronized
    fun select(route: CallAudioRoute) {
        if (!started) return
        userPick = route
        if (!applyRoute(route)) refresh()
    }

    @Synchronized
    fun stop() {
        if (!started) return
        started = false
        audioManager.removeOnCommunicationDeviceChangedListener(communicationDeviceListener)
        audioManager.unregisterAudioDeviceCallback(deviceCallback)
        audioManager.clearCommunicationDevice()
        userPick = null
        _state.value = CallRouteState()
    }

    @Synchronized
    private fun refresh() {
        if (!started) return
        // The published set is also what the policy last saw, so whatever is in `available` but not
        // in it is a device that just appeared.
        val previous = _state.value
        val available = availableRoutes()
        val current = currentRoute()
        val resolved = CallAudioRoutePolicy.resolve(previous.available, available, current, userPick)
        if (resolved != userPick) userPick = null
        publish(available, current)
        if (resolved != current) applyRoute(resolved)
    }

    @Synchronized
    private fun onCurrentDeviceChanged(device: AudioDeviceInfo?) {
        if (!started) return
        if (device == null) {
            // Nothing is selected any more — the active headset was unplugged, say. That is a route
            // change like any other, so re-run the policy instead of leaving a dead route published.
            refresh()
            return
        }
        // A device calls do not model (TYPE_TELEPHONY while a cellular call interrupts) is left
        // alone: the last known route stays published rather than fighting the system for it.
        val route = CallAudioRoutePolicy.routeOf(device.type) ?: return
        publish(_state.value.available, route)
    }

    /**
     * The only writer of [state]. [osRoute] is what the OS reports, null when it names nothing
     * calls can use, in which case the last known route stands — but either way only while that
     * route still exists. Publishing one that does not would leave the UI offering a device that is
     * gone and tell the proximity lock the call had left the earpiece when a disconnect has just
     * put it back on it.
     */
    private fun publish(available: Set<CallAudioRoute>, osRoute: CallAudioRoute?) {
        _state.value = CallRouteState(
            available = available,
            current = (osRoute ?: _state.value.current)?.takeIf { it in available },
        )
    }

    /**
     * `setCommunicationDevice` refuses anything that is not in the list *at that moment*, so the
     * list is re-queried here rather than carried in from the caller — a tap can arrive long after
     * the list that justified it was read.
     */
    private fun applyRoute(route: CallAudioRoute): Boolean {
        val device = audioManager.availableCommunicationDevices
            .firstOrNull { CallAudioRoutePolicy.routeOf(it.type) == route }
        if (device == null) {
            Log.w(TAG, "Route $route is gone, falling back to the policy")
            return false
        }
        val applied = audioManager.setCommunicationDevice(device)
        if (!applied) Log.w(TAG, "AudioManager refused $route")
        return applied
    }

    private fun availableRoutes(): Set<CallAudioRoute> =
        audioManager.availableCommunicationDevices
            .mapNotNullTo(mutableSetOf()) { CallAudioRoutePolicy.routeOf(it.type) }

    private fun currentRoute(): CallAudioRoute? =
        audioManager.communicationDevice?.type?.let(CallAudioRoutePolicy::routeOf)

    private companion object {
        const val TAG = "CallAudioRouter"
    }
}
