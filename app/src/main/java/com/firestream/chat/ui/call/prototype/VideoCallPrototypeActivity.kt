// PROTOTYPE — throwaway. See VideoCallPrototype.kt.
//
// Declared only in app/src/debug/AndroidManifest.xml, so a release build cannot start it.
// Set up like CallActivity: edge to edge, FireStreamTheme, lock-screen flags, and real
// picture-in-picture so variants A and B can be judged in the small window.
//
// Extras (all optional):
//   variant   A | B | C                       people    2 | 3 | 4
//   phase     outgoing | incoming | connecting | connected | ended
//   video     started as video (default true) mycam     own camera (default: video)
//   theircam  on | off | mixed                weak      weak network
//   name      the chat's name                 group     the chat is a group
//   theme     dark | light (default: the app's setting)
//   bare      hide the switcher               auto      ring once, then connect
package com.firestream.chat.ui.call.prototype

import android.app.PictureInPictureParams
import android.content.Context
import android.content.Intent
import android.content.res.Configuration
import android.os.Bundle
import android.util.Rational
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Phone
import androidx.compose.material.icons.filled.Videocam
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import com.firestream.chat.data.local.AppTheme
import com.firestream.chat.data.local.PreferencesDataStore
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject

@AndroidEntryPoint
class VideoCallPrototypeActivity : ComponentActivity() {

    @Inject lateinit var preferencesDataStore: PreferencesDataStore

    private var args by mutableStateOf(PrototypeLaunch())
    private var inPip by mutableStateOf(false)
    private var pipEligible = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        args = intent.toLaunch()

        val host = PrototypeHost(
            setPipEligible = ::setPipEligible,
            enterPip = ::enterPip,
            close = ::finish,
        )
        setContent {
            val appTheme by preferencesDataStore.appThemeFlow.collectAsState(initial = AppTheme.SYSTEM)
            val appDark = when (appTheme) {
                AppTheme.LIGHT -> false
                AppTheme.DARK -> true
                AppTheme.SYSTEM -> isSystemInDarkTheme()
            }
            VideoCallPrototype(launch = args, appDark = appDark, inPip = inPip, host = host)
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        args = intent.toLaunch()
    }

    /** While eligible, a swipe home goes straight into the small window. */
    private fun setPipEligible(eligible: Boolean) {
        pipEligible = eligible
        setPictureInPictureParams(pipParams(autoEnter = eligible))
    }

    private fun enterPip() {
        if (pipEligible) enterPictureInPictureMode(pipParams(autoEnter = true)) else moveTaskToBack(true)
    }

    private fun pipParams(autoEnter: Boolean): PictureInPictureParams =
        PictureInPictureParams.Builder()
            .setAspectRatio(Rational(9, 16))
            .setAutoEnterEnabled(autoEnter)
            .build()

    override fun onUserLeaveHint() {
        super.onUserLeaveHint()
        if (pipEligible && !isInPictureInPictureMode) enterPictureInPictureMode(pipParams(autoEnter = true))
    }

    override fun onPictureInPictureModeChanged(isInPictureInPictureMode: Boolean, newConfig: Configuration) {
        super.onPictureInPictureModeChanged(isInPictureInPictureMode, newConfig)
        inPip = isInPictureInPictureMode
    }

    companion object {
        /** From a chat: an outgoing call that rings once and then connects. */
        fun intent(context: Context, chatName: String, isGroup: Boolean, video: Boolean): Intent =
            Intent(context, VideoCallPrototypeActivity::class.java)
                .putExtra("name", chatName)
                .putExtra("group", isGroup)
                .putExtra("people", if (isGroup) 3 else 2)
                .putExtra("video", video)
                .putExtra("phase", CallPhase.OUTGOING.key)
                .putExtra("auto", true)
    }
}

private fun Intent?.toLaunch(): PrototypeLaunch {
    val extras = this?.extras ?: return PrototypeLaunch()
    val video = extras.getBoolean("video", true)
    return PrototypeLaunch(
        variant = CallVariant.of(extras.getString("variant")),
        people = extras.getInt("people", 2).coerceIn(2, 4),
        phase = CallPhase.of(extras.getString("phase")) ?: CallPhase.CONNECTED,
        chatName = extras.getString("name")?.takeIf { it.isNotBlank() } ?: "Mara Lindqvist",
        isGroup = extras.getBoolean("group", false),
        video = video,
        myCamera = extras.getBoolean("mycam", video),
        remoteCameras = RemoteCameras.of(extras.getString("theircam"))
            ?: if (video) RemoteCameras.ON else RemoteCameras.OFF,
        weak = extras.getBoolean("weak", false),
        bare = extras.getBoolean("bare", false),
        autoAdvance = extras.getBoolean("auto", false),
        forceDark = when (extras.getString("theme")) {
            "dark" -> true
            "light" -> false
            else -> null
        },
    )
}

/**
 * The entry point to judge, for ChatScreen's top bar in debug builds. A camera icon beside
 * the phone icon. A group has no real call yet, so it gets a stand-in phone icon as well.
 */
@Composable
internal fun VideoCallPrototypeEntry(chatName: String, isGroup: Boolean) {
    val context = LocalContext.current
    IconButton(
        onClick = {
            context.startActivity(VideoCallPrototypeActivity.intent(context, chatName, isGroup, video = true))
        },
    ) {
        Icon(
            imageVector = Icons.Default.Videocam,
            contentDescription = "Video call (prototype)",
            tint = MaterialTheme.colorScheme.onBackground,
        )
    }
    if (isGroup) {
        IconButton(
            onClick = {
                context.startActivity(VideoCallPrototypeActivity.intent(context, chatName, isGroup, video = false))
            },
        ) {
            Icon(
                imageVector = Icons.Default.Phone,
                contentDescription = "Voice call (prototype)",
                tint = MaterialTheme.colorScheme.onBackground,
            )
        }
    }
}
