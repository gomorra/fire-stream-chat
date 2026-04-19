package com.firestream.chat.ui.chatlist

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Unarchive
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.firestream.chat.domain.model.ChatType

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ArchivedChatsScreen(
    onChatClick: (chatId: String, recipientId: String) -> Unit,
    onBackClick: () -> Unit,
    viewModel: ChatListViewModel = hiltViewModel()
) {
    val uiState by viewModel.uiState.collectAsState()
    val archivedChats = uiState.chats.filter { it.isArchived }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Archived Chats") },
                navigationIcon = {
                    IconButton(onClick = onBackClick) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.background,
                    titleContentColor = MaterialTheme.colorScheme.onBackground,
                    navigationIconContentColor = MaterialTheme.colorScheme.onBackground
                )
            )
        }
    ) { padding ->
        if (archivedChats.isEmpty()) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding),
                contentAlignment = Alignment.Center
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(
                        text = "No archived chats",
                        style = MaterialTheme.typography.titleMedium
                    )
                    Text(
                        text = "Swipe left on a chat to archive it",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 4.dp)
                    )
                }
            }
        } else {
            LazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding)
            ) {
                items(archivedChats, key = { it.id }) { chat ->
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        val recipientId = if (chat.type == ChatType.INDIVIDUAL) {
                            chat.participants.firstOrNull { it != uiState.currentUserId } ?: ""
                        } else ""
                        ChatListItem(
                            chat = chat,
                            currentUserId = uiState.currentUserId,
                            contacts = uiState.contacts,
                            onClick = { onChatClick(chat.id, recipientId) },
                            modifier = Modifier.weight(1f)
                        )
                        IconButton(onClick = { viewModel.toggleArchive(chat.id, true) }) {
                            Icon(
                                imageVector = Icons.Default.Unarchive,
                                contentDescription = "Restore",
                                tint = MaterialTheme.colorScheme.primary
                            )
                        }
                        IconButton(onClick = { viewModel.requestDeleteChat(chat.id) }) {
                            Icon(
                                imageVector = Icons.Default.Delete,
                                contentDescription = "Delete",
                                tint = MaterialTheme.colorScheme.error
                            )
                        }
                    }
                }
            }
        }

        if (uiState.pendingDeleteChatId != null) {
            AlertDialog(
                onDismissRequest = { viewModel.cancelDeleteChat() },
                title = { Text("Delete chat") },
                text = { Text("Delete this chat? It will be removed from your device.") },
                confirmButton = {
                    TextButton(onClick = { viewModel.confirmDeleteChat() }) {
                        Text("Delete", color = MaterialTheme.colorScheme.error)
                    }
                },
                dismissButton = {
                    TextButton(onClick = { viewModel.cancelDeleteChat() }) {
                        Text("Cancel")
                    }
                }
            )
        }
    }
}
