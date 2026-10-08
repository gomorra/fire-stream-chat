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
     * Create the call and hand it to [CallService]. The screen has published [CallState.Placing]
     * for it first, with [CallStateHolder.prepareOutgoingCall]. Does nothing while an earlier call
     * is still being set up.
     *
     * The setup runs on the application scope. The call document rings the callee as soon as it
     * exists, so a setup cancelled halfway would leave them ringing. A setup that finishes after
     * the screen closed ends its call instead, and touches no state the next screen may own.
     *
     * Both steps are bounded, so a stuck setup cannot leave the placing up and block every call.
     */
    fun placeCall(calleeId: String, chatId: String, calleeName: String, calleeAvatarUrl: String?) {
        if (setup?.isActive == true) return
        setup = appScope.launch {
            val created = withTimeoutOrNull(CREATE_TIMEOUT_MS) { callRepository.createCall(calleeId) }
                ?: Result.failure(IllegalStateException("Creating the call took too long"))
            created
                .onSuccess { callId ->
                    // The screen closed, or Cancel was pressed, while the document was created.
                    if (closed.get() || !callStateHolder.placingCreated(callId)) {
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
                        callStateHolder.failPlacing(callId)
                        return@onSuccess
                    }
                    // The service takes the call over on the main thread a moment later. If it
                    // never does, the placing must not stay up. The service then finds it gone
                    // and ends the call itself.
                    val handedOver = withTimeoutOrNull(HANDOVER_TIMEOUT_MS) {
                        callStateHolder.callState.first { it !is CallState.Placing || it.callId != callId }
                    }
                    if (handedOver == null && callStateHolder.failPlacing(callId)) {
                        callRepository.endCall(callId, EndReason.ERROR.name.lowercase())
                    }
                }
                .onFailure { e ->
                    Log.w(TAG, "Could not create the call", e)
                    // A closed screen cancelled its placing as it closed. The placing now up may
                    // be the next screen's.
                    if (closed.get()) return@onFailure
                    _setupFailed.trySend(Unit)
                    callStateHolder.failPlacing(callId = null)
                }
        }
    }

    fun decline() = CallService.sendAction(context, CallService.ACTION_DECLINE)
    /** Hang up. A call still being placed is cancelled here: the service does not hold it yet. */
    fun hangup() {
        if (callStateHolder.cancelPlacing()) return
        CallService.sendAction(context, CallService.ACTION_HANGUP)
    }
    fun toggleMute() = CallService.sendAction(context, CallService.ACTION_TOGGLE_MUTE)

    /** Move the call's audio to [route]. The OS decides when it actually lands; [uiControls] follows. */
    fun selectAudioRoute(route: CallAudioRoute) = CallService.sendSelectAudioRoute(context, route)

    /**
     * The call screen is closing for good. Its activity calls this as it finishes: [onCleared]
     * comes only with its onDestroy, which the system can hold back for seconds after Back.
     */
    fun onScreenClosed() {
        if (closed.getAndSet(true)) return
        callStateHolder.cancelPlacing()
    }

    override fun onCleared() {
        onScreenClosed()
    }

    internal companion object {
        private const val TAG = "CallViewModel"

        /** How long creating the call document may take before the placing fails. */
        const val CREATE_TIMEOUT_MS = 20_000L

        /** How long the call service may take to take over a created call. */
        const val HANDOVER_TIMEOUT_MS = 10_000L
    }
}
