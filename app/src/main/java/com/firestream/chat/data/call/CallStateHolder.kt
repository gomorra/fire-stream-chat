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
     * Clear the way for an outgoing call. Returns false while another call is ongoing; the caller
     * must not place a second call over it.
     *
     * Otherwise forgets the finished call. The call screen finishes itself 1.5 s after it sees
     * Ended, so a stale Ended would close the new call's screen while the call is still being set up.
     */
    fun prepareOutgoingCall(): Boolean {
        if (_callState.value.isOngoing) return false
        reset()
        return true
    }

    /**
     * End an outgoing call that failed before [CallService] took it over, so the call screen shows
     * the end and closes. Replaces only the Idle that [prepareOutgoingCall] left. An incoming call
     * can start ringing during the setup, and its state must stay.
     */
    fun failOutgoingCall(callId: String) {
        _callState.compareAndSet(CallState.Idle, CallState.Ended(callId, EndReason.ERROR))
    }

    fun reset() {
        _callState.value = CallState.Idle
        _uiControls.value = CallUiControls()
    }
}
