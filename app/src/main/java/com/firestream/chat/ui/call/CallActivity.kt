package com.firestream.chat.ui.call

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.util.Log
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.core.content.ContextCompat
import com.firestream.chat.data.call.CallService
import com.firestream.chat.data.call.CallStateHolder
import com.firestream.chat.data.local.AppTheme
import com.firestream.chat.data.local.PreferencesDataStore
import com.firestream.chat.di.ApplicationScope
import com.firestream.chat.domain.model.CallState
import com.firestream.chat.domain.model.EndReason
import com.firestream.chat.domain.model.isOngoing
import com.firestream.chat.domain.repository.CallRepository
import com.firestream.chat.ui.theme.FireStreamTheme
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import javax.inject.Inject

@AndroidEntryPoint
class CallActivity : ComponentActivity() {

    @Inject lateinit var callStateHolder: CallStateHolder
    @Inject lateinit var callRepository: CallRepository
    @Inject lateinit var preferencesDataStore: PreferencesDataStore

    /**
     * Sets up outgoing calls. Not tied to this activity: a rotation recreates it mid-setup, and
     * cancelling the setup would leave a call document ringing the callee with no call behind it.
     */
    @Inject @ApplicationScope lateinit var appScope: CoroutineScope

    companion object {
        const val EXTRA_ACTION = "call_action"
        const val EXTRA_CALLEE_ID = "callee_id"
        const val EXTRA_CALLEE_NAME = "callee_name"
        const val EXTRA_CALLEE_AVATAR_URL = "callee_avatar_url"
        const val EXTRA_CHAT_ID = "chat_id"
        const val ACTION_OUTGOING = "outgoing"
        const val ACTION_ANSWER = "answer"
        private const val TAG = "CallActivity"
    }

    // Deferred action to run after permission is granted
    private var pendingAction: (() -> Unit)? = null

    private val requestAudioPermission = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) {
            pendingAction?.invoke()
        } else {
            Toast.makeText(this, "Microphone permission is required for calls", Toast.LENGTH_LONG).show()
            if (callStateHolder.callState.value is CallState.Idle) {
                finishAndRemoveTask()
            }
        }
        pendingAction = null
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        if (savedInstanceState == null) {
            handleIntent()
        }

        setContent {
            val appTheme by preferencesDataStore.appThemeFlow.collectAsState(initial = AppTheme.SYSTEM)
            val useDark = when (appTheme) {
                AppTheme.LIGHT -> false
                AppTheme.DARK -> true
                AppTheme.SYSTEM -> isSystemInDarkTheme()
            }
            FireStreamTheme(darkTheme = useDark) {
                CallScreen(
                    // Out of Recents too: relaunching a finished call screen from there must not
                    // bring back the intent that placed or answered its call.
                    onFinish = { finishAndRemoveTask() },
                    onAnswer = { withAudioPermission { answerCall() } }
                )
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleIntent()
    }

    private fun handleIntent() {
        val launchedFromHistory = intent.flags and Intent.FLAG_ACTIVITY_LAUNCHED_FROM_HISTORY != 0
        val launch = callLaunchFor(
            action = intent.getStringExtra(EXTRA_ACTION),
            launchedFromHistory = launchedFromHistory,
            callOngoing = callStateHolder.callState.value.isOngoing
        )
        when (launch) {
            CallLaunch.PLACE_CALL -> placeOutgoingCall()
            CallLaunch.ANSWER -> withAudioPermission { answerCall() }
            CallLaunch.SHOW -> Unit
            CallLaunch.CLOSE -> finishAndRemoveTask()
        }
    }

    private fun withAudioPermission(action: () -> Unit) {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO)
            == PackageManager.PERMISSION_GRANTED
        ) {
            action()
        } else {
            pendingAction = action
            requestAudioPermission.launch(Manifest.permission.RECORD_AUDIO)
        }
    }

    /**
     * Runs before the permission prompt. The call screen composes behind the prompt, and the
     * previous call's Ended state would finish this activity underneath it.
     */
    private fun placeOutgoingCall() {
        if (!callStateHolder.prepareOutgoingCall()) {
            // A call is already running. This screen shows it instead of placing a second one.
            Toast.makeText(this, "You're already in a call", Toast.LENGTH_SHORT).show()
            return
        }
        withAudioPermission { startOutgoingCall() }
    }

    private fun startOutgoingCall() {
        val calleeId = intent.getStringExtra(EXTRA_CALLEE_ID) ?: return
        val chatId = intent.getStringExtra(EXTRA_CHAT_ID) ?: return
        val calleeName = intent.getStringExtra(EXTRA_CALLEE_NAME) ?: "Unknown"
        val calleeAvatarUrl = intent.getStringExtra(EXTRA_CALLEE_AVATAR_URL)
        val appContext = applicationContext

        appScope.launch {
            callRepository.createCall(calleeId)
                .onSuccess { callId ->
                    if (isFinishing) {
                        // The user left during setup, but the new document has already rung the callee.
                        callRepository.endCall(callId, EndReason.HANGUP.name.lowercase())
                        return@onSuccess
                    }
                    try {
                        CallService.startOutgoing(appContext, callId, chatId, calleeId, calleeName, calleeAvatarUrl)
                    } catch (e: IllegalStateException) {
                        // Android 12+ will not start a foreground service once the app is in the
                        // background, and a slow setup can outlast the user leaving the app.
                        Log.w(TAG, "Could not start the call service", e)
                        callRepository.endCall(callId, EndReason.ERROR.name.lowercase())
                        callStateHolder.updateState(CallState.Ended(callId, EndReason.ERROR))
                    }
                }
                .onFailure { e ->
                    Log.w(TAG, "Could not create the call", e)
                    withContext(Dispatchers.Main) {
                        Toast.makeText(appContext, "Couldn't start the call", Toast.LENGTH_LONG).show()
                    }
                    // Ended closes whichever instance of this screen is showing, even after a rotation.
                    callStateHolder.updateState(CallState.Ended(callId = "", reason = EndReason.ERROR))
                }
        }
    }

    private fun answerCall() {
        CallService.sendAction(this, CallService.ACTION_ANSWER)
    }
}
