package com.firestream.chat.data.util

import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import com.firestream.chat.di.ApplicationScope
import com.firestream.chat.domain.util.ConnectivityObserver
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.stateIn
import javax.inject.Inject
import javax.inject.Singleton

/**
 * [ConnectivityObserver] over the platform's default-network callback.
 *
 * "Online" means the default network has both [NetworkCapabilities
 * .NET_CAPABILITY_INTERNET] and `NET_CAPABILITY_VALIDATED` — validation is what
 * makes a captive-portal Wi-Fi read as offline, matching what the user sees.
 *
 * The callback is registered while the flow is collected (plus a short grace
 * period across a configuration change) rather than for the process's life:
 * this is a display concern, and no queued send depends on it.
 */
@Singleton
class AndroidConnectivityObserver @Inject constructor(
    private val connectivityManager: ConnectivityManager,
    @ApplicationScope appScope: CoroutineScope,
) : ConnectivityObserver {

    override val isOnline: StateFlow<Boolean> = callbackFlow {
        val callback = object : ConnectivityManager.NetworkCallback() {
            override fun onCapabilitiesChanged(network: Network, capabilities: NetworkCapabilities) {
                trySend(capabilities.reachesInternet)
            }

            override fun onLost(network: Network) {
                trySend(false)
            }

            override fun onUnavailable() {
                trySend(false)
            }
        }
        trySend(currentlyOnline())
        connectivityManager.registerDefaultNetworkCallback(callback)
        awaitClose { connectivityManager.unregisterNetworkCallback(callback) }
    }
        .distinctUntilChanged()
        .stateIn(appScope, SharingStarted.WhileSubscribed(5_000), currentlyOnline())

    private fun currentlyOnline(): Boolean =
        connectivityManager.getNetworkCapabilities(connectivityManager.activeNetwork)
            ?.reachesInternet == true

    private val NetworkCapabilities.reachesInternet: Boolean
        get() = hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
            hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
}
