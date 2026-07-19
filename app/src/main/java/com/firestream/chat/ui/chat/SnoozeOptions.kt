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
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TimePicker
import androidx.compose.material3.rememberDatePickerState
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
 * The shared body of the message-snooze picker: [SnoozePresets.compute] rows plus a
 * "Pick date & time" row that opens Material3's [DatePicker] then a custom
 * [TimePicker] dialog (M3 ships no built-in TimePickerDialog). Owns the date/time
 * dialog state and past-time validation so both entry points — the long-press
 * [SnoozePickerSheet] and the `.remind` composer widget (RemindWidget) — render an
 * identical option list without duplicating the picker plumbing.
 *
 * The dialogs are composed inline but render as their own windows, so this is safe
 * to mount inside a bottom sheet or a composer-anchored card alike.
 *
 * @param detectedFireAtMs a smart-detected time for the target message; when non-null
 *   and in the future, [SnoozePresets.compute] prepends a "Detected" row.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun SnoozeOptionsList(
    nowMs: Long,
    zoneId: ZoneId,
    detectedFireAtMs: Long?,
    onTimeSelected: (Long) -> Unit,
    modifier: Modifier = Modifier,
) {
    val presets = remember(detectedFireAtMs, nowMs, zoneId) {
        SnoozePresets.compute(nowMs, zoneId, detectedFireAtMs)
    }

    var showDatePicker by remember { mutableStateOf(false) }
    var showTimePicker by remember { mutableStateOf(false) }
    var pendingDateMillis by remember { mutableStateOf<Long?>(null) }
    var pastTimeError by remember { mutableStateOf(false) }

    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(4.dp)) {
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

        if (pastTimeError) {
            Text(
                text = "That time has already passed — pick a time in the future.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
                modifier = Modifier.padding(horizontal = 4.dp, vertical = 4.dp),
            )
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
private fun PresetRow(preset: SnoozePreset, onClick: () -> Unit) {
    val formattedTime = presetTimeFormat.format(Date(preset.fireAtMs))
    val (icon, label) = when (preset.kind) {
        // The time is folded into the label itself for DETECTED (rather than the
        // subtitle, like the other rows) so it reads as "Detected: <time>" per spec.
        SnoozePreset.Kind.DETECTED -> Icons.Outlined.AutoAwesome to "Detected: $formattedTime"
        SnoozePreset.Kind.IN_1_HOUR -> Icons.Outlined.Schedule to "In 1 hour"
        SnoozePreset.Kind.THIS_EVENING -> Icons.Outlined.DarkMode to "This evening"
        SnoozePreset.Kind.TOMORROW_MORNING -> Icons.Outlined.WbSunny to "Tomorrow morning"
    }
    val isDetected = preset.kind == SnoozePreset.Kind.DETECTED
    val iconTint = if (isDetected) MaterialTheme.colorScheme.tertiary else MaterialTheme.colorScheme.primary
    val iconContainer = if (isDetected) MaterialTheme.colorScheme.tertiaryContainer else MaterialTheme.colorScheme.primaryContainer
    PickerRow(
        icon = icon,
        iconTint = iconTint,
        iconContainer = iconContainer,
        label = label,
        subtitle = if (isDetected) null else formattedTime,
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
