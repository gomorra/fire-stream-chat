package com.firestream.chat.data.call

import android.media.AudioDeviceCallback
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.os.Handler
import android.util.Log
import com.firestream.chat.domain.model.CallAudioRoute
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.concurrent.Executor

/**
 * Owns the OS side of call audio routing for one call: which devices exist, which one is playing,
 * and the `setCommunicationDevice()` calls that move between them. The *decision* is
 * [CallAudioRoutePolicy]'s; this class only feeds it and applies its answer.
 *
 * Lifecycle is [start] → n × [select] → [stop], each idempotent; [stop] drops the routing state, so
 * a stopped router routes a second call as if it were fresh. [start] must run **after**
 * `AudioManager.mode` is `MODE_IN_COMMUNICATION`, or the OS offers no communication devices at all;
 * [stop] must run on every call-teardown path, or the next media app inherits the 8 kHz SCO link.
 *
 * Threading: both listeners fire on [handler]'s looper (the main one), while [start] / [select] /
 * [stop] arrive from the WebRTC and service threads, so every read-modify-write of the routing
 * bookkeeping happens under [lock]. The only coroutine machinery is [state]; there is no scope to
 * cancel and no timer to stop.
 *
 * @param audioManager the system `AudioManager`.
 * @param handler a main-looper handler; the device callback needs one, and the communication-device
 *   listener's executor is derived from it so both listeners land on the same thread.
 */
class CallAudioRouter(
    private val audioManager: AudioManager,
    private val handler: Handler
) {

    /**
     * @param available every route the OS currently offers, display-ordered by [CallAudioRoute]'s
     *   declaration order.
     * @param current the route the OS reports as *playing* — not the one last requested. Bluetooth
     *   SCO takes about a second to come up, and this stays on the old route until it does. Null
     *   until the OS reports one at all: a guess here would show Bluetooth before it is up, and
     *   would let the policy's rule 3 pin a route the device never actually reached.
     */
    data class RouteState(
        val available: List<CallAudioRoute>,
        val current: CallAudioRoute?
    )

    // Until start() queries the OS, assume the phone every phone is: an earpiece and a speaker.
    private val _state = MutableStateFlow(
        RouteState(listOf(CallAudioRoute.EARPIECE, CallAudioRoute.SPEAKER), current = null)
    )
    val state: StateFlow<RouteState> = _state.asStateFlow()

    private val lock = Any()
    private val executor = Executor { command -> handler.post(command) }

    /** Guarded by [lock]. */
    private var started = false

    /** Guarded by [lock]. The device list the previous policy run saw; empty until the first run. */
    private var previousAvailable: Set<CallAudioRoute> = emptySet()

    /** Guarded by [lock]. The last explicit tap, dropped as soon as the policy overrules it. */
    private var userPick: CallAudioRoute? = null

    /** Guarded by [lock]. What the OS says is playing; null until it first tells us. */
    private var currentRoute: CallAudioRoute? = null

    private val deviceCallback = object : AudioDeviceCallback() {
        // Both arrays describe *all* devices, communication-capable or not, so they are ignored:
        // the policy only ever sees a fresh availableCommunicationDevices query.
        override fun onAudioDevicesAdded(addedDevices: Array<out AudioDeviceInfo>?) = onDeviceListChanged()
        override fun onAudioDevicesRemoved(removedDevices: Array<out AudioDeviceInfo>?) = onDeviceListChanged()
    }

    private val communicationDeviceListener =
        AudioManager.OnCommunicationDeviceChangedListener { device -> onCommunicationDeviceChanged(device) }

    /**
     * Register the listeners and route the call for the first time. A headset that was already
     * connected wins here through the policy's rule 1: [previousAvailable] is still empty.
     */
    fun start() {
        synchronized(lock) {
            if (started) return
            started = true
            audioManager.addOnCommunicationDeviceChangedListener(executor, communicationDeviceListener)
            audioManager.registerAudioDeviceCallback(deviceCallback, handler)
            // registerAudioDeviceCallback replays the current device list, which runs the policy a
            // second time on the main looper; rule 3 makes that re-run a no-op.
            applyPolicy()
        }
    }

    /**
     * Route to [route] because the user asked for it. If the route disappeared between the tap and
     * this call the policy decides instead — `setCommunicationDevice()` for a device that is no
     * longer offered returns false and does nothing, which would strand the call on a route nobody
     * is holding.
     */
    fun select(route: CallAudioRoute) {
        synchronized(lock) {
            if (!started) return
            userPick = route
            val devices = audioManager.availableCommunicationDevices
            if (routeTo(devices, route)) {
                // Deliberately not advancing previousAvailable: a headset that appeared just before
                // this tap still has its rule-1 preemption coming, and it wins (§0).
                publish(devices.routes())
            } else {
                Log.w(TAG, "Route $route vanished before it could be selected")
                applyPolicy(devices)
            }
        }
    }

    /** Hand the audio device back to the system and stop listening. Safe to call twice. */
    fun stop() {
        synchronized(lock) {
            if (!started) return
            started = false
            audioManager.removeOnCommunicationDeviceChangedListener(communicationDeviceListener)
            audioManager.unregisterAudioDeviceCallback(deviceCallback)
            audioManager.clearCommunicationDevice()
            previousAvailable = emptySet()
            userPick = null
            currentRoute = null
        }
    }

    private fun onDeviceListChanged() {
        synchronized(lock) {
            if (!started) return
            applyPolicy()
        }
    }

    private fun onCommunicationDeviceChanged(device: AudioDeviceInfo?) {
        synchronized(lock) {
            if (!started) return
            currentRoute = device?.type?.let(CallAudioRoutePolicy::routeOf)
            _state.value = _state.value.copy(current = currentRoute)
        }
    }

    /** Caller must hold [lock]. */
    private fun applyPolicy(devices: List<AudioDeviceInfo> = audioManager.availableCommunicationDevices) {
        val live = devices.routes()
        val resolved = CallAudioRoutePolicy.resolve(previousAvailable, live, currentRoute, userPick)
        previousAvailable = live
        // Rule 1's side effect: a pick the policy just overruled — preempted by a new headset, or
        // unplugged — is spent, and must not resurrect on the next device change.
        if (resolved != userPick) userPick = null

        if (!routeTo(devices, resolved)) {
            Log.w(TAG, "Policy chose $resolved but the OS offers no device for it")
        }
        publish(live)
    }

    /**
     * Ask the OS for [route], and report whether [devices] could serve it at all. Caller must hold
     * [lock] and must have queried [devices] inside it: `setCommunicationDevice()` silently returns
     * false for a device that is no longer offered.
     */
    private fun routeTo(devices: List<AudioDeviceInfo>, route: CallAudioRoute): Boolean {
        val device = devices.firstOrNull { CallAudioRoutePolicy.routeOf(it.type) == route } ?: return false
        audioManager.setCommunicationDevice(device)
        return true
    }

    /** Caller must hold [lock]. */
    private fun publish(live: Set<CallAudioRoute>) {
        _state.value = RouteState(available = live.sortedBy { it.ordinal }, current = currentRoute)
    }

    private fun List<AudioDeviceInfo>.routes(): Set<CallAudioRoute> =
        mapNotNullTo(mutableSetOf()) { CallAudioRoutePolicy.routeOf(it.type) }

    private companion object {
        const val TAG = "CallAudioRouter"
    }
}
