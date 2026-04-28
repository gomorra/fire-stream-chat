// region: AGENT-NOTE
// Hooks the PocketBase SSE realtime connection to the process lifecycle.
//
// PB SSE doesn't auto-pause on backgrounding (Firestore listeners do). Without
// this, an idle phone keeps the SSE socket open indefinitely, draining battery
// and pinning a server connection. We mirror Firestore's behaviour:
//   * onStart  → realtime.connect()    (ensures a stream is running)
//   * onStop   → realtime.disconnect() (drops the socket; subscribers' Flows
//                                       complete naturally and re-subscribe
//                                       on the next connect())
//
// Bound into the `Set<FlavorBootstrap>` so FireStreamApp.onCreate triggers
// instantiation, which in turn registers the observer with ProcessLifecycleOwner.
// endregion
package com.firestream.chat.data.remote.pocketbase

import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ProcessLifecycleOwner
import com.firestream.chat.di.FlavorBootstrap
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class PocketBaseLifecycleHook @Inject constructor(
    private val realtime: PocketBaseRealtime
) : FlavorBootstrap, DefaultLifecycleObserver {

    override fun start() {
        ProcessLifecycleOwner.get().lifecycle.addObserver(this)
    }

    override fun onStart(owner: LifecycleOwner) {
        realtime.connect()
    }

    override fun onStop(owner: LifecycleOwner) {
        realtime.disconnect()
    }
}
