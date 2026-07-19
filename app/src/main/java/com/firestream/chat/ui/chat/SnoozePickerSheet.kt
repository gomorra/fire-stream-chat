package com.firestream.chat.ui.chat

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.firestream.chat.domain.model.Message
import com.firestream.chat.domain.model.MessageType
import java.time.ZoneId

/**
 * The message-snooze picker bottom sheet. Renders the shared [SnoozeOptionsList]
 * (presets + "Pick date & time") below a quoted preview of the [message].
 *
 * The base presets render immediately (`detectedFireAtMs = null`); a `LaunchedEffect`
 * keyed on [message]'s id then runs [detectSnoozeTime] and, if it finds a future
 * date/time reference in the message text, recomputes the preset list with a
 * "Detected" row prepended — never blocking the sheet's initial appearance.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun SnoozePickerSheet(
    message: Message,
    onDismiss: () -> Unit,
    onTimeSelected: (Long) -> Unit,
    detectSnoozeTime: suspend (String) -> Long? = { null },
) {
    val sheetState = rememberModalBottomSheetState()
    val zoneId = remember { ZoneId.systemDefault() }
    val nowMs = remember(message.id) { System.currentTimeMillis() }
    var detectedFireAtMs by remember(message.id) { mutableStateOf<Long?>(null) }

    LaunchedEffect(message.id) {
        detectedFireAtMs = detectSnoozeTime(message.content)
    }

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

            SnoozeOptionsList(
                nowMs = nowMs,
                zoneId = zoneId,
                detectedFireAtMs = detectedFireAtMs,
                onTimeSelected = onTimeSelected,
            )
        }
    }
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
