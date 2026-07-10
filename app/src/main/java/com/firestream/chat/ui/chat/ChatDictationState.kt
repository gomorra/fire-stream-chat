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

internal data class DictationApply(
    val newText: String,
    val newAnchor: Int,
    val newLastLen: Int,
    val newCursorIndex: Int,
)

/**
 * Applies one dictation commit to the composer text, or returns null when the
 * commit must be ignored.
 *
 * Gate rule: a commit may still *extend* an existing dictation region right
 * after listening ended — the refined trailing Final of a normal stop is
 * emitted before the isListening flip but processed after it. A commit may
 * never *create* a region (`anchor < 0`) while not listening: that is the
 * stale/leaked-recognizer case, and applying it would overwrite whatever the
 * user is typing — the "composer only accepts one letter" failure mode.
 */
internal fun applyDictationCommit(
    currentText: String,
    cursorStart: Int,
    anchor: Int,
    lastLen: Int,
    commitText: String,
    isListening: Boolean,
): DictationApply? {
    val effectiveAnchor = if (anchor < 0) {
        if (!isListening) return null
        cursorStart.coerceIn(0, currentText.length)
    } else {
        anchor
    }
    val safeAnchor = effectiveAnchor.coerceIn(0, currentText.length)
    val before = currentText.substring(0, safeAnchor)
    val after = currentText.substring((safeAnchor + lastLen).coerceAtMost(currentText.length))
    val newText = before + commitText + after
    return DictationApply(
        newText = newText,
        newAnchor = effectiveAnchor,
        newLastLen = commitText.length,
        newCursorIndex = (safeAnchor + commitText.length).coerceIn(0, newText.length),
    )
}
