package com.firestream.chat.ui.chat

import com.firestream.chat.domain.model.AppError

internal data class DictationState(
    val isAvailable: Boolean = true,
    val isOnDeviceAvailable: Boolean = false,
    val isListening: Boolean = false,
    val error: AppError? = null,
)
// audioLevel lives on its own StateFlow on ChatDictationManager — folding it
// into ChatUiState would recompose the whole ChatScreen ~10×/sec during recording.

internal sealed interface DictationCommit {
    val text: String
    data class Partial(override val text: String) : DictationCommit
    data class Final(override val text: String) : DictationCommit
}
