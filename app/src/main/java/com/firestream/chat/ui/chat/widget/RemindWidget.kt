// region: AGENT-NOTE
// Responsibility: card mounted above the composer when the user selects the
//   `.remind` command. Offers the same snooze presets as SnoozePickerSheet
//   (long-press → Snooze) for a target message, as a power-user entry point.
// Owns: ephemeral picker state (the smart-detected time is fetched per Render via a
//   LaunchedEffect keyed on the target id). No ChatUiState access — the target
//   message and sender name are resolved by ChatScreen (which owns the slices) and
//   injected into RenderContent.
// Collaborators: RemindCommand (references this @Singleton widget through the
//   ChatCommand registry); ChatScreen (type-checks the active widget and calls
//   RenderContent with the resolved reply-target/newest message, routing the
//   confirm callback straight to ChatViewModel.snoozeMessage — a LOCAL action, no
//   message is sent to the recipient); the shared SnoozeOptionsList composable.
// Don't put here: reminder scheduling/persistence (ChatMessageActions.snoozeMessage
//   → ReminderRepository), target resolution (RemindCommand.resolveRemindTarget), or
//   ChatUiState reads. Per chat-manager slice-ownership the widget never touches
//   ChatUiState directly.
//
// Interface note: ChatCommandWidget.Render can't supply the target message (its
//   contract is chatId/composerText only), so the generic path renders the harmless
//   empty state. ChatScreen always routes RemindWidget through RenderContent with the
//   real target, so the no-target path is a safety net, not the normal flow.
// endregion

package com.firestream.chat.ui.chat.widget

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.firestream.chat.domain.command.ChatCommandWidget
import com.firestream.chat.domain.command.CommandPayload
import com.firestream.chat.domain.model.Message
import com.firestream.chat.domain.model.MessageType
import com.firestream.chat.ui.chat.SnoozeOptionsList
import java.time.ZoneId
import javax.inject.Inject
import javax.inject.Singleton

/**
 * `.remind` widget — a composer-anchored card that offers the snooze presets for a
 * target message. Selecting a time creates the reminder locally (no message is sent).
 */
@Singleton
class RemindWidget @Inject constructor() : ChatCommandWidget {

    /**
     * Interface contract for generic mounting. Without a target (the interface can't
     * supply one) this renders the empty state; ChatScreen routes RemindWidget through
     * [RenderContent] with the resolved target instead, so this is a safety net only.
     */
    @Composable
    override fun Render(
        chatId: String,
        composerText: String,
        onSend: (CommandPayload) -> Unit,
        onCancel: () -> Unit,
    ) {
        RenderContent(
            targetMessage = null,
            senderName = null,
            detectSnoozeTime = { null },
            onConfirm = {},
            onCancel = onCancel,
        )
    }

    /**
     * The real render path. [targetMessage] is the reply-target if the composer has
     * one, else the newest message in the chat (see RemindCommand.resolveRemindTarget);
     * null when the chat is empty. [onConfirm] receives the chosen fire time.
     */
    @Composable
    fun RenderContent(
        targetMessage: Message?,
        senderName: String?,
        detectSnoozeTime: suspend (String) -> Long?,
        onConfirm: (Long) -> Unit,
        onCancel: () -> Unit,
    ) {
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
            Text(
                text = "Remind me…",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurface,
            )

            if (targetMessage == null) {
                Text(
                    text = "No message to remind about yet.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else {
                TargetPreview(message = targetMessage, senderName = senderName)

                val zoneId = remember { ZoneId.systemDefault() }
                val nowMs = remember(targetMessage.id) { System.currentTimeMillis() }
                var detectedFireAtMs by remember(targetMessage.id) { mutableStateOf<Long?>(null) }
                LaunchedEffect(targetMessage.id) {
                    detectedFireAtMs = detectSnoozeTime(targetMessage.content)
                }

                SnoozeOptionsList(
                    nowMs = nowMs,
                    zoneId = zoneId,
                    detectedFireAtMs = detectedFireAtMs,
                    onTimeSelected = onConfirm,
                )
            }

            OutlinedButton(
                onClick = onCancel,
                modifier = Modifier.fillMaxWidth(),
            ) { Text("Cancel") }
        }
    }
}

@Composable
private fun TargetPreview(message: Message, senderName: String?) {
    val snippet = when (message.type) {
        MessageType.IMAGE -> message.content.take(80).ifBlank { "Photo" }
        MessageType.VIDEO -> message.content.take(80).ifBlank { "Video" }
        else -> message.content.take(80).ifBlank { "Message" }
    }
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f))
            .padding(horizontal = 12.dp, vertical = 8.dp),
    ) {
        Box(
            modifier = Modifier
                .size(32.dp)
                .clip(CircleShape)
                .background(MaterialTheme.colorScheme.primaryContainer),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector = Icons.Default.Notifications,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(18.dp),
            )
        }
        Spacer(modifier = Modifier.width(12.dp))
        Column {
            if (!senderName.isNullOrBlank()) {
                Text(
                    text = senderName,
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.primary,
                    fontWeight = FontWeight.Medium,
                )
            }
            Text(
                text = snippet,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 2,
            )
        }
    }
}
