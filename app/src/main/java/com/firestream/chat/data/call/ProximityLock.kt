package com.firestream.chat.data.call

import android.os.PowerManager
import com.firestream.chat.domain.model.CallAudioRoute

/**
 * The proximity wake lock of one call: held while the audio plays on the earpiece — the phone is
 * at the user's ear and the screen must blank — and released on every other route. While any video
 * shows it is not held at all: the phone is in front of the face, and a hand passing the sensor
 * must not blank the picture.
 *
 * It follows the route the OS reports as *playing*, never the one that was requested: releasing on
 * a Bluetooth pick would blank nothing, but acquiring on one would blank the screen during the
 * ~1 s SCO ramp while the audio is still on the earpiece.
 *
 * [follow] runs on the call's route collector, [setVideoShowing] when a camera goes on or off, and
 * [shutdown] on call teardown, all on the main thread. [shutdown] latches: a call that comes after
 * it cannot re-acquire the lock. The class owns its synchronisation, so the latch holds for a
 * caller on any thread.
 */
class ProximityLock(private val powerManager: PowerManager) {

    private val lock = Any()
    private var wakeLock: PowerManager.WakeLock? = null
    private var shutDown = false

    /** The route last followed. Null until the first [follow]. */
    private var route: CallAudioRoute? = null
    private var videoShowing = false

    /**
     * Hold the lock if [route] is the earpiece and no video shows, release it otherwise. No-op
     * after [shutdown].
     */
    fun follow(route: CallAudioRoute) {
        synchronized(lock) {
            this.route = route
            holdOrRelease()
        }
    }

    /**
     * Video started or stopped showing, on either side. The lock is released while it shows, and
     * taken again on the earpiece when it stops. No-op after [shutdown].
     */
    fun setVideoShowing(showing: Boolean) {
        synchronized(lock) {
            videoShowing = showing
            holdOrRelease()
        }
    }

    /** Release for good. Idempotent, and every later [follow] is ignored. */
    fun shutdown() {
        synchronized(lock) {
            shutDown = true
            release()
        }
    }

    /** Caller holds [lock]. */
    private fun holdOrRelease() {
        if (shutDown) return
        if (route == CallAudioRoute.EARPIECE && !videoShowing) acquire() else release()
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
