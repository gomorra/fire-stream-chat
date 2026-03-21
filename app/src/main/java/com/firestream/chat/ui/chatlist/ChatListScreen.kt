package com.firestream.chat.ui.chatlist

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Archive
import androidx.compose.material.icons.filled.Campaign
import androidx.compose.material.icons.filled.Chat
import androidx.compose.material.icons.filled.Group
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.NotificationsOff
import androidx.compose.material.icons.filled.PushPin
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SearchBar
import androidx.compose.material3.SearchBarDefaults
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.firestream.chat.R
import com.firestream.chat.domain.model.Chat
import com.firestream.chat.domain.model.ChatType
import com.firestream.chat.domain.model.Contact
import com.firestream.chat.domain.model.Message
import com.firestream.chat.domain.model.MessageType
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun ChatListScreen(
    onChatClick: (chatId: String, recipientId: String) -> Unit,
    onNewChatClick: () -> Unit,
    onNewGroupClick: () -> Unit = {},
    onNewBroadcastClick: () -> Unit = {},
    onSettingsClick: () -> Unit,
    viewModel: ChatListViewModel = hiltViewModel()
) {
    val uiState by viewModel.uiState.collectAsState()
    val snackbarHostState = remember { SnackbarHostState() }

    LaunchedEffect(uiState.error) {
        uiState.error?.let {
            snackbarHostState.showSnackbar(it)
            viewModel.clearError()
        }
    }

    val allChats = uiState.chats
    val activeChats = allChats.filter { !it.isArchived }
    val pinnedChats = activeChats.filter { it.isPinned }
        .sortedByDescending { it.lastMessage?.timestamp ?: it.createdAt }
    val regularChats = activeChats.filter { !it.isPinned }
        .sortedByDescending { it.lastMessage?.timestamp ?: it.createdAt }
    val archivedChats = allChats.filter { it.isArchived }

    Scaffold(
        contentWindowInsets = WindowInsets(0),
        topBar = {
            TopAppBar(
                title = {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        FireIcon(modifier = Modifier.size(26.dp))
                        Text(
                            text = stringResource(R.string.app_name),
                            style = MaterialTheme.typography.titleLarge
                        )
                    }
                },
                windowInsets = WindowInsets(0),
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.primary,
                    titleContentColor = MaterialTheme.colorScheme.onPrimary,
                    actionIconContentColor = MaterialTheme.colorScheme.onPrimary
                ),
                actions = {
                    var showMenu by remember { mutableStateOf(false) }
                    IconButton(onClick = { showMenu = true }) {
                        Icon(Icons.Default.Campaign, contentDescription = "More")
                    }
                    DropdownMenu(
                        expanded = showMenu,
                        onDismissRequest = { showMenu = false }
                    ) {
                        DropdownMenuItem(
                            text = { Text("New Group") },
                            leadingIcon = { Icon(Icons.Default.Group, null) },
                            onClick = { showMenu = false; onNewGroupClick() }
                        )
                        DropdownMenuItem(
                            text = { Text("New Broadcast") },
                            leadingIcon = { Icon(Icons.Default.Campaign, null) },
                            onClick = { showMenu = false; onNewBroadcastClick() }
                        )
                    }
                    IconButton(onClick = onSettingsClick) {
                        Icon(Icons.Default.Settings, contentDescription = "Settings")
                    }
                }
            )
        },
        floatingActionButton = {
            FloatingActionButton(
                onClick = onNewChatClick,
                containerColor = MaterialTheme.colorScheme.primary
            ) {
                Icon(
                    imageVector = Icons.Default.Chat,
                    contentDescription = stringResource(R.string.new_chat),
                    tint = MaterialTheme.colorScheme.onPrimary
                )
            }
        },
        snackbarHost = { SnackbarHost(snackbarHostState) },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
        ) {
            // Search bar
            SearchBar(
                inputField = {
                    SearchBarDefaults.InputField(
                        query = uiState.searchQuery,
                        onQueryChange = viewModel::onSearchQueryChange,
                        onSearch = {},
                        expanded = uiState.isSearchActive,
                        onExpandedChange = { if (!it) viewModel.clearSearch() },
                        placeholder = { Text("Search messages…") },
                        leadingIcon = { Icon(Icons.Default.Search, contentDescription = null) },
                        trailingIcon = {
                            if (uiState.searchQuery.isNotEmpty()) {
                                IconButton(onClick = viewModel::clearSearch) {
                                    Icon(Icons.Default.Close, contentDescription = "Clear")
                                }
                            }
                        }
                    )
                },
                expanded = uiState.isSearchActive,
                onExpandedChange = { if (!it) viewModel.clearSearch() },
                windowInsets = WindowInsets(0.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 0.dp)
            ) {
                if (uiState.searchResults.isEmpty() && uiState.searchQuery.isNotEmpty()) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(16.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = "No messages found",
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                } else {
                    LazyColumn {
                        items(uiState.searchResults, key = { it.id }) { message ->
                            SearchResultItem(
                                message = message,
                                onClick = {
                                    viewModel.clearSearch()
                                    onChatClick(message.chatId, "")
                                }
                            )
                        }
                    }
                }
            }

            when {
                uiState.isLoading -> {
                    Box(
                        modifier = Modifier.fillMaxSize(),
                        contentAlignment = Alignment.Center
                    ) { CircularProgressIndicator() }
                }

                activeChats.isEmpty() && archivedChats.isEmpty() -> {
                    Box(
                        modifier = Modifier.fillMaxSize(),
                        contentAlignment = Alignment.Center
                    ) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Text(
                                text = stringResource(R.string.no_chats_yet),
                                style = MaterialTheme.typography.titleMedium
                            )
                            Text(
                                text = stringResource(R.string.start_chatting),
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(top = 4.dp)
                            )
                        }
                    }
                }

                else -> {
                    LazyColumn(modifier = Modifier.fillMaxSize()) {
                        if (pinnedChats.isNotEmpty()) {
                            stickyHeader(key = "header_pinned") {
                                ChatSectionHeader("Pinned")
                            }
                            items(pinnedChats, key = { "pin_${it.id}" }) { chat ->
                                ChatItem(
                                    chat = chat,
                                    currentUserId = uiState.currentUserId,
                                    contacts = uiState.contacts,
                                    onClick = { onChatClick(chat.id, chat.recipientId(uiState.currentUserId)) },
                                    onDelete = { viewModel.requestDeleteChat(chat.id) },
                                    onPin = { viewModel.togglePin(chat.id, chat.isPinned) },
                                    onArchive = { viewModel.toggleArchive(chat.id, chat.isArchived) },
                                    onMute = { viewModel.requestMuteChat(chat.id) }
                                )
                            }
                        }

                        if (regularChats.isNotEmpty() && pinnedChats.isNotEmpty()) {
                            stickyHeader(key = "header_chats") {
                                ChatSectionHeader("Chats")
                            }
                        }
                        items(regularChats, key = { it.id }) { chat ->
                            ChatItem(
                                chat = chat,
                                currentUserId = uiState.currentUserId,
                                contacts = uiState.contacts,
                                onClick = { onChatClick(chat.id, chat.recipientId(uiState.currentUserId)) },
                                onDelete = { viewModel.requestDeleteChat(chat.id) },
                                onPin = { viewModel.togglePin(chat.id, chat.isPinned) },
                                onArchive = { viewModel.toggleArchive(chat.id, chat.isArchived) },
                                onMute = { viewModel.requestMuteChat(chat.id) }
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

        if (uiState.pendingMuteChatId != null) {
            MuteDialog(
                onSelect = { viewModel.confirmMuteChat(it) },
                onDismiss = { viewModel.cancelMuteChat() }
            )
        }
    }
}

@Composable
private fun ChatItem(
    chat: Chat,
    currentUserId: String,
    contacts: Map<String, Contact> = emptyMap(),
    onClick: () -> Unit,
    onDelete: () -> Unit,
    onPin: () -> Unit,
    onArchive: () -> Unit,
    onMute: () -> Unit
) {
    var showMenu by remember { mutableStateOf(false) }
    val isMuted = remember(chat.muteUntil) {
        chat.muteUntil == Long.MAX_VALUE ||
            (chat.muteUntil > 0 && chat.muteUntil > System.currentTimeMillis())
    }

    Box {
        ChatListItem(
            chat = chat,
            currentUserId = currentUserId,
            contacts = contacts,
            onClick = onClick,
            onLongClick = { showMenu = true }
        )
        DropdownMenu(
            expanded = showMenu,
            onDismissRequest = { showMenu = false }
        ) {
            DropdownMenuItem(
                text = { Text(if (chat.isPinned) "Unpin" else "Pin") },
                leadingIcon = { Icon(Icons.Default.PushPin, null) },
                onClick = { showMenu = false; onPin() }
            )
            DropdownMenuItem(
                text = { Text(if (chat.isArchived) "Unarchive" else "Archive") },
                leadingIcon = { Icon(Icons.Default.Archive, null) },
                onClick = { showMenu = false; onArchive() }
            )
            DropdownMenuItem(
                text = { Text(if (isMuted) "Unmute" else "Mute") },
                leadingIcon = { Icon(Icons.Default.NotificationsOff, null) },
                onClick = { showMenu = false; onMute() }
            )
            DropdownMenuItem(
                text = { Text("Delete", color = MaterialTheme.colorScheme.error) },
                onClick = { showMenu = false; onDelete() }
            )
        }
    }
}

@Composable
private fun ChatSectionHeader(title: String) {
    Text(
        text = title,
        style = MaterialTheme.typography.labelMedium,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surface)
            .padding(horizontal = 16.dp, vertical = 6.dp)
    )
}

@Composable
private fun SearchResultItem(
    message: Message,
    onClick: () -> Unit
) {
    val dateStr = SimpleDateFormat("MMM d, h:mm a", Locale.getDefault())
        .format(Date(message.timestamp))
    ListItem(
        headlineContent = {
            Text(
                text = when (message.type) {
                    MessageType.IMAGE -> "📷 Photo"
                    MessageType.VOICE -> "🎤 Voice message"
                    MessageType.DOCUMENT -> "📄 ${message.content}"
                    else -> message.content
                },
                maxLines = 1
            )
        },
        supportingContent = {
            Text(text = dateStr, style = MaterialTheme.typography.labelSmall)
        },
        modifier = Modifier.clickable(onClick = onClick)
    )
}

@Composable
private fun MuteDialog(
    onSelect: (Long) -> Unit,
    onDismiss: () -> Unit
) {
    val now = System.currentTimeMillis()
    val options = listOf(
        "1 hour" to now + 3_600_000L,
        "8 hours" to now + 28_800_000L,
        "1 week" to now + 604_800_000L,
        "Always" to Long.MAX_VALUE,
        "Unmute" to 0L
    )
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Mute notifications") },
        text = {
            Column {
                options.forEach { (label, value) ->
                    TextButton(
                        onClick = { onSelect(value) },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text(label)
                    }
                }
            }
        },
        confirmButton = {},
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
        }
    )
}

private fun Chat.recipientId(currentUserId: String): String =
    if (type == ChatType.INDIVIDUAL) participants.firstOrNull { it != currentUserId } ?: ""
    else ""

@Composable
private fun FireIcon(modifier: Modifier = Modifier) {
    Canvas(modifier = modifier) {
        val w = size.width
        val h = size.height

        // Outer flame — deep orange base fading to amber/yellow at tip
        val outerGradient = Brush.verticalGradient(
            colors = listOf(
                Color(0xFFFFEB3B), // yellow tip
                Color(0xFFFF9800), // amber mid
                Color(0xFFE64A19), // deep orange base
            ),
            startY = 0f,
            endY = h
        )

        val outerFlame = Path().apply {
            moveTo(w * 0.50f, h * 0.00f)                                         // tip
            cubicTo(w * 0.82f, h * 0.14f, w * 0.96f, h * 0.40f, w * 0.88f, h * 0.62f) // right upper
            cubicTo(w * 0.80f, h * 0.78f, w * 0.90f, h * 0.88f, w * 0.74f, h * 0.93f) // right lower
            cubicTo(w * 0.62f, h * 0.97f, w * 0.57f, h * 1.00f, w * 0.50f, h * 1.00f) // base right
            cubicTo(w * 0.43f, h * 1.00f, w * 0.38f, h * 0.97f, w * 0.26f, h * 0.93f) // base left
            cubicTo(w * 0.10f, h * 0.88f, w * 0.20f, h * 0.78f, w * 0.12f, h * 0.62f) // left lower
            cubicTo(w * 0.04f, h * 0.40f, w * 0.18f, h * 0.14f, w * 0.50f, h * 0.00f) // left upper → tip
            close()
        }
        drawPath(outerFlame, brush = outerGradient)

        // Inner highlight — white-hot core fading out toward the middle
        val innerGradient = Brush.verticalGradient(
            colors = listOf(
                Color(0xCCFFFFFF), // near-white hot core
                Color(0x88FFEB3B), // warm yellow
                Color(0x00FF9800), // transparent fade-out
            ),
            startY = h * 0.08f,
            endY = h * 0.60f
        )

        val innerFlame = Path().apply {
            moveTo(w * 0.50f, h * 0.08f)
            cubicTo(w * 0.66f, h * 0.18f, w * 0.72f, h * 0.38f, w * 0.64f, h * 0.54f)
            cubicTo(w * 0.59f, h * 0.63f, w * 0.54f, h * 0.67f, w * 0.50f, h * 0.68f)
            cubicTo(w * 0.46f, h * 0.67f, w * 0.41f, h * 0.63f, w * 0.36f, h * 0.54f)
            cubicTo(w * 0.28f, h * 0.38f, w * 0.34f, h * 0.18f, w * 0.50f, h * 0.08f)
            close()
        }
        drawPath(innerFlame, brush = innerGradient)
    }
}
