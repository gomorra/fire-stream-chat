package com.firestream.chat.data.call

import android.media.AudioDeviceCallback
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.util.Log
import com.firestream.chat.domain.model.CallAudioRoute
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import java.util.concurrent.Executor

/**
 * Routes call audio with `AudioManager.setCommunicationDevice()`. Owned by [CallService]:
 * [start] once the audio mode is `MODE_IN_COMMUNICATION`, [stop] before the mode is restored.
 * Which route to take is [CallAudioRoutePolicy]'s decision; this class only feeds it the
 * device list and applies the answer.
 *
 * Threading: the service calls in from the WebRTC signaling thread, the main thread and its
 * IO scope, the listeners run on [mainExecutor] — every entry point is `@Synchronized`, and
 * [started] drops a listener callback that was already queued when [stop] ran.
 *
 * No retry timers, no SCO polling, no `isSpeakerphoneOn`.
 */
class CallAudioRouter(
    private val audioManager: AudioManager,
    private val mainExecutor: Executor,
) {

    /** [current] is the route the OS reports as active, not the one that was asked for. */
    data class RouteState(
        val available: List<CallAudioRoute> = listOf(CallAudioRoute.EARPIECE, CallAudioRoute.SPEAKER),
        val current: CallAudioRoute = CallAudioRoute.EARPIECE,
    )

    private val _state = MutableStateFlow(RouteState())
    val state: StateFlow<RouteState> = _state.asStateFlow()

    private var started = false
    private var previousAvailable: Set<CallAudioRoute> = emptySet()
    private var userPick: CallAudioRoute? = null

    // The policy's "current". Deliberately the last route asked for, not the OS's: Bluetooth SCO
    // lags a request by about a second, and a device-list callback inside that window (there is
    // always one, right after registration) would otherwise resolve back to the earpiece.
    private var requested: CallAudioRoute? = null

    // The callback's own arrays list every device, communication-capable or not — re-query instead.
    private val deviceCallback = object : AudioDeviceCallback() {
        override fun onAudioDevicesAdded(addedDevices: Array<out AudioDeviceInfo>?) = onDevicesChanged()
        override fun onAudioDevicesRemoved(removedDevices: Array<out AudioDeviceInfo>?) = onDevicesChanged()
    }

    private val communicationDeviceListener =
        AudioManager.OnCommunicationDeviceChangedListener { device -> onCommunicationDeviceChanged(device) }

    @Synchronized
    fun start() {
        if (started) return
        started = true
        // A null handler means the main looper, the same thread as mainExecutor.
        audioManager.registerAudioDeviceCallback(deviceCallback, null)
        audioManager.addOnCommunicationDeviceChangedListener(mainExecutor, communicationDeviceListener)
        evaluate(force = false, current = routeOf(audioManager.communicationDevice) ?: CallAudioRoute.EARPIECE)
    }

    /** An explicit tap. If the route vanished since the sheet was drawn, the policy picks instead. */
    @Synchronized
    fun select(route: CallAudioRoute) {
        if (!started) return
        userPick = route
        evaluate(force = true)
    }

    /** Idempotent. Must run on every exit path, or the next media app inherits the SCO link. */
    @Synchronized
    fun stop() {
        if (!started) return
        started = false
        audioManager.unregisterAudioDeviceCallback(deviceCallback)
        audioManager.removeOnCommunicationDeviceChangedListener(communicationDeviceListener)
        audioManager.clearCommunicationDevice()
        previousAvailable = emptySet()
        userPick = null
        requested = null
    }

    @Synchronized
    private fun onDevicesChanged() {
        if (started) evaluate(force = false)
    }

    @Synchronized
    private fun onCommunicationDeviceChanged(device: AudioDeviceInfo?) {
        if (!started) return
        val route = routeOf(device)
        if (route != null) {
            _state.update { it.copy(current = route) }
        } else {
            // The OS dropped the selected device (it vanished) and routes by its own default now.
            // Ask again, so the next report names a route the UI and the proximity lock can follow.
            evaluate(force = true)
        }
    }

    /**
     * @param force re-apply even if the policy lands on the route already asked for. A tap does:
     *   the OS may have dropped that route by itself. A device callback does not, it would
     *   re-issue a Bluetooth request that is still coming up.
     * @param current the OS's active route, when the caller has a fresher one than [state]
     */
    private fun evaluate(force: Boolean, current: CallAudioRoute = _state.value.current) {
        // setCommunicationDevice() refuses a device that is not in this list at this moment,
        // so the list is read here and never cached.
        val devices = audioManager.availableCommunicationDevices
        val available = devices.mapNotNullTo(sortedSetOf()) { CallAudioRoutePolicy.routeOf(it.type) }

        _state.value = RouteState(available.toList(), current)

        val target = CallAudioRoutePolicy.resolve(previousAvailable, available, requested, userPick)
        if (target != requested || force) {
            val device = devices.firstOrNull { CallAudioRoutePolicy.routeOf(it.type) == target }
            if (device == null || !audioManager.setCommunicationDevice(device)) {
                // Nothing is committed, so the next callback decides from the same inputs. With
                // previousAvailable advanced, a headset that just appeared could never preempt again.
                Log.w(TAG, "setCommunicationDevice refused $target")
                return
            }
            requested = target
        }
        previousAvailable = available
        if (target != userPick) userPick = null
    }

    private fun routeOf(device: AudioDeviceInfo?): CallAudioRoute? =
        device?.let { CallAudioRoutePolicy.routeOf(it.type) }

    private companion object {
        const val TAG = "CallAudioRouter"
    }
}
