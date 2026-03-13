package com.firestream.chat.ui.call

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
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
        const val ACTION_OUTGOING = "outgoing"
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        // If launched to start an outgoing call, initiate it
        if (savedInstanceState == null && intent?.getStringExtra(EXTRA_ACTION) == ACTION_OUTGOING) {
            startOutgoingCall()
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

    private fun startOutgoingCall() {
        val calleeId = intent.getStringExtra(EXTRA_CALLEE_ID) ?: return
        val calleeName = intent.getStringExtra(EXTRA_CALLEE_NAME) ?: "Unknown"
        val calleeAvatarUrl = intent.getStringExtra(EXTRA_CALLEE_AVATAR_URL)

        activityScope.launch {
            callRepository.createCall(calleeId).onSuccess { callId ->
                CallService.startOutgoing(
                    this@CallActivity, callId, calleeId, calleeName, calleeAvatarUrl
                )
            }
        }
    }
}
