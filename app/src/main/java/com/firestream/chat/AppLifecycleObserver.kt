package com.firestream.chat

import android.util.Log
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import com.firestream.chat.domain.repository.UserRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Observes process-level lifecycle events (not Activity-level) to manage online presence.
 *
 * Using [ProcessLifecycleOwner] means:
 * - [onStart] fires once when the app enters foreground (first Activity resumes)
 * - [onStop] fires once when the entire app goes to background (last Activity stops)
 *
 * This avoids the bug where [MainActivity.onStop] fires during Activity transitions
 * (e.g. navigating to CallActivity, permission dialogs) — which would mark the user
 * offline while they're still actively using the app.
 *
 * Registered in [FireStreamApp.onCreate].
 */
@Singleton
class AppLifecycleObserver @Inject constructor(
    private val userRepository: UserRepository
) : DefaultLifecycleObserver {

    // Dedicated scope that survives Activity destruction. SupervisorJob so one
    // failed coroutine doesn't cancel the sibling (online/offline).
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onStart(owner: LifecycleOwner) {
        Log.d(TAG, "onStart — setting online")
        scope.launch {
            val result = userRepository.setOnlineStatus(true)
            result.exceptionOrNull()?.let { Log.w(TAG, "setOnlineStatus(true) failed", it) }
        }
    }

    override fun onStop(owner: LifecycleOwner) {
        Log.d(TAG, "onStop — setting offline")
        scope.launch {
            withContext(NonCancellable) {
                val result = userRepository.setOnlineStatus(false)
                result.exceptionOrNull()?.let { Log.w(TAG, "setOnlineStatus(false) failed", it) }
            }
        }
    }

    companion object {
        private const val TAG = "Presence"
    }
}
