package com.firestream.chat.ui.call

import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
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
import com.firestream.chat.domain.model.CallState
import com.firestream.chat.domain.repository.CallRepository
import com.firestream.chat.ui.theme.FireStreamTheme
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import javax.inject.Inject

@AndroidEntryPoint
class CallActivity : ComponentActivity() {

    @Inject lateinit var callStateHolder: CallStateHolder
    @Inject lateinit var callRepository: CallRepository
    @Inject lateinit var preferencesDataStore: PreferencesDataStore

    private val activityScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    companion object {
        const val EXTRA_ACTION = "call_action"
        const val EXTRA_CALLEE_ID = "callee_id"
        const val EXTRA_CALLEE_NAME = "callee_name"
        const val EXTRA_CALLEE_AVATAR_URL = "callee_avatar_url"
        const val EXTRA_CHAT_ID = "chat_id"
        const val ACTION_OUTGOING = "outgoing"
        const val ACTION_ANSWER = "answer"
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
                finish()
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
                CallScreen(onFinish = { finish() })
            }
        }
    }

    override fun onNewIntent(intent: android.content.Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleIntent()
    }

    private fun handleIntent() {
        when (intent?.getStringExtra(EXTRA_ACTION)) {
            ACTION_OUTGOING -> withAudioPermission { startOutgoingCall() }
            ACTION_ANSWER -> withAudioPermission { answerCall() }
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

    private fun startOutgoingCall() {
        val calleeId = intent.getStringExtra(EXTRA_CALLEE_ID) ?: return
        val chatId = intent.getStringExtra(EXTRA_CHAT_ID) ?: return
        val calleeName = intent.getStringExtra(EXTRA_CALLEE_NAME) ?: "Unknown"
        val calleeAvatarUrl = intent.getStringExtra(EXTRA_CALLEE_AVATAR_URL)

        activityScope.launch {
            callRepository.createCall(calleeId).onSuccess { callId ->
                CallService.startOutgoing(
                    this@CallActivity, callId, chatId, calleeId, calleeName, calleeAvatarUrl
                )
            }
        }
    }

    private fun answerCall() {
        CallService.sendAction(this, CallService.ACTION_ANSWER)
    }

    override fun onDestroy() {
        super.onDestroy()
        activityScope.cancel()
    }
}
