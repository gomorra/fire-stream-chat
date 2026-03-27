package com.firestream.chat.ui.lists

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Group
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.firestream.chat.domain.model.Chat
import com.firestream.chat.domain.model.ChatType
import com.firestream.chat.domain.model.User
import com.firestream.chat.ui.components.UserAvatar

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun ListShareSheet(
    chats: List<Chat>,
    sharedChatIds: List<String>,
    currentUserId: String,
    chatParticipants: Map<String, User>,
    onShare: (chatId: String) -> Unit,
    onUnshare: (chatId: String) -> Unit,
    onDismiss: () -> Unit
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    var searchQuery by remember { mutableStateOf("") }
    val sharedChatIdSet = remember(sharedChatIds) { sharedChatIds.toHashSet() }

    fun displayName(chat: Chat): String {
        val recipientId = chat.participants.firstOrNull { it != currentUserId } ?: ""
        return chat.name ?: chatParticipants[recipientId]?.displayName ?: "Chat"
    }

    val filteredChats = remember(chats, searchQuery, chatParticipants, currentUserId) {
        if (searchQuery.isEmpty()) chats
        else chats.filter { displayName(it).contains(searchQuery, ignoreCase = true) }
    }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState
    ) {
        Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp)) {
            Text(
                text = "Manage sharing",
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.padding(bottom = 12.dp)
            )

            OutlinedTextField(
                value = searchQuery,
                onValueChange = { searchQuery = it },
                placeholder = { Text("Search chats...") },
                leadingIcon = { Icon(Icons.Default.Search, contentDescription = null) },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true
            )

            Spacer(Modifier.height(8.dp))

            LazyColumn {
                items(filteredChats, key = { it.id }) { chat ->
                    val isGroup = chat.type == ChatType.GROUP
                    val recipientId = chat.participants.firstOrNull { it != currentUserId } ?: ""
                    val resolvedUser = chatParticipants[recipientId]
                    val name = displayName(chat)
                    val avatarUrl = chat.avatarUrl ?: resolvedUser?.avatarUrl
                    val isShared = chat.id in sharedChatIdSet

                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable {
                                if (isShared) onUnshare(chat.id) else onShare(chat.id)
                            }
                            .padding(vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        UserAvatar(
                            avatarUrl = avatarUrl,
                            contentDescription = name,
                            icon = if (isGroup) Icons.Default.Group else Icons.Default.Person,
                            size = 40.dp,
                            modifier = Modifier.size(40.dp)
                        )
                        Spacer(Modifier.width(12.dp))
                        Text(
                            text = name,
                            style = MaterialTheme.typography.bodyLarge,
                            modifier = Modifier.weight(1f),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        Checkbox(
                            checked = isShared,
                            onCheckedChange = { checked ->
                                if (checked) onShare(chat.id) else onUnshare(chat.id)
                            }
                        )
                    }
                    HorizontalDivider()
                }
            }

            Spacer(Modifier.height(16.dp))
        }
    }
}
