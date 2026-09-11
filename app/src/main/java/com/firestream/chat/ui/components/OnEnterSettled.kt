package com.firestream.chat.ui.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.AnimatedVisibilityScope
import androidx.compose.animation.EnterExitState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.snapshotFlow
import kotlinx.coroutines.flow.first

/**
 * Runs [onSettled] once, when the enclosing [AnimatedVisibility]'s enter
 * transition has landed — that is, once its content is drawn at full opacity
 * rather than still fading in.
 *
 * An enter that a dismissal interrupts counts as landed too: the transition
 * steps its current state to Visible before it turns around, so a caller that
 * must act exactly once per open is never left waiting.
 *
 * Exists for one hand-off: an overlay opening *over* another that shows the
 * same content (the send preview over the fullscreen viewer it was opened
 * from). Closing the lower overlay in the same frame as opening the upper one
 * cross-fades two copies of the same photo over whatever sits beneath both,
 * and the photo visibly dips towards it. Keeping the lower overlay until the
 * upper has settled makes the fade invisible — the same pixels are underneath
 * the whole way — and lets the lower one close under an opaque cover.
 */
@Composable
fun AnimatedVisibilityScope.OnEnterSettled(onSettled: () -> Unit) {
    val current by rememberUpdatedState(onSettled)
    LaunchedEffect(Unit) {
        snapshotFlow { transition.currentState }.first { it == EnterExitState.Visible }
        current()
    }
}
