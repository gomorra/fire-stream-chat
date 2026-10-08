package com.firestream.chat.ui.call

import android.content.Context
import android.util.Log
import androidx.lifecycle.ViewModel
import com.firestream.chat.data.call.CallService
import com.firestream.chat.data.call.CallStateHolder
import com.firestream.chat.di.ApplicationScope
import com.firestream.chat.domain.model.CallAudioRoute
import com.firestream.chat.domain.model.CallState
import com.firestream.chat.domain.model.CallUiControls
import com.firestream.chat.domain.model.EndReason
import com.firestream.chat.domain.repository.CallRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.atomic.AtomicBoolean
import javax.inject.Inject

@HiltViewModel
class CallViewModel @Inject constructor(
    private val callStateHolder: CallStateHolder,
    private val callRepository: CallRepository,
    @ApplicationScope private val appScope: CoroutineScope,
    @ApplicationContext private val context: Context
) : ViewModel() {

    val callState: StateFlow<CallState> = callStateHolder.callState
    val uiControls: StateFlow<CallUiControls> = callStateHolder.uiControls

    /**
     * The action waiting on the microphone prompt. It lives here, not in the activity, so that a
     * grant after a rotation still runs it.
     */
    internal var pendingMicAction: MicAction? = null

    private val _setupFailed = Channel<Unit>(Channel.CONFLATED)

    /** Emits when an outgoing call could not be created. */
    val setupFailed: Flow<Unit> = _setupFailed.receiveAsFlow()

    /** The running [placeCall], until it hands the call to [CallService] or fails. */
    private var setup: Job? = null

    /**
     * Set when the call screen closes for good. A rotation keeps this ViewModel, so it is not a
     * close. The setup reads it on the application scope's thread.
     */
    private val closed = AtomicBoolean(false)

    /**
     * Create the call and hand it to [CallService]. Does nothing while an earlier call is still
     * being set up.
     *
     * The setup runs on the application scope. The call document rings the callee as soon as it
     * exists, so a setup cancelled halfway would leave them ringing. A setup that finishes after
     * the screen closed ends its call instead, and touches no state the next screen may own.
     */
    fun placeCall(calleeId: String, chatId: String, calleeName: String, calleeAvatarUrl: String?) {
        if (setup?.isActive == true) return
        setup = appScope.launch {
            callRepository.createCall(calleeId)
                .onSuccess { callId ->
                    if (closed.get()) {
                        callRepository.endCall(callId, EndReason.HANGUP.name.lowercase())
                        return@onSuccess
                    }
                    try {
                        CallService.startOutgoing(context, callId, chatId, calleeId, calleeName, calleeAvatarUrl)
                    } catch (e: IllegalStateException) {
                        // Android 12+ will not start a foreground service once the app is in the
                        // background, and a slow setup can outlast the user leaving the app.
                        Log.w(TAG, "Could not start the call service", e)
                        callRepository.endCall(callId, EndReason.ERROR.name.lowercase())
                        callStateHolder.failOutgoingCall(callId)
                        return@onSuccess
                    }
                    // The setup stays running until the service publishes the call, which it does
                    // on the main thread a moment later. Until then the state is still Idle, and a
                    // second tap would place a second call.
                    withTimeoutOrNull(HANDOVER_TIMEOUT_MS) {
                        callStateHolder.callState.first { it != CallState.Idle }
                    }
                }
                .onFailure { e ->
                    Log.w(TAG, "Could not create the call", e)
                    if (closed.get()) return@onFailure
                    _setupFailed.trySend(Unit)
                    callStateHolder.failOutgoingCall(callId = "")
                }
        }
    }

    fun decline() = CallService.sendAction(context, CallService.ACTION_DECLINE)
    fun hangup() = CallService.sendAction(context, CallService.ACTION_HANGUP)
    fun toggleMute() = CallService.sendAction(context, CallService.ACTION_TOGGLE_MUTE)

    /** Move the call's audio to [route]. The OS decides when it actually lands; [uiControls] follows. */
    fun selectAudioRoute(route: CallAudioRoute) = CallService.sendSelectAudioRoute(context, route)

    /**
     * The call screen is closing for good. Its activity calls this as it finishes: [onCleared]
     * comes only with its onDestroy, which the system can hold back for seconds after Back.
     */
    fun onScreenClosed() {
        closed.set(true)
    }

    override fun onCleared() {
        onScreenClosed()
    }

    private companion object {
        const val TAG = "CallViewModel"
        const val HANDOVER_TIMEOUT_MS = 5_000L
    }
}
