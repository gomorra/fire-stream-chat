package com.firestream.chat.ui.lists

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.automirrored.filled.Sort
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Checklist
import androidx.compose.material.icons.filled.ShoppingCart
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.firestream.chat.domain.model.ListData
import com.firestream.chat.domain.model.ListType
import com.firestream.chat.ui.lists.ListSortOption
import com.firestream.chat.ui.chat.CreateListSheet
import com.firestream.chat.ui.chat.ForwardChatPicker
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
internal fun ListsScreen(
    onListClick: (listId: String) -> Unit,
    onListCreated: (listId: String) -> Unit = {},
    viewModel: ListsViewModel = hiltViewModel()
) {
    val uiState by viewModel.uiState.collectAsState()
    var showCreateSheet by remember { mutableStateOf(false) }
    var showSortMenu by remember { mutableStateOf(false) }
    var selectedListForAction by remember { mutableStateOf<ListData?>(null) }
    var showSharePicker by remember { mutableStateOf(false) }
    var shareListId by remember { mutableStateOf("") }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Lists") },
                windowInsets = WindowInsets(0, 0, 0, 0),
                actions = {
                    IconButton(onClick = { showSortMenu = true }) {
                        Icon(
                            Icons.AutoMirrored.Filled.Sort,
                            contentDescription = "Sort",
                            tint = MaterialTheme.colorScheme.onPrimary
                        )
                    }
                    DropdownMenu(
                        expanded = showSortMenu,
                        onDismissRequest = { showSortMenu = false }
                    ) {
                        ListSortOption.entries.forEach { option ->
                            DropdownMenuItem(
                                text = { Text(option.displayName) },
                                leadingIcon = {
                                    if (uiState.sortOption == option) {
                                        Icon(Icons.Default.Check, contentDescription = null)
                                    }
                                },
                                onClick = {
                                    viewModel.setSortOption(option)
                                    showSortMenu = false
                                }
                            )
                        }
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.primary,
                    titleContentColor = MaterialTheme.colorScheme.onPrimary
                )
            )
        },
        floatingActionButton = {
            FloatingActionButton(onClick = { showCreateSheet = true }) {
                Icon(Icons.Default.Add, contentDescription = "New List")
            }
        }
    ) { padding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
        ) {
            when {
                uiState.isLoading -> {
                    CircularProgressIndicator(modifier = Modifier.align(Alignment.Center))
                }
                uiState.lists.isEmpty() -> {
                    Column(
                        modifier = Modifier.align(Alignment.Center),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Icon(
                            imageVector = Icons.Default.Checklist,
                            contentDescription = null,
                            modifier = Modifier.size(64.dp),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f)
                        )
                        Spacer(modifier = Modifier.height(12.dp))
                        Text(
                            text = "No lists yet",
                            style = MaterialTheme.typography.bodyLarge,
                            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f)
                        )
                        Text(
                            text = "Tap + to create one",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f)
                        )
                    }
                }
                else -> {
                    LazyColumn(modifier = Modifier.fillMaxSize()) {
                        items(uiState.lists, key = { it.id }) { listData ->
                            ListRow(
                                listData = listData,
                                participants = uiState.participantAvatars[listData.id] ?: emptyList(),
                                onClick = { onListClick(listData.id) },
                                onLongClick = {
                                    selectedListForAction = listData
                                    viewModel.loadHistory(listData.id)
                                }
                            )
                            HorizontalDivider()
                        }
                    }
                }
            }
        }
    }

    if (showCreateSheet) {
        CreateListSheet(
            onDismiss = { showCreateSheet = false },
            onCreateList = { title, type, _, genericStyle ->
                viewModel.createList(title, type, genericStyle) { listId ->
                    onListCreated(listId)
                }
                showCreateSheet = false
            }
        )
    }

    selectedListForAction?.let { listData ->
        ListContextSheet(
            listData = listData,
            isOwner = listData.createdBy == uiState.currentUserId,
            history = uiState.selectedListHistory,
            onDismiss = {
                selectedListForAction = null
                viewModel.clearSelectedList()
            },
            onShare = {
                shareListId = listData.id
                showSharePicker = true
                selectedListForAction = null
                viewModel.clearSelectedList()
            },
            onDelete = {
                viewModel.deleteList(listData.id)
                selectedListForAction = null
                viewModel.clearSelectedList()
            },
            onRename = { newTitle ->
                viewModel.renameList(listData.id, newTitle)
                selectedListForAction = null
                viewModel.clearSelectedList()
            }
        )
    }

    if (showSharePicker) {
        ForwardChatPicker(
            chats = uiState.chats,
            currentUserId = uiState.currentUserId,
            onDismiss = { showSharePicker = false },
            onForward = { chatId, _ ->
                viewModel.shareListToChat(shareListId, chatId)
                showSharePicker = false
            },
            users = uiState.chatParticipants
        )
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun ListRow(
    listData: ListData,
    participants: List<com.firestream.chat.domain.model.User>,
    onClick: () -> Unit,
    onLongClick: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .combinedClickable(
                onClick = onClick,
                onLongClick = onLongClick
            )
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            imageVector = when (listData.type) {
                ListType.CHECKLIST -> Icons.Default.Checklist
                ListType.SHOPPING -> Icons.Default.ShoppingCart
                ListType.GENERIC -> Icons.AutoMirrored.Filled.List
            },
            contentDescription = null,
            modifier = Modifier.size(40.dp),
            tint = MaterialTheme.colorScheme.primary
        )

        Spacer(modifier = Modifier.width(12.dp))

        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = listData.title,
                style = MaterialTheme.typography.bodyLarge,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            val itemCount = listData.items.size
            val checkedCount = listData.items.count { it.isChecked }
            val subtitle = when (listData.type) {
                ListType.CHECKLIST, ListType.SHOPPING ->
                    "$checkedCount/$itemCount checked"
                ListType.GENERIC ->
                    "$itemCount item${if (itemCount != 1) "s" else ""}"
            }
            Text(
                text = subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }

        val overflow = (listData.participants.size - 1 - participants.size).coerceAtLeast(0)
        if (participants.isNotEmpty() || overflow > 0) {
            Spacer(modifier = Modifier.width(8.dp))
            AvatarStack(users = participants, overflow = overflow)
        }

        Spacer(modifier = Modifier.width(8.dp))
        Text(
            text = SimpleDateFormat("MMM d", Locale.getDefault())
                .format(Date(listData.updatedAt)),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}
