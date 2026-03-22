package com.firestream.chat.ui.chatlist

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.padding
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
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
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
                    Text(
                        text = stringResource(R.string.app_name),
                        style = MaterialTheme.typography.titleLarge
                    )
                },
                windowInsets = WindowInsets(0),
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.primary,
                    titleContentColor = MaterialTheme.colorScheme.onPrimary,
                    actionIconContentColor = MaterialTheme.colorScheme.onPrimary
                ),
                actions = {
                    IconButton(onClick = { viewModel.toggleSearchBar() }) {
                        Icon(Icons.Default.Search, contentDescription = "Search")
                    }
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
            val searchFocusRequester = remember { FocusRequester() }

            LaunchedEffect(uiState.isSearchBarVisible) {
                if (uiState.isSearchBarVisible) {
                    searchFocusRequester.requestFocus()
                }
            }

            AnimatedVisibility(
                visible = uiState.isSearchBarVisible,
                enter = fadeIn() + expandVertically(),
                exit = fadeOut() + shrinkVertically()
            ) {
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
                            },
                            modifier = Modifier.focusRequester(searchFocusRequester)
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
                                    onlineUserIds = uiState.onlineUserIds,
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
                                onlineUserIds = uiState.onlineUserIds,
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
    onlineUserIds: Set<String> = emptySet(),
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
            isRecipientOnline = chat.type == ChatType.INDIVIDUAL &&
                chat.participants.firstOrNull { it != currentUserId }?.let { it in onlineUserIds } == true,
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

