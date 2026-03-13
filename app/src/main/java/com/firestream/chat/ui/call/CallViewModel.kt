package com.firestream.chat.ui.call

import android.content.Context
import androidx.lifecycle.ViewModel
import com.firestream.chat.data.call.CallService
import com.firestream.chat.data.call.CallStateHolder
import com.firestream.chat.domain.model.CallState
import com.firestream.chat.domain.model.CallUiControls
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.StateFlow
import javax.inject.Inject

@HiltViewModel
class CallViewModel @Inject constructor(
    private val callStateHolder: CallStateHolder,
    @ApplicationContext private val context: Context
) : ViewModel() {

    val callState: StateFlow<CallState> = callStateHolder.callState
    val uiControls: StateFlow<CallUiControls> = callStateHolder.uiControls

    fun answer() = CallService.sendAction(context, CallService.ACTION_ANSWER)
    fun decline() = CallService.sendAction(context, CallService.ACTION_DECLINE)
    fun hangup() = CallService.sendAction(context, CallService.ACTION_HANGUP)
    fun toggleMute() = CallService.sendAction(context, CallService.ACTION_TOGGLE_MUTE)
    fun toggleSpeaker() = CallService.sendAction(context, CallService.ACTION_TOGGLE_SPEAKER)
}
