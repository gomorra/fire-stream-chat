package com.firestream.chat.ui.chat

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.AutoAwesome
import androidx.compose.material.icons.outlined.DarkMode
import androidx.compose.material.icons.outlined.EditCalendar
import androidx.compose.material.icons.outlined.Schedule
import androidx.compose.material.icons.outlined.WbSunny
import androidx.compose.material3.Button
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TimePicker
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.material3.rememberTimePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import com.firestream.chat.domain.model.Message
import com.firestream.chat.domain.model.MessageType
import com.firestream.chat.domain.reminder.SnoozePreset
import com.firestream.chat.domain.reminder.SnoozePresets
import java.text.SimpleDateFormat
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset
import java.util.Date
import java.util.Locale

private val presetTimeFormat = SimpleDateFormat("EEE, MMM d · h:mm a", Locale.getDefault())

/**
 * The message-snooze picker: [SnoozePresets.compute] rows plus a "Pick date & time"
 * row that opens Material3's [DatePicker] then a custom [TimePicker] dialog (M3 has
 * no built-in TimePickerDialog). No detection source yet — [SnoozePresets.compute]
 * is called with `detectedFireAtMs = null` here; Step 7 wires smart-time detection
 * by launching a suspend detect() call before this composition and passing its
 * result into the same call site, so this is a one-line change later.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun SnoozePickerSheet(
    message: Message,
    onDismiss: () -> Unit,
    onTimeSelected: (Long) -> Unit,
) {
    val sheetState = rememberModalBottomSheetState()
    val zoneId = remember { ZoneId.systemDefault() }
    val presets = remember { SnoozePresets.compute(System.currentTimeMillis(), zoneId) }

    var showDatePicker by remember { mutableStateOf(false) }
    var showTimePicker by remember { mutableStateOf(false) }
    var pendingDateMillis by remember { mutableStateOf<Long?>(null) }
    var pastTimeError by remember { mutableStateOf(false) }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp)
                .padding(bottom = 32.dp),
        ) {
            Text(
                text = "Remind me…",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
            )
            Spacer(modifier = Modifier.height(4.dp))
            QuotedMessagePreview(message)
            Spacer(modifier = Modifier.height(16.dp))

            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                presets.forEach { preset ->
                    PresetRow(
                        preset = preset,
                        onClick = { onTimeSelected(preset.fireAtMs) },
                    )
                }
                PickDateTimeRow(
                    onClick = {
                        pastTimeError = false
                        showDatePicker = true
                    },
                )
            }

            if (pastTimeError) {
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = "That time has already passed — pick a time in the future.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.padding(horizontal = 4.dp),
                )
            }
        }
    }

    if (showDatePicker) {
        val datePickerState = rememberDatePickerState(
            initialSelectedDateMillis = System.currentTimeMillis(),
        )
        DatePickerDialog(
            onDismissRequest = { showDatePicker = false },
            confirmButton = {
                TextButton(
                    onClick = {
                        pendingDateMillis = datePickerState.selectedDateMillis
                        showDatePicker = false
                        showTimePicker = true
                    },
                    enabled = datePickerState.selectedDateMillis != null,
                ) { Text("Next") }
            },
            dismissButton = {
                TextButton(onClick = { showDatePicker = false }) { Text("Cancel") }
            },
        ) {
            DatePicker(state = datePickerState)
        }
    }

    if (showTimePicker) {
        val dateMillis = pendingDateMillis
        val timePickerState = rememberTimePickerState(is24Hour = false)
        TimePickerDialog(
            state = timePickerState,
            onDismissRequest = { showTimePicker = false },
            onConfirm = {
                showTimePicker = false
                val fireAtMs = dateMillis?.let { resolveFireAtMs(it, timePickerState.hour, timePickerState.minute, zoneId) }
                if (fireAtMs == null || fireAtMs <= System.currentTimeMillis()) {
                    pastTimeError = true
                } else {
                    onTimeSelected(fireAtMs)
                }
            },
        )
    }
}

/**
 * Combines the UTC-midnight [dateMillis] from [DatePickerState.selectedDateMillis]
 * with the picked [hour]/[minute] in [zoneId] — the date picker always operates in
 * UTC internally regardless of display locale, so the date component must be read
 * out via [ZoneOffset.UTC] before re-anchoring to the local zone.
 */
private fun resolveFireAtMs(dateMillis: Long, hour: Int, minute: Int, zoneId: ZoneId): Long {
    val localDate = Instant.ofEpochMilli(dateMillis).atZone(ZoneOffset.UTC).toLocalDate()
    return localDate.atTime(hour, minute).atZone(zoneId).toInstant().toEpochMilli()
}

@Composable
private fun QuotedMessagePreview(message: Message) {
    val snippet = when (message.type) {
        MessageType.IMAGE -> message.content.take(80).ifBlank { "Photo" }
        MessageType.VIDEO -> message.content.take(80).ifBlank { "Video" }
        else -> message.content.take(80)
    }
    if (snippet.isBlank()) return
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f))
            .padding(horizontal = 12.dp, vertical = 8.dp),
    ) {
        Text(
            text = snippet,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 2,
        )
    }
}

@Composable
private fun PresetRow(preset: SnoozePreset, onClick: () -> Unit) {
    val (icon, label) = when (preset.kind) {
        SnoozePreset.Kind.DETECTED -> Icons.Outlined.AutoAwesome to "Detected time"
        SnoozePreset.Kind.IN_1_HOUR -> Icons.Outlined.Schedule to "In 1 hour"
        SnoozePreset.Kind.THIS_EVENING -> Icons.Outlined.DarkMode to "This evening"
        SnoozePreset.Kind.TOMORROW_MORNING -> Icons.Outlined.WbSunny to "Tomorrow morning"
    }
    val iconTint = if (preset.kind == SnoozePreset.Kind.DETECTED) {
        MaterialTheme.colorScheme.tertiary
    } else {
        MaterialTheme.colorScheme.primary
    }
    val iconContainer = if (preset.kind == SnoozePreset.Kind.DETECTED) {
        MaterialTheme.colorScheme.tertiaryContainer
    } else {
        MaterialTheme.colorScheme.primaryContainer
    }
    PickerRow(
        icon = icon,
        iconTint = iconTint,
        iconContainer = iconContainer,
        label = label,
        subtitle = presetTimeFormat.format(Date(preset.fireAtMs)),
        onClick = onClick,
    )
}

@Composable
private fun PickDateTimeRow(onClick: () -> Unit) {
    PickerRow(
        icon = Icons.Outlined.EditCalendar,
        iconTint = MaterialTheme.colorScheme.onSurfaceVariant,
        iconContainer = MaterialTheme.colorScheme.surfaceVariant,
        label = "Pick date & time",
        subtitle = null,
        onClick = onClick,
    )
}

@Composable
private fun PickerRow(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    iconTint: androidx.compose.ui.graphics.Color,
    iconContainer: androidx.compose.ui.graphics.Color,
    label: String,
    subtitle: String?,
    onClick: () -> Unit,
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 8.dp, vertical = 10.dp),
    ) {
        Box(
            modifier = Modifier
                .size(40.dp)
                .clip(CircleShape)
                .background(iconContainer),
            contentAlignment = Alignment.Center,
        ) {
            Icon(imageVector = icon, contentDescription = null, tint = iconTint, modifier = Modifier.size(20.dp))
        }
        Spacer(modifier = Modifier.width(12.dp))
        Column {
            Text(
                text = label,
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurface,
                fontWeight = FontWeight.Medium,
            )
            if (subtitle != null) {
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

/**
 * Material3 doesn't ship a TimePickerDialog (only DatePickerDialog) — this is the
 * standard wrap-TimePicker-in-a-Dialog pattern.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun TimePickerDialog(
    state: androidx.compose.material3.TimePickerState,
    onDismissRequest: () -> Unit,
    onConfirm: () -> Unit,
) {
    Dialog(onDismissRequest = onDismissRequest) {
        Box(
            modifier = Modifier
                .clip(RoundedCornerShape(28.dp))
                .background(MaterialTheme.colorScheme.surfaceContainerHigh)
                .padding(24.dp),
        ) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text(
                    text = "Select time",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(modifier = Modifier.height(16.dp))
                TimePicker(state = state)
                Spacer(modifier = Modifier.height(8.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End,
                ) {
                    TextButton(onClick = onDismissRequest) { Text("Cancel") }
                    Spacer(modifier = Modifier.width(8.dp))
                    Button(onClick = onConfirm) { Text("Set reminder") }
                }
            }
        }
    }
}
