package com.firestream.chat

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.delay
import com.firestream.chat.data.local.AppTheme
import com.firestream.chat.data.local.PreferencesDataStore
import com.firestream.chat.data.share.SharedContentHolder
import com.firestream.chat.domain.repository.UserRepository
import com.firestream.chat.navigation.FireStreamNavGraph
import com.firestream.chat.ui.theme.FireStreamTheme
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.launch
import javax.inject.Inject

@AndroidEntryPoint
class MainActivity : ComponentActivity() {

    companion object {
        const val EXTRA_CHAT_ID: String = "chatId"
        const val EXTRA_SENDER_ID: String = "senderId"
        private const val SPLASH_SETTLE_TIMEOUT_MS = 1500L
    }

    @Inject
    lateinit var preferencesDataStore: PreferencesDataStore

    @Inject
    lateinit var userRepository: UserRepository

    @Inject
    lateinit var sharedContentHolder: SharedContentHolder

    // Keeps the system splash on screen until the launch-restore has settled
    // (restored chat/list revealed, or nothing to restore). Flipped by
    // FireStreamNavGraph via onLaunchSettled; the timeout in onCreate is the
    // safety net so a missed signal can never trap the user behind the splash.
    private val launchSettled = mutableStateOf(false)

    override fun onResume() {
        super.onResume()
        // Redundant with AppLifecycleObserver.onStart(), but guarantees online status is set
        // even if ProcessLifecycleOwner fires before auth state is restored (e.g. cold start).
        lifecycleScope.launch { userRepository.setOnlineStatus(true) }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        val splashScreen = installSplashScreen()
        super.onCreate(savedInstanceState)
        splashScreen.setKeepOnScreenCondition { !launchSettled.value }
        lifecycleScope.launch {
            delay(SPLASH_SETTLE_TIMEOUT_MS)
            launchSettled.value = true
        }
        enableEdgeToEdge()
        requestNotificationPermissionIfNeeded()
        val initialChatId = intent.getStringExtra(EXTRA_CHAT_ID)
        val initialSenderId = intent.getStringExtra(EXTRA_SENDER_ID)
        val openSettings = intent.getBooleanExtra("openSettings", false)
        val focusUpdate = intent.getBooleanExtra("focusUpdate", false)
        val isShareIntent = intent?.action in listOf(Intent.ACTION_SEND, Intent.ACTION_SEND_MULTIPLE)
        if (isShareIntent) {
            sharedContentHolder.pendingIntent = intent
        }
        setContent {
            val appTheme by preferencesDataStore.appThemeFlow.collectAsState(initial = AppTheme.SYSTEM)
            val useDark = when (appTheme) {
                AppTheme.LIGHT -> false
                AppTheme.DARK -> true
                AppTheme.SYSTEM -> isSystemInDarkTheme()
            }
            FireStreamTheme(darkTheme = useDark) {
                FireStreamNavGraph(
                    initialChatId = initialChatId,
                    initialSenderId = initialSenderId,
                    isShareIntent = isShareIntent,
                    openSettings = openSettings,
                    focusUpdate = focusUpdate,
                    preferencesDataStore = preferencesDataStore,
                    onLaunchSettled = { launchSettled.value = true }
                )
            }
        }
    }


    private fun requestNotificationPermissionIfNeeded() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
                != PackageManager.PERMISSION_GRANTED
            ) {
                ActivityCompat.requestPermissions(
                    this,
                    arrayOf(Manifest.permission.POST_NOTIFICATIONS),
                    0
                )
            }
        }
    }
}
