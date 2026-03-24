package com.firestream.chat.ui.lists

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.DragHandle
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.zIndex
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlin.math.roundToInt
import androidx.hilt.navigation.compose.hiltViewModel
import com.firestream.chat.domain.model.ListItem
import com.firestream.chat.domain.model.ListType
import com.firestream.chat.ui.chat.ForwardChatPicker

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ListDetailScreen(
    autoFocus: Boolean = false,
    onBackClick: () -> Unit,
    onShareToChat: (chatId: String, recipientId: String) -> Unit = { _, _ -> },
    viewModel: ListDetailViewModel = hiltViewModel()
) {
    val uiState by viewModel.uiState.collectAsState()
    var showOverflowMenu by remember { mutableStateOf(false) }
    var showSharePicker by remember { mutableStateOf(false) }
    var newItemText by remember { mutableStateOf("") }
    var isEditingTitle by remember { mutableStateOf(false) }
    var titleEditValue by remember { mutableStateOf(TextFieldValue("")) }
    var draggedItemKey by remember { mutableStateOf<String?>(null) }
    var dragOffset by remember { mutableFloatStateOf(0f) }
    val listState = rememberLazyListState()
    val addItemFocusRequester = remember { FocusRequester() }
    val titleFocusRequester = remember { FocusRequester() }
    val keyboardController = LocalSoftwareKeyboardController.current
    val scrollBehavior = TopAppBarDefaults.pinnedScrollBehavior()

    LaunchedEffect(uiState.isDeleted) {
        if (uiState.isDeleted) onBackClick()
    }

    LaunchedEffect(autoFocus, uiState.listData) {
        if (autoFocus && uiState.listData != null) {
            addItemFocusRequester.requestFocus()
            keyboardController?.show()
        }
    }

    // Auto-focus title field when entering edit mode
    LaunchedEffect(isEditingTitle) {
        if (isEditingTitle) {
            titleFocusRequester.requestFocus()
        }
    }

    fun submitTitleEdit() {
        val newTitle = titleEditValue.text.trim()
        if (newTitle.isNotBlank() && newTitle != uiState.listData?.title) {
            viewModel.updateTitle(newTitle)
        }
        isEditingTitle = false
        keyboardController?.hide()
    }

    Scaffold(
        modifier = Modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
        topBar = {
            TopAppBar(
                scrollBehavior = scrollBehavior,
                title = {
                    if (isEditingTitle) {
                        BasicTextField(
                            value = titleEditValue,
                            onValueChange = { titleEditValue = it },
                            modifier = Modifier
                                .fillMaxWidth()
                                .focusRequester(titleFocusRequester),
                            singleLine = true,
                            textStyle = MaterialTheme.typography.titleLarge.copy(
                                color = MaterialTheme.colorScheme.onPrimary
                            ),
                            cursorBrush = SolidColor(MaterialTheme.colorScheme.onPrimary),
                            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                            keyboardActions = KeyboardActions(onDone = { submitTitleEdit() })
                        )
                    } else {
                        Text(
                            text = uiState.listData?.title ?: "List",
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.clickable {
                                val title = uiState.listData?.title ?: return@clickable
                                titleEditValue = TextFieldValue(title, TextRange(title.length))
                                isEditingTitle = true
                            }
                        )
                    }
                },
                navigationIcon = {
                    IconButton(onClick = {
                        if (isEditingTitle) submitTitleEdit() else onBackClick()
                    }) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                actions = {
                    IconButton(onClick = { showOverflowMenu = true }) {
                        Icon(Icons.Default.MoreVert, contentDescription = "More options")
                    }
                    DropdownMenu(
                        expanded = showOverflowMenu,
                        onDismissRequest = { showOverflowMenu = false }
                    ) {
                        DropdownMenuItem(
                            text = { Text("Share to chat") },
                            leadingIcon = {
                                Icon(Icons.Default.Share, contentDescription = null)
                            },
                            onClick = {
                                showOverflowMenu = false
                                showSharePicker = true
                            }
                        )
                        DropdownMenuItem(
                            text = { Text("Delete list") },
                            leadingIcon = {
                                Icon(
                                    Icons.Default.Delete,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.error
                                )
                            },
                            onClick = {
                                showOverflowMenu = false
                                viewModel.deleteList()
                            }
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.primary,
                    titleContentColor = MaterialTheme.colorScheme.onPrimary,
                    navigationIconContentColor = MaterialTheme.colorScheme.onPrimary,
                    actionIconContentColor = MaterialTheme.colorScheme.onPrimary
                )
            )
        }
    ) { padding ->
        when {
            uiState.isLoading -> {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(padding),
                    contentAlignment = Alignment.Center
                ) {
                    CircularProgressIndicator()
                }
            }
            uiState.listData == null -> {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(padding),
                    contentAlignment = Alignment.Center
                ) {
                    Text("List not found")
                }
            }
            else -> {
                val listData = uiState.listData!!
                val items = listData.items
                LaunchedEffect(items.size) {
                    if (items.isNotEmpty()) listState.animateScrollToItem(items.size - 1)
                }
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(padding)
                        .imePadding()
                ) {
                    LazyColumn(
                        state = listState,
                        modifier = Modifier.weight(1f)
                    ) {
                        items(items, key = { it.id }) { item ->
                            val isDragging = item.id == draggedItemKey
                            Column(
                                modifier = Modifier
                                    .zIndex(if (isDragging) 1f else 0f)
                                    .graphicsLayer {
                                        translationY = if (isDragging) dragOffset else 0f
                                        shadowElevation = if (isDragging) 8f else 0f
                                    }
                                    .then(
                                        if (isDragging) Modifier.background(MaterialTheme.colorScheme.surfaceContainerHighest)
                                        else Modifier
                                    )
                                    .animateItem()
                            ) {
                                ListItemRow(
                                    item = item,
                                    listType = listData.type,
                                    onToggle = { viewModel.toggleItem(item.id) },
                                    onRemove = { viewModel.removeItem(item.id) },
                                    onEdit = { newText -> viewModel.updateItem(item.id, newText) },
                                    onDragStart = {
                                        draggedItemKey = item.id
                                        dragOffset = 0f
                                    },
                                    onDrag = { delta -> dragOffset += delta },
                                    onDragEnd = {
                                        val fromIndex = items.indexOfFirst { it.id == draggedItemKey }
                                        if (fromIndex >= 0) {
                                            val visible = listState.layoutInfo.visibleItemsInfo
                                            val avgHeight = if (visible.isNotEmpty())
                                                visible.map { it.size }.average().toFloat()
                                            else 60f
                                            val slots = (dragOffset / avgHeight).roundToInt()
                                            val toIndex = (fromIndex + slots).coerceIn(0, items.size - 1)
                                            if (fromIndex != toIndex) {
                                                viewModel.reorderItems(fromIndex, toIndex)
                                            }
                                        }
                                        draggedItemKey = null
                                        dragOffset = 0f
                                    }
                                )
                                HorizontalDivider()
                            }
                        }

                        if (items.isEmpty()) {
                            item {
                                Box(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(32.dp),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Text(
                                        text = "No items yet. Add one below.",
                                        style = MaterialTheme.typography.bodyMedium,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f)
                                    )
                                }
                            }
                        }
                    }

                    // Add item row
                    HorizontalDivider()
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 12.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        OutlinedTextField(
                            value = newItemText,
                            onValueChange = { newItemText = it },
                            placeholder = { Text("Add item...") },
                            modifier = Modifier.weight(1f).focusRequester(addItemFocusRequester),
                            singleLine = true
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        IconButton(
                            onClick = {
                                if (newItemText.isNotBlank()) {
                                    viewModel.addItem(newItemText)
                                    newItemText = ""
                                }
                            },
                            enabled = newItemText.isNotBlank()
                        ) {
                            Icon(
                                Icons.Default.Add,
                                contentDescription = "Add item",
                                tint = if (newItemText.isNotBlank())
                                    MaterialTheme.colorScheme.primary
                                else
                                    MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f)
                            )
                        }
                    }
                }
            }
        }
    }

    if (showSharePicker) {
        ForwardChatPicker(
            chats = uiState.chats,
            currentUserId = uiState.currentUserId,
            onDismiss = { showSharePicker = false },
            onForward = { chatId, _ ->
                viewModel.shareToChat(chatId)
                showSharePicker = false
            }
        )
    }
}

@Composable
private fun ListItemRow(
    item: ListItem,
    listType: ListType,
    onToggle: () -> Unit,
    onRemove: () -> Unit,
    onEdit: (String) -> Unit,
    onDragStart: () -> Unit = {},
    onDrag: (Float) -> Unit = {},
    onDragEnd: () -> Unit = {}
) {
    var isEditing by remember { mutableStateOf(false) }
    var editValue by remember(item.id) {
        mutableStateOf(TextFieldValue(item.text, TextRange(item.text.length)))
    }
    val editFocusRequester = remember { FocusRequester() }
    val keyboardController = LocalSoftwareKeyboardController.current

    LaunchedEffect(isEditing) {
        if (isEditing) editFocusRequester.requestFocus()
    }

    fun submitEdit() {
        val newText = editValue.text.trim()
        if (newText.isNotBlank() && newText != item.text) {
            onEdit(newText)
        }
        isEditing = false
        keyboardController?.hide()
    }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        // Checkbox/bullet — always toggles
        when (listType) {
            ListType.CHECKLIST, ListType.SHOPPING -> {
                Checkbox(
                    checked = item.isChecked,
                    onCheckedChange = { onToggle() }
                )
            }
            ListType.GENERIC -> {
                Text(
                    text = "\u2022",
                    style = MaterialTheme.typography.bodyLarge,
                    modifier = Modifier
                        .padding(start = 12.dp, end = 12.dp)
                        .clickable(onClick = onToggle)
                )
            }
        }

        // Text area — tap to edit
        Column(modifier = Modifier.weight(1f)) {
            if (isEditing) {
                BasicTextField(
                    value = editValue,
                    onValueChange = { editValue = it },
                    modifier = Modifier
                        .fillMaxWidth()
                        .focusRequester(editFocusRequester),
                    singleLine = true,
                    textStyle = MaterialTheme.typography.bodyLarge.copy(
                        color = LocalContentColor.current
                    ),
                    cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                    keyboardActions = KeyboardActions(onDone = { submitEdit() })
                )
            } else {
                Text(
                    text = item.text,
                    style = MaterialTheme.typography.bodyLarge,
                    textDecoration = if (item.isChecked) TextDecoration.LineThrough else TextDecoration.None,
                    color = if (item.isChecked)
                        MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f)
                    else
                        MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.clickable {
                        editValue = TextFieldValue(item.text, TextRange(item.text.length))
                        isEditing = true
                    }
                )
                if (listType == ListType.SHOPPING && (item.quantity != null || item.unit != null)) {
                    Text(
                        text = listOfNotNull(item.quantity, item.unit).joinToString(" "),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }

        if (isEditing) {
            IconButton(onClick = { submitEdit() }, modifier = Modifier.size(32.dp)) {
                Icon(
                    Icons.Default.Check,
                    contentDescription = "Done",
                    modifier = Modifier.size(16.dp),
                    tint = MaterialTheme.colorScheme.primary
                )
            }
        } else {
            IconButton(onClick = onRemove, modifier = Modifier.size(32.dp)) {
                Icon(
                    Icons.Default.Close,
                    contentDescription = "Remove",
                    modifier = Modifier.size(16.dp),
                    tint = MaterialTheme.colorScheme.error.copy(alpha = 0.6f)
                )
            }
        }

        Icon(
            Icons.Default.DragHandle,
            contentDescription = "Drag to reorder",
            modifier = Modifier
                .size(24.dp)
                .pointerInput(Unit) {
                    detectVerticalDragGestures(
                        onDragStart = { onDragStart() },
                        onDragEnd = { onDragEnd() },
                        onDragCancel = { onDragEnd() },
                        onVerticalDrag = { change, dragAmount ->
                            change.consume()
                            onDrag(dragAmount)
                        }
                    )
                },
            tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f)
        )
    }
}
