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
import com.firestream.chat.navigation.DeepLinkRequest
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
        const val EXTRA_MESSAGE_ID: String = "messageId"
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

    // The current notification deep link, observed by FireStreamNavGraph. Seeded
    // from the launch intent in onCreate (cold start) and replaced by
    // onNewIntent when a notification is tapped while the activity is already
    // alive (warm foreground/background delivery — the critical R1 case). A fresh
    // DeepLinkRequest carries a unique token so the graph re-drives even for the
    // same chat.
    private val deepLinkRequest = mutableStateOf<DeepLinkRequest?>(null)

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
        // Only seed the deep link on a genuine fresh create. On a config-change /
        // process-death recreation (savedInstanceState != null) the launch intent
        // is re-delivered, but the deep link was already consumed — rebuilding it
        // would re-navigate. Warm taps come through onNewIntent instead.
        if (savedInstanceState == null) {
            deepLinkRequest.value = deepLinkFromIntent(intent)
        }
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
                    deepLinkRequest = deepLinkRequest.value,
                    isShareIntent = isShareIntent,
                    openSettings = openSettings,
                    focusUpdate = focusUpdate,
                    preferencesDataStore = preferencesDataStore,
                    onLaunchSettled = { launchSettled.value = true }
                )
            }
        }
    }


    // A notification tapped while this activity is already alive is delivered
    // here (launchMode is standard, but the notification PendingIntents carry
    // FLAG_ACTIVITY_SINGLE_TOP, so an on-top instance is reused). setIntent keeps
    // getIntent() consistent; updating deepLinkRequest with a fresh token makes
    // FireStreamNavGraph re-drive to the target chat. Risk R1: without this a
    // foreground tap was silently dropped.
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        deepLinkFromIntent(intent)?.let { deepLinkRequest.value = it }
    }

    /**
     * Builds a [DeepLinkRequest] from notification extras. Both chatId and
     * senderId are required (the CHAT route needs a recipient path segment and
     * resolveChatListPendingAction gates on both) — every notification producer
     * sets them; anything else is not a chat deep link.
     */
    private fun deepLinkFromIntent(intent: Intent?): DeepLinkRequest? {
        val chatId = intent?.getStringExtra(EXTRA_CHAT_ID) ?: return null
        val senderId = intent.getStringExtra(EXTRA_SENDER_ID) ?: return null
        val messageId = intent.getStringExtra(EXTRA_MESSAGE_ID)
        return DeepLinkRequest(chatId = chatId, senderId = senderId, messageId = messageId)
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
