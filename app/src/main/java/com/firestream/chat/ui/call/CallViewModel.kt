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
import kotlinx.coroutines.async
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
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

    /** The call this screen placed last: its placing, and the chat it is in. Main thread only. */
    private var placed: PlacedCall? = null

    private class PlacedCall(val placingId: Long, val chatId: String)

    /**
     * Set when the call screen closes for good. A rotation keeps this ViewModel, so it is not a
     * close. Main thread only.
     */
    private var closed = false

    /**
     * Publish the call as being placed, then create it and hand it to [CallService]. Does nothing
     * while a call is ongoing, this screen's own placing included.
     *
     * The setup runs on the application scope. The call document rings the callee as soon as it
     * exists, so a setup cancelled halfway would leave them ringing. Each step is bounded, and a
     * failed step ends the placing before anything is written, so the placing never stays up to
     * block every later call.
     *
     * The setup touches only its own placing. A Cancel, or a closed screen, can end it at any
     * stage. Whoever ends a placing whose document exists also ends the call and records it,
     * because no service holds that call.
     */
    fun placeCall(calleeId: String, chatId: String, calleeName: String, calleeAvatarUrl: String?) {
        if (closed) return
        val placing = callStateHolder.startPlacing(calleeId, calleeName, calleeAvatarUrl) ?: return
        placed = PlacedCall(placing.placingId, chatId)
        appScope.launch { setUp(placing.placingId, calleeId, chatId, calleeName, calleeAvatarUrl) }
    }

    private suspend fun setUp(placingId: Long, calleeId: String, chatId: String, calleeName: String, calleeAvatarUrl: String?) {
        // The create goes on after a timeout, so a call it creates late is ended, not left ringing.
        val creating = appScope.async { callRepository.createCall(calleeId) }
        val created = withTimeoutOrNull(CREATE_TIMEOUT_MS) { creating.await() }
        if (created == null) {
            Log.w(TAG, "Creating the call took too long")
            if (callStateHolder.failPlacing(placingId)) _setupFailed.trySend(Unit)
            creating.await().onSuccess { callId -> endUnheldCall(callId, chatId, EndReason.ERROR) }
            return
        }
        val callId = created.getOrElse { e ->
            Log.w(TAG, "Could not create the call", e)
            if (callStateHolder.failPlacing(placingId)) _setupFailed.trySend(Unit)
            return
        }
        // Cancel, or a closed screen, ended the placing while the document was created.
        if (!callStateHolder.placingCreated(placingId, callId)) {
            endUnheldCall(callId, chatId, EndReason.HANGUP)
            return
        }
        try {
            CallService.startOutgoing(context, callId, chatId, calleeId, calleeName, calleeAvatarUrl)
        } catch (e: IllegalStateException) {
            // Android 12+ will not start a foreground service once the app is in the background,
            // and a slow setup can outlast the user leaving the app.
            Log.w(TAG, "Could not start the call service", e)
            if (callStateHolder.failPlacing(placingId)) endUnheldCall(callId, chatId, EndReason.ERROR)
            return
        }
        // The service takes the call over on the main thread a moment later. If it never does, the
        // placing ends here, and the service, if it starts at all, finds it gone and ends the call.
        val handedOver = withTimeoutOrNull(HANDOVER_TIMEOUT_MS) {
            callStateHolder.callState.first { it !is CallState.Placing || it.placingId != placingId }
        }
        if (handedOver == null && callStateHolder.failPlacing(placingId)) {
            endUnheldCall(callId, chatId, EndReason.ERROR)
        }
    }

    /**
     * End a call whose document exists but which no service holds, and record it in the chat. Its
     * push has already rung the callee, so they see a missed call and the caller sees no answer.
     */
    private suspend fun endUnheldCall(callId: String, chatId: String, reason: EndReason) {
        callRepository.endCall(callId, reason)
        callRepository.logCallMessage(chatId, reason, durationSeconds = 0)
    }

    fun decline() = CallService.sendAction(context, CallService.ACTION_DECLINE)
    /** Hang up. A call still being placed is cancelled here, because the service does not hold it yet. */
    fun hangup() {
        if (cancelPlacing()) return
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
        closed = true
        cancelPlacing()
    }

    /**
     * Cancel the call this screen is placing. Returns false when its placing is no longer up. A
     * placing whose document exists has rung the callee already, so its call is ended and
     * recorded here.
     */
    private fun cancelPlacing(): Boolean {
        val placed = placed ?: return false
        val cancelled = callStateHolder.cancelPlacing(placed.placingId) ?: return false
        cancelled.callId?.let { callId ->
            appScope.launch { endUnheldCall(callId, placed.chatId, EndReason.HANGUP) }
        }
        return true
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
