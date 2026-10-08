package com.firestream.chat.data.call

import com.firestream.chat.domain.model.CallAudioRoute
import com.firestream.chat.domain.model.CallState
import com.firestream.chat.domain.model.CallUiControls
import com.firestream.chat.domain.model.EndReason
import com.firestream.chat.domain.model.isOngoing
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.flow.updateAndGet
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class CallStateHolder @Inject constructor() {

    private val _callState = MutableStateFlow<CallState>(CallState.Idle)
    val callState: StateFlow<CallState> = _callState.asStateFlow()

    private val _uiControls = MutableStateFlow(CallUiControls())
    val uiControls: StateFlow<CallUiControls> = _uiControls.asStateFlow()

    /**
     * Publish [state]. A state that starts a call also resets the controls first. A call starts
     * when an ongoing state follows Idle or Ended.
     *
     * The controls belong to one call. The next call's audio track is created enabled, so a mute
     * left over from the previous call would show "muted" over a live microphone.
     */
    fun updateState(state: CallState) {
        if (state.isOngoing && !_callState.value.isOngoing) _uiControls.value = CallUiControls()
        _callState.value = state
    }

    fun updateControls(controls: CallUiControls) {
        _uiControls.value = controls
    }

    /**
     * Flip mute and return the new value, so the caller applies to the audio track exactly what the
     * UI shows. Atomic, like [updateAudioRoutes], so neither writes back a stale copy of the other's
     * field.
     */
    fun toggleMute(): Boolean = _uiControls.updateAndGet { it.copy(isMuted = !it.isMuted) }.isMuted

    /**
     * Publish the routes the OS offers and the one it is actually playing through. A null [current]
     * means the OS has not reported a route yet, and leaves the displayed one alone rather than
     * guessing. Leaves mute alone either way.
     */
    fun updateAudioRoutes(available: List<CallAudioRoute>, current: CallAudioRoute?) {
        _uiControls.update {
            it.copy(audioRoute = current ?: it.audioRoute, availableRoutes = available)
        }
    }

    /**
     * Start placing a call to [calleeId]: publish [CallState.Placing], with fresh controls. Returns
     * false while another call is ongoing, a placing included. The caller must not place a second
     * call over it.
     *
     * Replaces a finished call. The call screen finishes itself 1.5 s after it sees Ended, so a
     * stale Ended would close the new call's screen while the call is being set up.
     */
    fun prepareOutgoingCall(calleeId: String, calleeName: String, calleeAvatarUrl: String?): Boolean {
        val placing = CallState.Placing(calleeId, calleeName, calleeAvatarUrl)
        while (true) {
            val current = _callState.value
            if (current.isOngoing) return false
            if (_callState.compareAndSet(current, placing)) {
                _uiControls.value = CallUiControls()
                return true
            }
        }
    }

    /**
     * Record that the call being placed now has the document [callId]. Returns false when the
     * placing was cancelled or failed meanwhile, or already has a document.
     */
    fun placingCreated(callId: String): Boolean = replacePlacing(callId = null) { it.copy(callId = callId) }

    /**
     * Let the call service take over the placed call as [ringing]. Returns false unless the call
     * being placed is the one [ringing] names: its placing was cancelled or timed out first, and the
     * service must end that call instead of ringing.
     */
    fun takeOverPlacing(ringing: CallState.OutgoingRinging): Boolean =
        replacePlacing(ringing.callId) { ringing }

    /**
     * End the placing of [callId] with an error, so the call screen shows the end and closes. Pass
     * null before the call's document exists. Returns false, and changes nothing, unless that call
     * is being placed: an incoming call that rang meanwhile keeps its state.
     */
    fun failPlacing(callId: String?): Boolean =
        replacePlacing(callId) { CallState.Ended(callId ?: "", EndReason.ERROR) }

    /** Cancel the call being placed, at whatever stage. Returns false when no call is being placed. */
    fun cancelPlacing(): Boolean {
        while (true) {
            val current = _callState.value as? CallState.Placing ?: return false
            val ended = CallState.Ended(current.callId ?: "", EndReason.HANGUP)
            if (_callState.compareAndSet(current, ended)) return true
        }
    }

    /**
     * Replace the placing of [callId] with [next]. Atomic: the setup on the application scope and
     * the service on the main thread race for the same placing, and exactly one of them wins it.
     */
    private inline fun replacePlacing(callId: String?, next: (CallState.Placing) -> CallState): Boolean {
        while (true) {
            val current = _callState.value
            if (current !is CallState.Placing || current.callId != callId) return false
            if (_callState.compareAndSet(current, next(current))) return true
        }
    }
}
