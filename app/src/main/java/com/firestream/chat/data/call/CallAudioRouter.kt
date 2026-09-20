package com.firestream.chat.data.call

import android.media.AudioDeviceCallback
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.os.Handler
import android.os.Looper
import android.util.Log
import com.firestream.chat.domain.model.CallAudioRoute
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import java.util.concurrent.Executor

/**
 * What the OS is doing right now: the routes this call can use and the one it is actually on.
 * [current] follows `OnCommunicationDeviceChangedListener`, never the route we asked for —
 * Bluetooth SCO takes about a second to come up and the UI must not lie about it. Display order
 * is `CallStateHolder.updateAudioRoutes`'s job, so [available] stays an unordered set.
 */
data class CallRouteState(
    val available: Set<CallAudioRoute> = emptySet(),
    val current: CallAudioRoute = CallAudioRoute.EARPIECE,
)

/**
 * Applies [CallAudioRoutePolicy] to the real device list. Owned by `CallService`: [start] runs
 * once the audio mode is `MODE_IN_COMMUNICATION`, [stop] before it is restored.
 *
 * Every device change re-queries `availableCommunicationDevices` rather than trusting the
 * callback's array, which reports *all* devices, communication-capable or not. There are no
 * retries and no SCO polling: `setCommunicationDevice` either takes or the next device callback
 * corrects us.
 *
 * Threading: the two listeners fire on [mainExecutor], [start]/[select]/[stop] are called from
 * the service's WebRTC and IO threads, and the mutable state below is guarded by [lock]. The
 * work is not posted to the executor on purpose — `stop()` has to clear the communication
 * device *before* the caller restores the audio mode, which a posted runnable cannot promise.
 */
class CallAudioRouter(
    private val audioManager: AudioManager,
    private val mainExecutor: Executor,
) {

    private val _state = MutableStateFlow(CallRouteState())
    val state: StateFlow<CallRouteState> = _state.asStateFlow()

    private val lock = Any()

    /**
     * `registerAudioDeviceCallback` resolves a null Handler to `Looper.myLooper()` and only
     * falls back to the main Looper — and `start()` is reached from a Looper-less WebRTC
     * thread, so the fallback is an accident, not a contract. Both listeners must be on main.
     */
    private val mainHandler = Handler(Looper.getMainLooper())

    /** All three guarded by [lock]. */
    private var started = false
    private var previousAvailable: Set<CallAudioRoute> = emptySet()
    private var userPick: CallAudioRoute? = null

    private val deviceCallback = object : AudioDeviceCallback() {
        override fun onAudioDevicesAdded(addedDevices: Array<out AudioDeviceInfo>?) = reconcile()
        override fun onAudioDevicesRemoved(removedDevices: Array<out AudioDeviceInfo>?) = reconcile()
    }

    private val communicationDeviceListener =
        AudioManager.OnCommunicationDeviceChangedListener { device ->
            val route = device?.let { CallAudioRoutePolicy.routeOf(it.type) } ?: return@OnCommunicationDeviceChangedListener
            _state.update { it.copy(current = route) }
        }

    /** Starts routing and applies the policy once. Idempotent. */
    fun start() {
        synchronized(lock) {
            if (started) return
            started = true
            previousAvailable = emptySet()
            userPick = null
            audioManager.addOnCommunicationDeviceChangedListener(mainExecutor, communicationDeviceListener)
            audioManager.registerAudioDeviceCallback(deviceCallback, mainHandler)
            reconcile()
        }
    }

    /** Records an explicit user tap and applies it, or the policy's answer if the route is gone. */
    fun select(route: CallAudioRoute) {
        synchronized(lock) {
            if (!started) return
            userPick = route
            reconcile()
        }
    }

    /** Releases the route and both listeners. Idempotent; safe when [start] never ran. */
    fun stop() {
        synchronized(lock) {
            if (!started) return
            started = false
            audioManager.unregisterAudioDeviceCallback(deviceCallback)
            audioManager.removeOnCommunicationDeviceChangedListener(communicationDeviceListener)
            // Without this the next media app is stuck on the 8 kHz SCO link.
            audioManager.clearCommunicationDevice()
        }
        // _state is left alone: CallStateHolder.reset() owns the UI reset, and a last EARPIECE
        // emission here would re-acquire the proximity wake lock while the call tears down.
    }

    /** The one path that reads the devices, runs the policy and applies it. Reentrant on [lock]. */
    private fun reconcile() {
        synchronized(lock) {
            if (!started) return
            // Re-query: setCommunicationDevice does nothing for a device that is not in this
            // list at this moment, and the device callback's array is not filtered to
            // communication use.
            val routed = audioManager.availableCommunicationDevices.mapNotNull { device ->
                CallAudioRoutePolicy.routeOf(device.type)?.let { it to device }
            }
            val available = routed.mapTo(mutableSetOf()) { it.first }
            val current = audioManager.communicationDevice?.let { CallAudioRoutePolicy.routeOf(it.type) }

            val resolved = CallAudioRoutePolicy.resolve(previousAvailable, available, current, userPick)
            previousAvailable = available
            // A headset that just appeared outranks the last tap (§0: it always wins).
            if (userPick != null && resolved != userPick) userPick = null

            _state.update { it.copy(available = available, current = current ?: it.current) }

            if (resolved == current) return
            val device = routed.firstOrNull { it.first == resolved }?.second ?: return
            if (!audioManager.setCommunicationDevice(device)) {
                Log.w(TAG, "setCommunicationDevice($resolved) refused")
            }
        }
    }

    private companion object {
        const val TAG = "CallAudioRouter"
    }
}
