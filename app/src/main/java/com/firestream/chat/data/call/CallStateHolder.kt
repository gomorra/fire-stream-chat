package com.firestream.chat.data.call

import com.firestream.chat.domain.model.CallState
import com.firestream.chat.domain.model.CallUiControls
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class CallStateHolder @Inject constructor() {

    private val _callState = MutableStateFlow<CallState>(CallState.Idle)
    val callState: StateFlow<CallState> = _callState.asStateFlow()

    private val _uiControls = MutableStateFlow(CallUiControls())
    val uiControls: StateFlow<CallUiControls> = _uiControls.asStateFlow()

    fun updateState(state: CallState) {
        _callState.value = state
    }

    fun updateControls(controls: CallUiControls) {
        _uiControls.value = controls
    }

    fun toggleMute() {
        _uiControls.value = _uiControls.value.copy(isMuted = !_uiControls.value.isMuted)
    }

    fun toggleSpeaker() {
        _uiControls.value = _uiControls.value.copy(isSpeakerOn = !_uiControls.value.isSpeakerOn)
    }

    fun reset() {
        _callState.value = CallState.Idle
        _uiControls.value = CallUiControls()
    }
}
