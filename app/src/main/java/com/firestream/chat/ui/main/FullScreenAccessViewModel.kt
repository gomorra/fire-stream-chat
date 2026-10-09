package com.firestream.chat.ui.main

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.firestream.chat.data.call.FullScreenIntentAccess
import com.firestream.chat.data.local.PreferencesDataStore
import com.firestream.chat.di.ApplicationScope
import com.firestream.chat.domain.util.shouldPromptForFullScreenAccess
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * Decides whether the main screen asks for the access *Full screen notifications*.
 *
 * The system tells nobody when the access changes. The screen calls [refresh] when it enters the
 * composition and every time it comes to the foreground, and [showPrompt] holds the answer before
 * [refresh] returns.
 */
@HiltViewModel
class FullScreenAccessViewModel @Inject constructor(
    private val access: FullScreenIntentAccess,
    private val preferencesDataStore: PreferencesDataStore,
    @ApplicationScope private val appScope: CoroutineScope,
) : ViewModel() {

    // Null until the remembered "Not now" has been read. The prompt stays away until then, so
    // it never flashes for a user who put it off.
    private var dismissed: Boolean? = null

    private val _showPrompt = MutableStateFlow(false)
    val showPrompt: StateFlow<Boolean> = _showPrompt.asStateFlow()

    init {
        viewModelScope.launch {
            preferencesDataStore.fullScreenAccessPromptDismissedFlow.collect {
                dismissed = it
                refresh()
            }
        }
    }

    fun refresh() {
        val dismissed = dismissed ?: return
        // Once put off, the answer is no, and the system is not asked.
        _showPrompt.value = !dismissed &&
            shouldPromptForFullScreenAccess(access.apiLevel, access.isGranted(), dismissed)
    }

    fun openSettings() = access.openSettings()

    // On the application scope: the write must land even when the screen goes right after the tap.
    fun dismiss() {
        appScope.launch { preferencesDataStore.setFullScreenAccessPromptDismissed() }
    }
}
