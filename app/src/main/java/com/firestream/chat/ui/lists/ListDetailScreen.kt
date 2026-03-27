package com.firestream.chat.ui.lists

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
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
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.DragHandle
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.SwipeToDismissBox
import androidx.compose.material3.SwipeToDismissBoxValue
import androidx.compose.material3.rememberSwipeToDismissBoxState
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
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import sh.calvin.reorderable.ReorderableItem
import sh.calvin.reorderable.rememberReorderableLazyListState
import com.firestream.chat.domain.model.GenericListStyle
import com.firestream.chat.domain.model.ListItem
import com.firestream.chat.domain.model.ListType
import kotlinx.coroutines.launch

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
    var showStyleMenu by remember { mutableStateOf(false) }
    var newItemText by remember { mutableStateOf("") }
    var isEditingTitle by remember { mutableStateOf(false) }
    var titleEditValue by remember { mutableStateOf(TextFieldValue("")) }
    var pendingRemoval by remember { mutableStateOf<ListItem?>(null) }
    val snackbarHostState = remember { SnackbarHostState() }
    val coroutineScope = rememberCoroutineScope()
    val listState = rememberLazyListState()
    val addItemFocusRequester = remember { FocusRequester() }
    val titleFocusRequester = remember { FocusRequester() }
    val focusManager = LocalFocusManager.current
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
        snackbarHost = {
            SnackbarHost(snackbarHostState, modifier = Modifier.imePadding())
        },
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
                                .focusRequester(titleFocusRequester)
                                .onFocusChanged { if (!it.isFocused && isEditingTitle) submitTitleEdit() },
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
                        val isOwner = uiState.listData?.createdBy == uiState.currentUserId
                        if (isOwner) {
                            DropdownMenuItem(
                                text = { Text("Manage sharing") },
                                leadingIcon = {
                                    Icon(Icons.Default.Share, contentDescription = null)
                                },
                                onClick = {
                                    showOverflowMenu = false
                                    showSharePicker = true
                                }
                            )
                        }
                        if (uiState.listData?.type == ListType.GENERIC) {
                            DropdownMenuItem(
                                text = { Text("List style") },
                                leadingIcon = {
                                    Icon(Icons.AutoMirrored.Filled.List, contentDescription = null)
                                },
                                onClick = {
                                    showOverflowMenu = false
                                    showStyleMenu = true
                                }
                            )
                        }
                        if (isOwner) {
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
                var localItems by remember { mutableStateOf(uiState.displayItems) }
                var draggedItemId by remember { mutableStateOf<String?>(null) }
                val displayItems = localItems.filter { it.id != pendingRemoval?.id }

                val reorderableLazyListState = rememberReorderableLazyListState(listState) { from, to ->
                    if (draggedItemId == null) draggedItemId = localItems.getOrNull(from.index)?.id
                    localItems = localItems.toMutableList().apply {
                        add(to.index, removeAt(from.index))
                    }
                }

                LaunchedEffect(uiState.displayItems) {
                    if (!reorderableLazyListState.isAnyItemDragging) {
                        localItems = uiState.displayItems
                    }
                }

                LaunchedEffect(reorderableLazyListState.isAnyItemDragging) {
                    if (!reorderableLazyListState.isAnyItemDragging) {
                        val id = draggedItemId ?: return@LaunchedEffect
                        draggedItemId = null
                        val originalFrom = uiState.displayItems.indexOfFirst { it.id == id }
                        val finalTo = localItems.indexOfFirst { it.id == id }
                        localItems = uiState.displayItems
                        if (originalFrom >= 0 && finalTo >= 0 && originalFrom != finalTo) {
                            viewModel.reorderItems(originalFrom, finalTo)
                        }
                    }
                }

                LaunchedEffect(listData.items.size) {
                    if (displayItems.isNotEmpty()) listState.animateScrollToItem(displayItems.size - 1)
                }
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(padding)
                        .imePadding()
                        .pointerInput(Unit) {
                            detectTapGestures { focusManager.clearFocus() }
                        }
                ) {
                    LazyColumn(
                        state = listState,
                        modifier = Modifier.weight(1f)
                    ) {
                        itemsIndexed(displayItems, key = { _, it -> it.id }) { index, item ->
                            ReorderableItem(reorderableLazyListState, key = item.id) { isDragging ->
                                val dismissState = rememberSwipeToDismissBoxState(
                                    confirmValueChange = { value ->
                                        if (value == SwipeToDismissBoxValue.EndToStart) {
                                            pendingRemoval = item
                                            coroutineScope.launch {
                                                val result = snackbarHostState.showSnackbar(
                                                    message = "\"${item.text}\" removed",
                                                    actionLabel = "Undo",
                                                    duration = SnackbarDuration.Short
                                                )
                                                if (result == SnackbarResult.ActionPerformed) {
                                                    pendingRemoval = null
                                                } else {
                                                    pendingRemoval?.let { viewModel.removeItem(it.id) }
                                                    pendingRemoval = null
                                                }
                                            }
                                            true
                                        } else false
                                    }
                                )
                                Column(
                                    modifier = Modifier
                                        .then(
                                            if (isDragging) Modifier.background(MaterialTheme.colorScheme.surfaceContainerHighest)
                                            else Modifier
                                        )
                                        .animateItem()
                                ) {
                                    SwipeToDismissBox(
                                        state = dismissState,
                                        enableDismissFromStartToEnd = false,
                                        backgroundContent = {
                                            val iconTint = if (dismissState.targetValue == SwipeToDismissBoxValue.EndToStart)
                                                MaterialTheme.colorScheme.error
                                            else
                                                Color.White
                                            Box(
                                                modifier = Modifier
                                                    .fillMaxSize()
                                                    .background(Color(0xFF424242)),
                                                contentAlignment = Alignment.CenterEnd
                                            ) {
                                                Icon(
                                                    Icons.Default.Delete,
                                                    contentDescription = null,
                                                    tint = iconTint,
                                                    modifier = Modifier.padding(end = 16.dp)
                                                )
                                            }
                                        }
                                    ) {
                                        ListItemRow(
                                            item = item,
                                            listType = listData.type,
                                            itemIndex = index,
                                            genericStyle = listData.genericStyle,
                                            dragHandleModifier = Modifier.draggableHandle(),
                                            onToggle = { viewModel.toggleItem(item.id) },
                                            onEdit = { newText -> viewModel.updateItem(item.id, newText) }
                                        )
                                    }
                                    HorizontalDivider()
                                }
                            }
                        }

                        if (displayItems.isEmpty()) {
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
        ListShareSheet(
            chats = uiState.chats,
            sharedChatIds = uiState.listData?.sharedChatIds ?: emptyList(),
            currentUserId = uiState.currentUserId,
            chatParticipants = uiState.chatParticipants,
            onShare = { chatId -> viewModel.shareToChat(chatId) },
            onUnshare = { chatId -> viewModel.unshareFromChat(chatId) },
            onDismiss = { showSharePicker = false }
        )
    }

    if (showStyleMenu) {
        androidx.compose.material3.AlertDialog(
            onDismissRequest = { showStyleMenu = false },
            title = { Text("List style") },
            text = {
                Column {
                    val currentStyle = uiState.listData?.genericStyle ?: GenericListStyle.BULLET
                    GenericListStyle.entries.forEach { style ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable {
                                    viewModel.updateGenericStyle(style)
                                    showStyleMenu = false
                                }
                                .padding(vertical = 12.dp, horizontal = 8.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            androidx.compose.material3.RadioButton(
                                selected = currentStyle == style,
                                onClick = {
                                    viewModel.updateGenericStyle(style)
                                    showStyleMenu = false
                                }
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(text = style.displayName(), style = MaterialTheme.typography.bodyLarge)
                        }
                    }
                }
            },
            confirmButton = {}
        )
    }
}

@Composable
private fun ListItemRow(
    item: ListItem,
    listType: ListType,
    itemIndex: Int = 0,
    genericStyle: GenericListStyle = GenericListStyle.BULLET,
    dragHandleModifier: Modifier = Modifier,
    onToggle: () -> Unit,
    onEdit: (String) -> Unit
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
            .background(MaterialTheme.colorScheme.surface)
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
                val prefix = when (genericStyle) {
                    GenericListStyle.BULLET -> "\u2022"
                    GenericListStyle.NUMBER -> "${itemIndex + 1}."
                    GenericListStyle.DASH -> "\u2013"
                    GenericListStyle.NONE -> null
                }
                if (prefix != null) {
                    Text(
                        text = prefix,
                        style = MaterialTheme.typography.bodyLarge,
                        modifier = Modifier
                            .padding(start = 12.dp, end = 12.dp)
                            .clickable(onClick = onToggle)
                    )
                } else {
                    Spacer(modifier = Modifier.width(12.dp))
                }
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
                        .focusRequester(editFocusRequester)
                        .onFocusChanged { if (!it.isFocused && isEditing) submitEdit() },
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
        }

        Icon(
            Icons.Default.DragHandle,
            contentDescription = "Drag to reorder",
            modifier = Modifier
                .size(24.dp)
                .then(dragHandleModifier),
            tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f)
        )
    }
}
