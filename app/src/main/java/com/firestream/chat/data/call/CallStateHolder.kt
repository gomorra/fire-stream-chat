package com.firestream.chat.data.call

import com.firestream.chat.domain.model.CallAudioRoute
import com.firestream.chat.domain.model.CallState
import com.firestream.chat.domain.model.CallUiControls
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
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

    // Both mutate one slice of the same value: toggleMute() from the service's main thread,
    // updateAudioRoutes() from the route collector on Dispatchers.IO. Read-modify-write via
    // `.value =` would let one drop the other's field.
    fun toggleMute() {
        _uiControls.update { it.copy(isMuted = !it.isMuted) }
    }

    fun updateAudioRoutes(available: Collection<CallAudioRoute>, current: CallAudioRoute) {
        _uiControls.update {
            it.copy(audioRoute = current, availableRoutes = available.distinct().sorted())
        }
    }

    fun reset() {
        _callState.value = CallState.Idle
        _uiControls.value = CallUiControls()
    }
}
