package com.firestream.chat.data.call

import android.os.PowerManager
import com.firestream.chat.domain.model.CallAudioRoute

/**
 * The proximity wake lock of one call: held while the audio plays on the earpiece — the phone is
 * at the user's ear and the screen must blank — and released on every other route.
 *
 * It follows the route the OS reports as *playing*, never the one that was requested: releasing on
 * a Bluetooth pick would blank nothing, but acquiring on one would blank the screen during the
 * ~1 s SCO ramp while the audio is still on the earpiece.
 *
 * Owns its synchronisation because [follow] runs on the call's route collector while [shutdown]
 * arrives from call teardown on another thread. [shutdown] latches: a [follow] that was already in
 * flight when the call ended cannot re-acquire the lock afterwards.
 */
class ProximityLock(private val powerManager: PowerManager) {

    private val lock = Any()
    private var wakeLock: PowerManager.WakeLock? = null
    private var shutDown = false

    /** Hold the lock if [route] is the earpiece, release it otherwise. No-op after [shutdown]. */
    fun follow(route: CallAudioRoute) {
        synchronized(lock) {
            if (shutDown) return
            if (route == CallAudioRoute.EARPIECE) acquire() else release()
        }
    }

    /** Release for good. Idempotent, and every later [follow] is ignored. */
    fun shutdown() {
        synchronized(lock) {
            shutDown = true
            release()
        }
    }

    private fun acquire() {
        // isHeld, not null: a lock that hit MAX_HOLD_MS released itself, and a call that long must
        // still blank the screen at the ear.
        if (wakeLock?.isHeld == true) return
        wakeLock = powerManager
            .newWakeLock(PowerManager.PROXIMITY_SCREEN_OFF_WAKE_LOCK, WAKE_LOCK_TAG)
            .apply { acquire(MAX_HOLD_MS) }
    }

    private fun release() {
        wakeLock?.let { if (it.isHeld) it.release() }
        wakeLock = null
    }

    private companion object {
        const val WAKE_LOCK_TAG = "firestream:call_proximity"
        const val MAX_HOLD_MS = 60 * 60 * 1000L
    }
}
