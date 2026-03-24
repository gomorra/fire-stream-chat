package com.firestream.chat.ui.chat

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Person
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.firestream.chat.domain.model.Chat
import com.firestream.chat.domain.model.User
import com.firestream.chat.ui.components.UserAvatar

@Composable
internal fun ForwardChatPicker(
    chats: List<Chat>,
    currentUserId: String,
    onDismiss: () -> Unit,
    onForward: (chatId: String, recipientId: String) -> Unit,
    users: Map<String, User> = emptyMap()
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Forward to") },
        text = {
            if (chats.isEmpty()) {
                Text(
                    "No chats available to forward to.",
                    style = MaterialTheme.typography.bodyMedium
                )
            } else {
                LazyColumn {
                    items(chats, key = { it.id }) { chat ->
                        val recipientId = chat.participants.firstOrNull { it != currentUserId } ?: ""
                        val resolvedUser = users[recipientId]
                        val displayName = chat.name
                            ?: resolvedUser?.displayName
                            ?: "Chat"
                        val avatarUrl = chat.avatarUrl ?: resolvedUser?.avatarUrl
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { onForward(chat.id, recipientId) }
                                .padding(vertical = 12.dp, horizontal = 8.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            UserAvatar(
                                avatarUrl = avatarUrl,
                                contentDescription = displayName,
                                icon = Icons.Default.Person,
                                size = 40.dp
                            )
                            Spacer(modifier = Modifier.width(12.dp))
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = displayName,
                                    style = MaterialTheme.typography.bodyLarge,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                                chat.lastMessage?.let { lastMsg ->
                                    Text(
                                        text = lastMsg.content.take(40),
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                }
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
        }
    )
}
