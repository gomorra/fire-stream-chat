package com.firestream.chat.ui.chatlist

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.border
import androidx.compose.foundation.combinedClickable
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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Campaign
import androidx.compose.material.icons.filled.Group
import androidx.compose.material.icons.filled.Person
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.firestream.chat.domain.model.Chat
import com.firestream.chat.domain.model.ChatType
import com.firestream.chat.domain.model.Contact
import com.firestream.chat.ui.components.UserAvatar
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun ChatListItem(
    chat: Chat,
    currentUserId: String,
    contacts: Map<String, Contact> = emptyMap(),
    isRecipientOnline: Boolean = false,
    onClick: () -> Unit,
    onLongClick: () -> Unit = {},
    modifier: Modifier = Modifier
) {
    val recipientId = if (chat.type == ChatType.INDIVIDUAL) {
        chat.participants.firstOrNull { it != currentUserId }
    } else null
    val displayName = recipientId
        ?.let { contacts[it]?.displayName?.takeIf { n -> n.isNotBlank() } }
        ?: chat.name ?: "Chat"
    val avatarUrl = recipientId?.let { contacts[it]?.avatarUrl } ?: chat.avatarUrl

    Row(
        modifier = modifier
            .fillMaxWidth()
            .combinedClickable(onClick = onClick, onLongClick = onLongClick)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        // Avatar with optional online dot overlay
        Box(contentAlignment = Alignment.BottomEnd) {
            UserAvatar(
                avatarUrl = avatarUrl,
                contentDescription = displayName,
                icon = when (chat.type) {
                    ChatType.BROADCAST -> Icons.Default.Campaign
                    ChatType.GROUP -> Icons.Default.Group
                    else -> Icons.Default.Person
                },
                size = 52.dp,
                modifier = Modifier.size(52.dp)
            )
            if (isRecipientOnline) {
                Surface(
                    shape = CircleShape,
                    color = Color(0xFF4CAF50), // green
                    modifier = Modifier
                        .size(14.dp)
                        .border(2.dp, MaterialTheme.colorScheme.surface, CircleShape)
                ) {}
            }
        }

        Spacer(modifier = Modifier.width(12.dp))

        Row(
            modifier = Modifier.weight(1f),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // Name + message preview (takes all remaining space)
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = displayName,
                    style = MaterialTheme.typography.titleMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )

                val someoneElseTyping = chat.typingUserIds.any { it != currentUserId }
                if (someoneElseTyping) {
                    Text(
                        text = "typing...",
                        style = MaterialTheme.typography.bodyMedium.copy(fontStyle = FontStyle.Italic),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                } else {
                    val lastMsg = chat.lastMessage
                    val previewText = lastMsg?.content?.takeIf { it.isNotBlank() }?.let { content ->
                        if (lastMsg.senderId == currentUserId) "You: $content" else content
                    }
                    if (previewText != null) {
                        Text(
                            text = previewText,
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.width(8.dp))

            // Right column: time + unread badge
            Column(
                horizontalAlignment = Alignment.End,
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                chat.lastMessage?.timestamp?.let { timestamp ->
                    Text(
                        text = formatTime(timestamp),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                if (chat.unreadCount > 0) {
                    Surface(
                        shape = CircleShape,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(20.dp)
                    ) {
                        Box(contentAlignment = Alignment.Center) {
                            Text(
                                text = if (chat.unreadCount > 99) "99+" else chat.unreadCount.toString(),
                                style = MaterialTheme.typography.labelSmall.copy(fontSize = 10.sp),
                                color = MaterialTheme.colorScheme.onPrimary
                            )
                        }
                    }
                }
            }
        }
    }
}

private fun formatTime(timestamp: Long): String {
    val now = System.currentTimeMillis()
    val diff = now - timestamp
    val oneDay = 24 * 60 * 60 * 1000L

    return when {
        diff < oneDay -> SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date(timestamp))
        diff < 7 * oneDay -> SimpleDateFormat("EEE", Locale.getDefault()).format(Date(timestamp))
        else -> SimpleDateFormat("dd/MM/yy", Locale.getDefault()).format(Date(timestamp))
    }
}
