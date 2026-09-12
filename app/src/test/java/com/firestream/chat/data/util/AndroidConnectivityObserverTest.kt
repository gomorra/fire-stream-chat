package com.firestream.chat.data.util

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowNetworkCapabilities

/**
 * The observer's rule: online means the default network is *validated*, so a
 * captive-portal Wi-Fi (INTERNET without VALIDATED) reads as offline.
 *
 * Robolectric's `ShadowConnectivityManager` records the registered default
 * network callback but never dispatches to it, so the test drives the recorded
 * callback directly — that is exactly the edge the production code owns.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29], manifest = Config.NONE, application = android.app.Application::class)
class AndroidConnectivityObserverTest {

    private lateinit var connectivityManager: ConnectivityManager

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        connectivityManager = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
    }

    private fun capabilities(vararg caps: Int): NetworkCapabilities {
        val capabilities = ShadowNetworkCapabilities.newInstance()
        caps.forEach { shadowOf(capabilities).addCapability(it) }
        return capabilities
    }

    private val validated = { capabilities(
        NetworkCapabilities.NET_CAPABILITY_INTERNET,
        NetworkCapabilities.NET_CAPABILITY_VALIDATED,
    ) }

    private val captivePortal = { capabilities(NetworkCapabilities.NET_CAPABILITY_INTERNET) }

    @Test
    fun `a validated default network is online, losing it is offline`() = runTest(UnconfinedTestDispatcher()) {
        val observer = AndroidConnectivityObserver(connectivityManager, backgroundScope)
        backgroundScope.launch { observer.isOnline.collect { } }

        val callback = shadowOf(connectivityManager).networkCallbacks.first()
        callback.onCapabilitiesChanged(anyNetwork(), validated())
        assertTrue(observer.isOnline.value)

        callback.onLost(anyNetwork())
        assertFalse(observer.isOnline.value)
    }

    @Test
    fun `a captive portal counts as offline`() = runTest(UnconfinedTestDispatcher()) {
        // INTERNET without VALIDATED: the hotel Wi-Fi is connected, nothing sends.
        val observer = AndroidConnectivityObserver(connectivityManager, backgroundScope)
        backgroundScope.launch { observer.isOnline.collect { } }

        val callback = shadowOf(connectivityManager).networkCallbacks.first()
        callback.onCapabilitiesChanged(anyNetwork(), validated())
        callback.onCapabilitiesChanged(anyNetwork(), captivePortal())

        assertFalse(observer.isOnline.value)
    }

    @Test
    fun `the callback is unregistered once nothing collects`() = runTest(UnconfinedTestDispatcher()) {
        val observer = AndroidConnectivityObserver(connectivityManager, backgroundScope)
        val collecting = backgroundScope.launch { observer.isOnline.collect { } }
        assertTrue(shadowOf(connectivityManager).networkCallbacks.isNotEmpty())

        collecting.cancel()
        // WhileSubscribed's stop timeout, then the upstream's awaitClose runs.
        testScheduler.advanceTimeBy(6_000)

        assertTrue(shadowOf(connectivityManager).networkCallbacks.isEmpty())
    }

    private fun anyNetwork(): Network = checkNotNull(connectivityManager.activeNetwork)
}
