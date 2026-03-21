package com.firestream.chat

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
        // App entered foreground — mark the current user as online.
        scope.launch { userRepository.setOnlineStatus(true) }
    }

    override fun onStop(owner: LifecycleOwner) {
        // App went to background — mark offline. Use NonCancellable so the write
        // is not killed if the scope is also shutting down (e.g. process death race).
        scope.launch { withContext(NonCancellable) { userRepository.setOnlineStatus(false) } }
    }
}
