package com.firestream.chat.ui.call

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.firestream.chat.data.call.CallService
import com.firestream.chat.data.call.CallStateHolder
import com.firestream.chat.data.local.AppTheme
import com.firestream.chat.data.local.PreferencesDataStore
import com.firestream.chat.domain.model.CallState
import com.firestream.chat.domain.model.isOngoing
import com.firestream.chat.ui.theme.FireStreamTheme
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.launch
import javax.inject.Inject

@AndroidEntryPoint
class CallActivity : ComponentActivity() {

    @Inject lateinit var callStateHolder: CallStateHolder
    @Inject lateinit var preferencesDataStore: PreferencesDataStore

    /** Outlives a rotation, so the call setup and a pending permission request are not lost to one. */
    private val viewModel: CallViewModel by viewModels()

    companion object {
        const val EXTRA_ACTION = "call_action"
        const val EXTRA_CALLEE_ID = "callee_id"
        const val EXTRA_CALLEE_NAME = "callee_name"
        const val EXTRA_CALLEE_AVATAR_URL = "callee_avatar_url"
        const val EXTRA_CHAT_ID = "chat_id"
        const val ACTION_OUTGOING = "outgoing"
        const val ACTION_ANSWER = "answer"
    }

    private val requestAudioPermission = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        val action = viewModel.pendingMicAction
        viewModel.pendingMicAction = null
        if (granted) {
            action?.let(::runMicAction)
        } else {
            Toast.makeText(this, "Microphone permission is required for calls", Toast.LENGTH_LONG).show()
            if (callStateHolder.callState.value is CallState.Idle) {
                finishAndRemoveTask()
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        if (savedInstanceState == null) {
            handleIntent()
        }

        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.setupFailed.collect {
                    Toast.makeText(this@CallActivity, "Couldn't start the call", Toast.LENGTH_LONG).show()
                }
            }
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
                    onAnswer = { withAudioPermission(MicAction.ANSWER) },
                    viewModel = viewModel
                )
            }
        }
    }

    override fun onPause() {
        super.onPause()
        // Back or finishAndRemoveTask(). A setup still running must end its call, not start it.
        if (isFinishing) viewModel.onScreenClosed()
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
            CallLaunch.ANSWER -> withAudioPermission(MicAction.ANSWER)
            CallLaunch.SHOW -> Unit
            CallLaunch.CLOSE -> finishAndRemoveTask()
        }
    }

    /** Run [action] now if the microphone is granted, or after the user grants it. */
    private fun withAudioPermission(action: MicAction) {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO)
            == PackageManager.PERMISSION_GRANTED
        ) {
            runMicAction(action)
        } else {
            viewModel.pendingMicAction = action
            requestAudioPermission.launch(Manifest.permission.RECORD_AUDIO)
        }
    }

    private fun runMicAction(action: MicAction) {
        when (action) {
            MicAction.PLACE_CALL -> startOutgoingCall()
            MicAction.ANSWER -> CallService.sendAction(this, CallService.ACTION_ANSWER)
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
        withAudioPermission(MicAction.PLACE_CALL)
    }

    private fun startOutgoingCall() {
        val calleeId = intent.getStringExtra(EXTRA_CALLEE_ID) ?: return
        val chatId = intent.getStringExtra(EXTRA_CHAT_ID) ?: return
        val calleeName = intent.getStringExtra(EXTRA_CALLEE_NAME) ?: "Unknown"
        val calleeAvatarUrl = intent.getStringExtra(EXTRA_CALLEE_AVATAR_URL)
        viewModel.placeCall(calleeId, chatId, calleeName, calleeAvatarUrl)
    }
}
