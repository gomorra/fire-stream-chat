package com.firestream.chat.di

/**
 * Hook for flavor-specific eager initialization in [FireStreamApp.onCreate].
 *
 * Bound via `@Multibinds` so the firebase flavor (which currently needs no
 * flavor-specific bootstrap) provides an empty set, and the pocketbase flavor
 * adds [com.firestream.chat.data.remote.pocketbase.PocketBaseLifecycleHook] to
 * tear down / restart the SSE realtime connection on background / foreground.
 */
interface FlavorBootstrap {
    fun start()
}
