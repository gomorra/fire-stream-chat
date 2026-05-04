// region: AGENT-NOTE
// Responsibility: render the inner content of a TIMER message bubble — alarm-clock
//   icon plus state-aware label (live countdown when RUNNING, "Timer ended" when
//   COMPLETED, struck-through duration when CANCELLED) and an optional caption.
//   Mounts inside MessageBubble's content Column so it inherits the bubble shape,
//   long-press menu, swipe-to-react, and reactions row.
// Owns: nothing — pure render of the Message slice. The countdown ticker is a
//   produceState driven solely by message.timerStartedAtMs / timerDurationMs /
//   timerState. State flips (CANCELLED / COMPLETED) come from the server-canonical
//   message via the Room flow, and the alarm-firing path is owned by
//   TimerAlarmReceiver in data/timer.
// Collaborators: MessageBubble (the wrapping bubble), ChatTimerReactor (schedules /
//   cancels alarms based on timerState transitions; lives in this same package).
// Don't put here: scheduling logic (TimerAlarmScheduler), Room writes (the
//   reactor reaches MessageRepository.cancelTimer via ChatViewModel), or the
//   long-press menu UI (lives in MessageBubble's existing DropdownMenu, just
//   conditionally exposes a Cancel-timer entry through MessageBubbleCallbacks).
// endregion

package com.firestream.chat.ui.chat

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AccessAlarm
import androidx.compose.material.icons.filled.AlarmOff
import androidx.compose.material.icons.filled.NotificationsOff
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PauseCircleOutline
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import com.firestream.chat.domain.model.Message
import com.firestream.chat.domain.model.TimerState
import kotlinx.coroutines.delay

/**
 * Remaining ms until [Message.timerStartedAtMs] + [Message.timerDurationMs].
 * Returns 0 when either field is missing or fire time has already passed —
 * the bubble shows 00:00 (rather than a negative value) until the canonical
 * state flips to COMPLETED.
 */
internal fun computeRemainingMs(message: Message, nowMs: Long = System.currentTimeMillis()): Long {
    val started = message.timerStartedAtMs ?: return 0L
    val duration = message.timerDurationMs ?: return 0L
    return (started + duration - nowMs).coerceAtLeast(0L)
}

/**
 * Format a duration as `hh:mm:ss` if it's ≥ 1 hour, else `mm:ss`. Used for both
 * the live countdown and the static "original duration" label on COMPLETED /
 * CANCELLED bubbles, so the two read consistently.
 */
internal fun formatTimerDuration(ms: Long): String {
    val totalSeconds = (ms / 1000).coerceAtLeast(0L)
    val hours = totalSeconds / 3600
    val minutes = (totalSeconds % 3600) / 60
    val seconds = totalSeconds % 60
    return if (hours > 0) {
        "%02d:%02d:%02d".format(hours, minutes, seconds)
    } else {
        "%02d:%02d".format(minutes, seconds)
    }
}

@Composable
internal fun TimerBubbleContent(
    message: Message,
    textColor: Color,
    onPauseTimer: ((Long) -> Unit)? = null,
    onResumeTimer: (() -> Unit)? = null,
) {
    val state = message.timerState ?: TimerState.RUNNING
    val durationMs = message.timerDurationMs ?: 0L

    // Live countdown — only ticks while RUNNING. produceState restarts whenever
    // the canonical state or fire time changes (e.g. cancellation arrives, or a
    // re-keying after a Firestore round-trip stamps a new timerStartedAtMs).
    val remainingMs by produceState(
        initialValue = if (state == TimerState.RUNNING) computeRemainingMs(message) else 0L,
        key1 = state,
        key2 = message.timerStartedAtMs,
        key3 = message.timerDurationMs,
    ) {
        if (state != TimerState.RUNNING) return@produceState
        while (true) {
            val remaining = computeRemainingMs(message)
            value = remaining
            if (remaining <= 0L) break
            delay(1000L)
        }
    }

    val accentColor = when (state) {
        TimerState.RUNNING -> textColor
        TimerState.PAUSED -> textColor.copy(alpha = 0.8f)
        TimerState.COMPLETED -> textColor.copy(alpha = 0.7f)
        TimerState.CANCELLED -> textColor.copy(alpha = 0.6f)
    }
    val icon = when (state) {
        TimerState.RUNNING -> Icons.Default.AccessAlarm
        TimerState.PAUSED -> Icons.Default.PauseCircleOutline
        TimerState.COMPLETED, TimerState.CANCELLED -> Icons.Default.AlarmOff
    }

    Column {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.Start) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = accentColor,
                modifier = Modifier.size(24.dp),
            )
            Spacer(modifier = Modifier.width(8.dp))
            Column {
                when (state) {
                    TimerState.RUNNING -> {
                        val display = if (remainingMs > 0L) remainingMs else 0L
                        Text(
                            text = formatTimerDuration(display),
                            color = accentColor,
                            style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.SemiBold),
                        )
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                text = "Timer · ${formatTimerDuration(durationMs)}",
                                color = accentColor.copy(alpha = 0.7f),
                                style = MaterialTheme.typography.labelSmall,
                            )
                            if (message.timerSilent) {
                                Spacer(modifier = Modifier.width(4.dp))
                                Icon(
                                    imageVector = Icons.Default.NotificationsOff,
                                    contentDescription = "Silent timer",
                                    tint = accentColor.copy(alpha = 0.7f),
                                    modifier = Modifier.size(12.dp)
                                )
                            }
                        }
                    }
                    TimerState.PAUSED -> {
                        val display = message.timerRemainingMs ?: 0L
                        Text(
                            text = formatTimerDuration(display),
                            color = accentColor,
                            style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.SemiBold),
                        )
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                text = "Paused · ${formatTimerDuration(durationMs)}",
                                color = accentColor.copy(alpha = 0.7f),
                                style = MaterialTheme.typography.labelSmall,
                            )
                            if (message.timerSilent) {
                                Spacer(modifier = Modifier.width(4.dp))
                                Icon(
                                    imageVector = Icons.Default.NotificationsOff,
                                    contentDescription = "Silent timer",
                                    tint = accentColor.copy(alpha = 0.7f),
                                    modifier = Modifier.size(12.dp)
                                )
                            }
                        }
                    }
                    TimerState.COMPLETED -> {
                        Text(
                            text = "Timer ended",
                            color = accentColor,
                            style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold),
                        )
                        Text(
                            text = formatTimerDuration(durationMs),
                            color = accentColor.copy(alpha = 0.7f),
                            style = MaterialTheme.typography.labelSmall,
                        )
                    }
                    TimerState.CANCELLED -> {
                        Text(
                            text = "Cancelled",
                            color = accentColor,
                            style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold),
                        )
                        Text(
                            text = formatTimerDuration(durationMs),
                            color = accentColor.copy(alpha = 0.7f),
                            style = MaterialTheme.typography.labelSmall.copy(
                                textDecoration = TextDecoration.LineThrough,
                            ),
                        )
                    }
                }
            }
            if (state == TimerState.RUNNING && onPauseTimer != null) {
                Spacer(modifier = Modifier.width(16.dp))
                androidx.compose.material3.IconButton(
                    onClick = { onPauseTimer(remainingMs) },
                    modifier = Modifier.size(32.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.Pause,
                        contentDescription = "Pause timer",
                        tint = accentColor
                    )
                }
            } else if (state == TimerState.PAUSED && onResumeTimer != null) {
                Spacer(modifier = Modifier.width(16.dp))
                androidx.compose.material3.IconButton(
                    onClick = { onResumeTimer() },
                    modifier = Modifier.size(32.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.PlayArrow,
                        contentDescription = "Resume timer",
                        tint = accentColor
                    )
                }
            }
        }
        if (message.content.isNotBlank()) {
            Spacer(modifier = Modifier.height(6.dp))
            Text(
                text = message.content,
                color = textColor,
                style = MaterialTheme.typography.bodyMedium,
            )
        }
    }
}
