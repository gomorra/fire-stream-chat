package com.firestream.chat.ui.main

import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput

/**
 * Triggers [onSwipe] when the user drags horizontally past an 80dp threshold.
 * [swipeRight] = true fires on rightward drag; false fires on leftward drag.
 */
internal fun Modifier.swipeToNavigate(
    swipeRight: Boolean,
    onSwipe: () -> Unit
): Modifier = pointerInput(swipeRight, onSwipe) {
    val thresholdPx = with(this) { 80f * density }
    var accumulator = 0f
    detectHorizontalDragGestures(
        onDragEnd = { accumulator = 0f },
        onDragCancel = { accumulator = 0f }
    ) { _, dragAmount ->
        accumulator += dragAmount
        val triggered = if (swipeRight) accumulator > thresholdPx else accumulator < -thresholdPx
        if (triggered) {
            accumulator = 0f
            onSwipe()
        }
    }
}
