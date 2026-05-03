// region: AGENT-NOTE
// Responsibility: hh:mm:ss wheel picker mounted above the composer when the user
//   selects `.timer.set`. Submits a CommandPayload.Timer to ChatViewModel via the
//   onSend lambda passed from ChatScreen.
// Owns: ephemeral picker state (HH / MM / SS) via TimerSetWidgetState, scoped per
//   Render call with `remember { ... }`. State holder is a plain class — see
//   TimerSetWidgetState.kt — kept JVM-testable instead of @HiltViewModel.
// Collaborators: TimerSetCommand (which references this @Singleton widget through
//   the ChatCommand registry); ChatScreen (renders the widget when
//   uiState.commands.activeWidget is non-null and routes onSend to
//   ChatViewModel.onCommandSubmit).
// Don't put here: alarm scheduling (that's data/timer/TimerAlarmScheduler in
//   Step 5), repository calls (live in ChatViewModel.onCommandSubmit), or
//   navigation. Per chat-manager slice-ownership pattern, the widget never reads
//   or mutates ChatUiState directly — it only emits CommandPayload via onSend.
// endregion

package com.firestream.chat.ui.chat.widget

import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.snapping.rememberSnapFlingBehavior
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.firestream.chat.domain.command.ChatCommandWidget
import com.firestream.chat.domain.command.CommandPayload
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filter
import javax.inject.Inject
import javax.inject.Singleton

/**
 * `.timer.set` widget — three side-by-side wheel pickers for HH / MM / SS plus a
 * Cancel/Send row. Caption text typed in the composer (passed in as
 * `composerText`) is forwarded as the timer caption when sent.
 */
@Singleton
class TimerSetWidget @Inject constructor() : ChatCommandWidget {

    @Composable
    override fun Render(
        chatId: String,
        composerText: String,
        onSend: (CommandPayload) -> Unit,
        onCancel: () -> Unit,
    ) {
        val state = remember { TimerSetWidgetState() }
        val captionForSend = remember(composerText) { extractCaption(composerText) }

        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 8.dp, vertical = 4.dp)
                .shadow(elevation = 6.dp, shape = RoundedCornerShape(12.dp))
                .clip(RoundedCornerShape(12.dp))
                .background(MaterialTheme.colorScheme.surface)
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            HeaderRow(state)
            WheelRow(state)
            ActionRow(
                isSendEnabled = state.isSendEnabled,
                onCancel = onCancel,
                onSend = {
                    onSend(CommandPayload.Timer(durationMs = state.durationMs, caption = captionForSend))
                },
            )
        }
    }
}

@Composable
private fun HeaderRow(state: TimerSetWidgetState) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(
            text = "Set timer",
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.onSurface,
        )
        Text(
            text = formatPreview(state.hours, state.minutes, state.seconds),
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.primary,
            fontWeight = FontWeight.Medium,
        )
    }
}

@Composable
private fun WheelRow(state: TimerSetWidgetState) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceEvenly,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        TimerWheel(
            label = "h",
            range = 0..TimerSetWidgetState.MAX_HOURS,
            selected = state.hours,
            onSelected = { state.hours = it },
            modifier = Modifier.weight(1f),
        )
        TimerWheel(
            label = "m",
            range = 0..TimerSetWidgetState.MAX_MINUTES_OR_SECONDS,
            selected = state.minutes,
            onSelected = { state.minutes = it },
            modifier = Modifier.weight(1f),
        )
        TimerWheel(
            label = "s",
            range = 0..TimerSetWidgetState.MAX_MINUTES_OR_SECONDS,
            selected = state.seconds,
            onSelected = { state.seconds = it },
            modifier = Modifier.weight(1f),
        )
    }
}

private val WheelHeight = 144.dp     // 3 visible items × 48.dp
private val WheelItemHeight = 48.dp

@Composable
private fun TimerWheel(
    label: String,
    range: IntRange,
    selected: Int,
    onSelected: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    val values = remember(range) { range.toList() }
    val listState = rememberLazyListState(initialFirstVisibleItemIndex = selected)
    val flingBehavior = rememberSnapFlingBehavior(lazyListState = listState)

    // Emit the new selection only after the fling has settled. snapshotFlow on
    // isScrollInProgress avoids spamming onSelected during the scroll itself,
    // which would clamp/jitter the value mid-gesture.
    LaunchedEffect(listState, values) {
        snapshotFlow { listState.isScrollInProgress }
            .filter { !it }
            .distinctUntilChanged()
            .collect {
                val centered = listState.firstVisibleItemIndex
                val value = values.getOrNull(centered) ?: return@collect
                if (value != selected) onSelected(value)
            }
    }

    Box(
        modifier = modifier.height(WheelHeight),
        contentAlignment = Alignment.Center,
    ) {
        // Selection band — thin dividers above and below the centered item.
        Column(modifier = Modifier.fillMaxWidth()) {
            Spacer(Modifier.height(WheelItemHeight))
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            Spacer(Modifier.height(WheelItemHeight))
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
        }

        LazyColumn(
            state = listState,
            flingBehavior = flingBehavior,
            modifier = Modifier.fillMaxWidth().height(WheelHeight),
            // Top + bottom padding equal to one item so the first/last value can scroll
            // to the center band.
            contentPadding = androidx.compose.foundation.layout.PaddingValues(vertical = WheelItemHeight),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            items(values, key = { it }) { value ->
                WheelItem(value = value, label = label, isCentered = value == selected)
            }
        }
    }
}

@Composable
private fun WheelItem(value: Int, label: String, isCentered: Boolean) {
    val color = if (isCentered) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant
    val style = if (isCentered) MaterialTheme.typography.headlineSmall else MaterialTheme.typography.titleMedium
    Box(
        modifier = Modifier.fillMaxWidth().height(WheelItemHeight),
        contentAlignment = Alignment.Center,
    ) {
        Row(verticalAlignment = Alignment.Bottom) {
            Text(
                text = "%02d".format(value),
                style = style,
                color = color,
                fontWeight = if (isCentered) FontWeight.SemiBold else FontWeight.Normal,
                textAlign = TextAlign.Center,
            )
            Spacer(Modifier.width(2.dp))
            Text(
                text = label,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun ActionRow(
    isSendEnabled: Boolean,
    onCancel: () -> Unit,
    onSend: () -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        OutlinedButton(
            onClick = onCancel,
            modifier = Modifier.weight(1f),
        ) { Text("Cancel") }
        Button(
            onClick = onSend,
            enabled = isSendEnabled,
            modifier = Modifier.weight(1f),
        ) { Text("Send") }
    }
}

private fun formatPreview(hours: Int, minutes: Int, seconds: Int): String =
    "%02d:%02d:%02d".format(hours, minutes, seconds)

/**
 * Strips the leading `.command.subcommand` chip text from the composer string
 * and returns the residual caption (or null if blank). The widget mounts when
 * text is exactly `.timer.set`, so anything trailing it is the user's caption.
 */
internal fun extractCaption(composerText: String): String? {
    if (!composerText.startsWith(".")) return composerText.takeIf { it.isNotBlank() }
    // Drop the chip portion: ".timer.set" or ".timer.set " etc.
    val firstSpace = composerText.indexOf(' ')
    val residual = if (firstSpace == -1) "" else composerText.substring(firstSpace + 1)
    return residual.trim().takeIf { it.isNotBlank() }
}
