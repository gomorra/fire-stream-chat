package com.firestream.chat.ui.chat

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.InsertDriveFile
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.BrokenImage
import androidx.compose.material.icons.filled.Call
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.HowToVote
import androidx.compose.material.icons.filled.LocationOn
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Timer
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.rememberVectorPainter
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import com.firestream.chat.domain.model.Chat
import com.firestream.chat.domain.model.Message
import com.firestream.chat.domain.model.MessageType
import com.firestream.chat.domain.model.User
import com.firestream.chat.ui.components.ChatPickerLabels
import com.firestream.chat.ui.components.ChatPickerOverlay
import com.firestream.chat.ui.components.placeholderLabel

/**
 * "Forward to…" — the chat picker, opened over the conversation.
 *
 * It is the panel an incoming share intent opens (`ui/share/SharePickerScreen`),
 * down to the preview above the list and the send button: forwarding and sharing
 * ask the user the same question, so they no longer answer it with two different
 * controls — this replaced a bare `AlertDialog` list. Multi-select comes with
 * the panel, so one long-press can now put a message into several chats.
 *
 * Opening, closing and picking all belong to [ChatPickerOverlay]; what is left
 * here is the one thing only a chat knows — what a message being forwarded
 * looks like.
 */
@Composable
internal fun ForwardMessagePanel(
    target: Message?,
    chats: List<Chat>,
    currentUserId: String,
    participants: Map<String, User>,
    onDismiss: () -> Unit,
    onForward: (message: Message, targets: List<Chat>) -> Unit,
) {
    ChatPickerOverlay(
        target = target,
        chats = chats,
        currentUserId = currentUserId,
        participants = participants,
        labels = ChatPickerLabels(
            title = "Forward to…",
            emptyLabel = "No chats to forward to",
        ),
        onDismiss = onDismiss,
        onSend = onForward,
    ) { message ->
        ForwardMessagePreview(message)
    }
}

/**
 * What is about to be forwarded, filling the space above the chat list: the
 * picture itself for a photo or a video, otherwise the text, otherwise an icon
 * naming the kind of message.
 */
@Composable
private fun BoxScope.ForwardMessagePreview(message: Message) {
    when (message.type) {
        MessageType.IMAGE -> MediaPreview(
            model = rememberMessageImageModel(message),
            caption = message.content,
            overlayIcon = null,
        )
        MessageType.VIDEO -> MediaPreview(
            model = rememberMessageVideoThumbModel(message),
            caption = message.content,
            overlayIcon = Icons.Default.PlayArrow,
        )
        MessageType.TEXT -> Text(
            text = message.content,
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier
                .align(Alignment.TopStart)
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
        )
        else -> LabelledPreview(
            icon = message.type.previewIcon,
            label = message.content.ifBlank { message.type.placeholderLabel },
        )
    }
}

@Composable
private fun MediaPreview(model: Any?, caption: String, overlayIcon: ImageVector?) {
    if (model == null) {
        LabelledPreview(icon = Icons.Default.Description, label = caption.ifBlank { "Media" })
        return
    }
    Box(contentAlignment = Alignment.Center) {
        AsyncImage(
            model = model,
            contentDescription = caption.ifBlank { "Message being forwarded" },
            contentScale = ContentScale.Fit,
            modifier = Modifier.fillMaxSize(),
            error = rememberVectorPainter(Icons.Default.BrokenImage),
        )
        if (overlayIcon != null) {
            Icon(
                imageVector = overlayIcon,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.size(56.dp),
            )
        }
    }
}

@Composable
private fun LabelledPreview(icon: ImageVector, label: String) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier.padding(16.dp),
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.primary,
            modifier = Modifier.size(56.dp),
        )
        Spacer(modifier = Modifier.height(8.dp))
        Text(
            text = label,
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurface,
            textAlign = TextAlign.Center,
            maxLines = 4,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

/** Exhaustive on purpose: a new [MessageType] should fail the build here, not
 *  quietly fall through to a generic document icon. */
private val MessageType.previewIcon: ImageVector
    get() = when (this) {
        MessageType.VOICE -> Icons.Default.Mic
        MessageType.DOCUMENT -> Icons.AutoMirrored.Filled.InsertDriveFile
        MessageType.LOCATION -> Icons.Default.LocationOn
        MessageType.POLL -> Icons.Default.HowToVote
        MessageType.TIMER -> Icons.Default.Timer
        MessageType.LIST -> Icons.AutoMirrored.Filled.List
        MessageType.CALL -> Icons.Default.Call
        // Handled by their own branches in ForwardMessagePreview.
        MessageType.TEXT, MessageType.IMAGE, MessageType.VIDEO -> Icons.Default.Description
    }
