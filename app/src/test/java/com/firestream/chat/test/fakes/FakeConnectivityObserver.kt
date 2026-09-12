package com.firestream.chat.test.fakes

import com.firestream.chat.domain.util.ConnectivityObserver
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/** Drivable [ConnectivityObserver] — starts online; call [setOnline] to flip it. */
class FakeConnectivityObserver(online: Boolean = true) : ConnectivityObserver {

    private val _isOnline = MutableStateFlow(online)
    override val isOnline: StateFlow<Boolean> = _isOnline

    fun setOnline(online: Boolean) {
        _isOnline.value = online
    }
}
