package com.firestream.chat.ui.chat

import android.Manifest
import android.content.Context
import android.content.Intent
import com.firestream.chat.ui.call.CallActivity
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.result.contract.ActivityResultContracts.PickVisualMedia
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.gestures.animateScrollBy
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.AttachFile
import androidx.compose.material.icons.filled.Block
import androidx.compose.material.icons.outlined.EmojiEmotions
import androidx.compose.material.icons.filled.CameraAlt
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.Group
import androidx.compose.material.icons.filled.Checklist
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.LocationOn
import androidx.compose.material.icons.filled.Reply
import androidx.compose.material.icons.filled.Phone
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SmallFloatingActionButton
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.TextRange
import com.firestream.chat.ui.components.TypingIndicator
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Popup
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import androidx.hilt.navigation.compose.hiltViewModel
import com.firestream.chat.R
import com.firestream.chat.data.remote.LinkPreview
import com.firestream.chat.domain.model.Message
import com.firestream.chat.ui.components.UserAvatar
import com.firestream.chat.domain.model.MessageType
import androidx.compose.runtime.snapshotFlow
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

// Max emoji size multiplier shown in the input field — keeps tall emoji from overflowing maxLines.
private const val INPUT_EMOJI_SIZE_CAP = 2.0f

private val searchResultDateFormat = SimpleDateFormat("MMM d, HH:mm", Locale.getDefault())

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChatScreen(
    onBackClick: () -> Unit,
    onMessageInfoClick: (Message, List<String>) -> Unit = { _, _ -> },
    onProfileClick: (userId: String) -> Unit = {},
    onGroupSettingsClick: () -> Unit = {},
    onSharedMediaClick: () -> Unit = {},
    onSharedListsClick: () -> Unit = {},
    onListClick: (listId: String) -> Unit = {},
    viewModel: ChatViewModel = hiltViewModel()
) {
    val uiState by viewModel.uiState.collectAsState()
    val uploadProgressMap by viewModel.uploadProgress.collectAsState()
    var messageText by rememberSaveable { mutableStateOf("") }
    // Tracks char-index → size multiplier for emojis inserted via the picker.
    // Indices are based on messageText.length at insertion time and cleared on send/cancel.
    var pendingEmojiSizes by remember { mutableStateOf(emptyMap<Int, Float>()) }
    var inputCursor by remember { mutableStateOf(TextRange(0)) }
    val listState = rememberLazyListState()
    var initialScrollDone by remember { mutableStateOf(false) }
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val density = LocalDensity.current
    var showAttachmentSheet by remember { mutableStateOf(false) }
    var showCreatePollSheet by remember { mutableStateOf(false) }
    var showCreateListSheet by remember { mutableStateOf(false) }
    var showLocationSheet by remember { mutableStateOf(false) }
    var showEmojiSheet by remember { mutableStateOf(false) }
    var showOverflowMenu by remember { mutableStateOf(false) }
    var fullscreenImageMessage by remember { mutableStateOf<com.firestream.chat.domain.model.Message?>(null) }
    val sheetState = rememberModalBottomSheetState()
    val keyboardController = LocalSoftwareKeyboardController.current
    val configuration = LocalConfiguration.current
    val screenHeightDp = configuration.screenHeightDp
    val screenWidthDp = configuration.screenWidthDp
    // Height that shows exactly 5 emoji rows: search bar + 5 rows + category toolbar
    val emojiPanelHeightDp = run {
        val cellDp = (screenWidthDp - 30) / 8  // 8 cols, 16dp h-padding + 7×2dp gaps
        52 + 5 * cellDp + 4 * 2 + 40           // search + rows + row-gaps + toolbar
    }

    BackHandler(enabled = showEmojiSheet) { showEmojiSheet = false }

    // Reaction picker state
    var reactionTargetMessage by remember { mutableStateOf<Message?>(null) }
    // Swipe-to-react panel state
    var swipeReactMessage by remember { mutableStateOf<Message?>(null) }
    // ID of the message whose reaction chips should be scrolled into view after reacting
    var reactionScrollTarget by remember { mutableStateOf<String?>(null) }

    // Snackbar host state
    val snackbarHostState = remember { SnackbarHostState() }

    // Forward picker state
    var forwardTargetMessage by remember { mutableStateOf<Message?>(null) }

    // Save scroll position when leaving so it can be restored on re-entry
    DisposableEffect(Unit) {
        onDispose {
            viewModel.saveScrollPosition(
                listState.firstVisibleItemIndex,
                listState.firstVisibleItemScrollOffset
            )
        }
    }

    // Restore saved scroll position once messages are first available
    LaunchedEffect(uiState.messages.isNotEmpty()) {
        if (uiState.messages.isNotEmpty() && !initialScrollDone) {
            initialScrollDone = true
            val savedIndex = viewModel.savedScrollIndex
            if (savedIndex in 0 until uiState.messages.size) {
                listState.scrollToItem(savedIndex, viewModel.savedScrollOffset)
            } else {
                listState.scrollToItem(uiState.messages.size - 1)
            }
        }
    }

    // Track screen visibility for read receipts — only mark READ when chat is in foreground.
    // Also re-check block state on resume so the composer flips to the banner immediately
    // after the user toggles block/unblock from the profile screen and navigates back.
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_RESUME -> {
                    viewModel.setScreenVisible(true)
                    viewModel.refreshBlockState()
                }
                Lifecycle.Event.ON_PAUSE -> viewModel.setScreenVisible(false)
                else -> {}
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            viewModel.setScreenVisible(false)
            lifecycleOwner.lifecycle.removeObserver(observer)
        }
    }

    // Surface send errors as a snackbar. ChatMessageSender writes failures (block,
    // network, Signal, Storage upload, …) into uiState.error; without this the error
    // is silently dropped on the next state update.
    LaunchedEffect(uiState.error) {
        uiState.error?.let { message ->
            snackbarHostState.showSnackbar(message, duration = SnackbarDuration.Short)
            viewModel.clearError()
        }
    }

    // Forward any snackbarEvent emissions (e.g. "Saved to Downloads") to the host.
    LaunchedEffect(Unit) {
        viewModel.snackbarEvent.collect { message ->
            snackbarHostState.showSnackbar(message, duration = SnackbarDuration.Short)
        }
    }

    LaunchedEffect(uiState.editingMessage) {
        val editing = uiState.editingMessage
        if (editing != null) {
            messageText = editing.content
            inputCursor = TextRange(editing.content.length)
        }
    }

    // Auto-scroll only when near the bottom (within ~1 screen of the end)
    // Skip until the initial scroll restore has run to avoid racing with it.
    LaunchedEffect(uiState.messages.size) {
        if (!initialScrollDone) return@LaunchedEffect
        if (uiState.messages.isNotEmpty()) {
            val lastVisible = listState.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: 0
            val visibleCount = listState.layoutInfo.visibleItemsInfo.size
            val nearBottom = (uiState.messages.size - 1 - lastVisible) <= visibleCount
            if (nearBottom) {
                listState.animateScrollToItem(uiState.messages.size - 1)
                // For tall items (e.g. images), the bottom edge may still be clipped after
                // scrollToItem positions the item's top. Wait one frame for layout then nudge.
                delay(50)
                val layoutInfo = listState.layoutInfo
                val lastVisibleItem = layoutInfo.visibleItemsInfo.lastOrNull()
                if (lastVisibleItem != null) {
                    val itemBottom = lastVisibleItem.offset + lastVisibleItem.size
                    val viewportEnd = layoutInfo.viewportEndOffset
                    if (itemBottom > viewportEnd) {
                        listState.animateScrollBy((itemBottom - viewportEnd).toFloat())
                    }
                }
            }
        }
    }

    // Scroll correction for receiver images that load from URL after initial layout.
    // Coil's AsyncImage expands to its natural aspect-ratio height once decoded, which can
    // push the bottom of the last message below the viewport. Only nudge when the very last
    // message is the one that just grew — the 10 px threshold prevents micro-jitter.
    LaunchedEffect(Unit) {
        snapshotFlow { listState.layoutInfo }
            .collect { layoutInfo ->
                val lastVisible = layoutInfo.visibleItemsInfo.lastOrNull() ?: return@collect
                val totalItems = layoutInfo.totalItemsCount
                if (totalItems > 0 && lastVisible.index == totalItems - 1) {
                    val itemBottom = lastVisible.offset + lastVisible.size
                    val viewportEnd = layoutInfo.viewportEndOffset
                    if (itemBottom > viewportEnd + 10) {
                        listState.animateScrollBy((itemBottom - viewportEnd).toFloat())
                    }
                }
            }
    }

    // Dismiss swipe reaction panel when user scrolls
    LaunchedEffect(Unit) {
        snapshotFlow { listState.isScrollInProgress }
            .collect { scrolling -> if (scrolling) swipeReactMessage = null }
    }

    // Report "at bottom" state to the ViewModel so it can reset the unread counter
    // while the user is actively reading new messages at the tail of the list.
    LaunchedEffect(Unit) {
        snapshotFlow {
            val layoutInfo = listState.layoutInfo
            val total = layoutInfo.totalItemsCount
            val lastVisibleIndex = layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: -1
            total > 0 && lastVisibleIndex >= total - 1
        }
            .distinctUntilChanged()
            .collect { viewModel.setAtBottom(it) }
    }

    // Always scroll to bottom when the user sends a message
    LaunchedEffect(uiState.scrollToBottomTrigger) {
        if (uiState.scrollToBottomTrigger > 0 && uiState.messages.isNotEmpty()) {
            listState.animateScrollToItem(uiState.messages.size - 1)
        }
    }

    // After a reaction is added, scroll so the reaction chips (bottom of the message) are visible
    LaunchedEffect(reactionScrollTarget) {
        val target = reactionScrollTarget ?: return@LaunchedEffect
        val idx = uiState.messages.indexOfFirst { it.id == target }
        if (idx < 0) { reactionScrollTarget = null; return@LaunchedEffect }

        // Wait for the reaction row to render (Firestore round-trip + recomposition)
        delay(250)

        val viewportHeight = listState.layoutInfo.viewportSize.height
        var item = listState.layoutInfo.visibleItemsInfo.firstOrNull { it.index == idx }
        if (item == null) {
            listState.animateScrollToItem(idx)
            delay(100)
            item = listState.layoutInfo.visibleItemsInfo.firstOrNull { it.index == idx }
        }
        item?.let {
            val marginPx = with(density) { 12.dp.toPx() }
            val overshoot = it.offset + it.size + marginPx - viewportHeight
            if (overshoot > 0) listState.animateScrollBy(overshoot)
        }
        reactionScrollTarget = null
    }

    var cameraUri by rememberSaveable { mutableStateOf<Uri?>(null) }
    var pendingImageUri by rememberSaveable { mutableStateOf<Uri?>(null) }
    var pendingImageMimeType by rememberSaveable { mutableStateOf("image/jpeg") }

    val galleryLauncher = rememberLauncherForActivityResult(PickVisualMedia()) { uri ->
        if (uri != null) {
            pendingImageMimeType = context.contentResolver.getType(uri) ?: "image/jpeg"
            pendingImageUri = uri
        }
    }

    val cameraLauncher = rememberLauncherForActivityResult(ActivityResultContracts.TakePicture()) { success ->
        if (success) cameraUri?.let {
            pendingImageMimeType = "image/jpeg"
            pendingImageUri = it
        }
    }

    val fileLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri?.let {
            val mimeType = context.contentResolver.getType(it) ?: "application/octet-stream"
            viewModel.sendMediaMessage(it, mimeType)
        }
    }

    val cameraPermissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) {
            cameraUri = createCameraUri(context)
            cameraUri?.let { cameraLauncher.launch(it) }
        }
    }

    val galleryPermissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { _ ->
        galleryLauncher.launch(PickVisualMediaRequest(PickVisualMedia.ImageOnly))
    }

    val locationPermissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) showLocationSheet = true
    }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbarHostState) },
        topBar = {
            TopAppBar(
                title = {
                    Row(
                        modifier = Modifier.clickable {
                            if (uiState.isGroupChat) onGroupSettingsClick()
                            else if (!uiState.isBroadcast) onProfileClick(viewModel.recipientId)
                        },
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        UserAvatar(
                            avatarUrl = uiState.avatarUrl,
                            contentDescription = null,
                            icon = if (uiState.isGroupChat) Icons.Default.Group else Icons.Default.Person,
                            size = 36.dp,
                            modifier = Modifier.size(36.dp),
                            localAvatarPath = uiState.localAvatarPath
                        )
                        Spacer(modifier = Modifier.width(10.dp))
                        Column {
                            Text(
                                text = uiState.chatName ?: "Chat",
                                style = MaterialTheme.typography.titleLarge,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                            when {
                                uiState.isRecipientOnline -> Text(
                                    text = "Online",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onPrimary.copy(alpha = 0.8f)
                                )
                                uiState.isBroadcast && uiState.broadcastRecipientCount > 0 -> Text(
                                    text = "${uiState.broadcastRecipientCount} ${if (uiState.broadcastRecipientCount == 1) "recipient" else "recipients"}",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onPrimary.copy(alpha = 0.7f)
                                )
                            }
                        }
                    }
                },
                navigationIcon = {
                    IconButton(onClick = onBackClick) {
                        Icon(imageVector = Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                actions = {
                    if (!uiState.isGroupChat && !uiState.isBroadcast) {
                        IconButton(onClick = {
                            val callIntent = Intent(context, CallActivity::class.java).apply {
                                putExtra(CallActivity.EXTRA_ACTION, CallActivity.ACTION_OUTGOING)
                                putExtra(CallActivity.EXTRA_CALLEE_ID, viewModel.recipientId)
                                putExtra(CallActivity.EXTRA_CALLEE_NAME, uiState.chatName ?: "")
                                putExtra(CallActivity.EXTRA_CALLEE_AVATAR_URL, uiState.recipientAvatarUrl)
                                putExtra(CallActivity.EXTRA_CHAT_ID, viewModel.chatId)
                            }
                            context.startActivity(callIntent)
                        }) {
                            Icon(
                                imageVector = Icons.Default.Phone,
                                contentDescription = "Voice call",
                                tint = MaterialTheme.colorScheme.onPrimary
                            )
                        }
                    }
                    IconButton(onClick = { viewModel.toggleSearch() }) {
                        Icon(
                            imageVector = Icons.Default.Search,
                            contentDescription = "Search messages",
                            tint = MaterialTheme.colorScheme.onPrimary
                        )
                    }
                    Box {
                        IconButton(onClick = { showOverflowMenu = true }) {
                            Icon(
                                imageVector = Icons.Default.MoreVert,
                                contentDescription = "More options",
                                tint = MaterialTheme.colorScheme.onPrimary
                            )
                        }
                        DropdownMenu(
                            expanded = showOverflowMenu,
                            onDismissRequest = { showOverflowMenu = false }
                        ) {
                            Column(modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)) {
                                FilledTonalButton(
                                    onClick = {
                                        showOverflowMenu = false
                                        onSharedMediaClick()
                                    },
                                    modifier = Modifier.fillMaxWidth()
                                ) {
                                    Icon(Icons.Default.Image, null, modifier = Modifier.padding(end = 4.dp))
                                    Text("Shared Media")
                                }
                                FilledTonalButton(
                                    onClick = {
                                        showOverflowMenu = false
                                        onSharedListsClick()
                                    },
                                    modifier = Modifier.fillMaxWidth().padding(top = 4.dp)
                                ) {
                                    Icon(Icons.Default.Checklist, null, modifier = Modifier.padding(end = 4.dp))
                                    Text("Shared Lists")
                                }
                            }
                        }
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.primary,
                    titleContentColor = MaterialTheme.colorScheme.onPrimary,
                    navigationIconContentColor = MaterialTheme.colorScheme.onPrimary
                )
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .imePadding()
        ) {
            // Pinned message banner
            if (uiState.pinnedMessages.isNotEmpty()) {
                val pinned = uiState.pinnedMessages.last()
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(MaterialTheme.colorScheme.secondaryContainer)
                        .clickable {
                            val index = uiState.messages.indexOfFirst { it.id == pinned.id }
                            if (index >= 0) {
                                scope.launch { listState.animateScrollToItem(index) }
                            }
                        }
                        .padding(horizontal = 12.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        imageVector = Icons.Default.Lock,
                        contentDescription = null,
                        modifier = Modifier.size(16.dp),
                        tint = MaterialTheme.colorScheme.onSecondaryContainer
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = pinned.content.take(80),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSecondaryContainer,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f)
                    )
                    IconButton(
                        onClick = { viewModel.togglePin(pinned.id, false) },
                        modifier = Modifier.size(24.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.Close,
                            contentDescription = "Unpin",
                            modifier = Modifier.size(16.dp),
                            tint = MaterialTheme.colorScheme.onSecondaryContainer
                        )
                    }
                }
            }

            // In-chat search bar
            AnimatedVisibility(
                visible = uiState.isSearchActive,
                enter = slideInVertically() + fadeIn(),
                exit = slideOutVertically() + fadeOut()
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(MaterialTheme.colorScheme.surfaceVariant)
                        .padding(horizontal = 8.dp, vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        imageVector = Icons.Default.Search,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(20.dp)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    OutlinedTextField(
                        value = uiState.searchQuery,
                        onValueChange = { viewModel.onSearchQueryChange(it) },
                        modifier = Modifier.weight(1f),
                        placeholder = { Text("Search in conversation...") },
                        singleLine = true,
                        shape = RoundedCornerShape(20.dp),
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search)
                    )
                    IconButton(onClick = { viewModel.clearSearch() }) {
                        Icon(
                            imageVector = Icons.Default.Close,
                            contentDescription = "Close search",
                            tint = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }

            // Search results overlay
            if (uiState.isSearchActive && uiState.searchQuery.isNotBlank()) {
                if (uiState.searchResults.isEmpty()) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(16.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = "No results found",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                } else {
                    Text(
                        text = "${uiState.searchResults.size} ${if (uiState.searchResults.size == 1) "result" else "results"}",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp)
                    )
                    LazyColumn(
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxWidth()
                            .background(MaterialTheme.colorScheme.surface)
                    ) {
                        items(uiState.searchResults, key = { "search_${it.id}" }) { message ->
                            Column(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable {
                                        val idx = uiState.messages.indexOfFirst { it.id == message.id }
                                        if (idx >= 0) {
                                            scope.launch {
                                                listState.scrollToItem(idx)
                                                delay(16)
                                                val viewportHeight = listState.layoutInfo.viewportSize.height
                                                val itemInfo = listState.layoutInfo.visibleItemsInfo
                                                    .firstOrNull { it.index == idx }
                                                if (itemInfo != null && viewportHeight > 0) {
                                                    listState.animateScrollBy(
                                                        -(viewportHeight - itemInfo.size) / 2f
                                                    )
                                                }
                                            }
                                        }
                                        viewModel.clearSearch()
                                    }
                                    .padding(horizontal = 16.dp, vertical = 10.dp)
                            ) {
                                Text(
                                    text = message.senderId.take(12),
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.primary
                                )
                                Text(
                                    text = message.content,
                                    style = MaterialTheme.typography.bodyMedium,
                                    maxLines = 2,
                                    overflow = TextOverflow.Ellipsis
                                )
                                Text(
                                    text = searchResultDateFormat.format(Date(message.timestamp)),
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                            HorizontalDivider()
                        }
                    }
                }
            }

            val showingSearchResults = uiState.isSearchActive && uiState.searchQuery.isNotBlank() && uiState.searchResults.isNotEmpty()
            if (!showingSearchResults) {
                when {
                    uiState.isLoading -> {
                        Box(modifier = Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                            CircularProgressIndicator()
                        }
                    }
                    else -> {
                        // Scroll-to-bottom: show FAB when more than 2 screens from bottom.
                        // derivedStateOf prevents recomposition on every scroll frame — the
                        // boolean only changes when the FAB needs to appear or disappear.
                        val totalItems = uiState.messages.size
                        val showScrollToBottom by remember(totalItems) {
                            derivedStateOf {
                                val visInfo = listState.layoutInfo.visibleItemsInfo
                                val lastIdx = visInfo.lastOrNull()?.index ?: 0
                                val visible = visInfo.size
                                totalItems > 0 && (totalItems - 1 - lastIdx) > visible * 2
                            }
                        }

                        Box(modifier = Modifier.weight(1f).fillMaxWidth()) {
                        LazyColumn(
                            state = listState,
                            modifier = Modifier.fillMaxSize().padding(horizontal = 8.dp)
                        ) {
                            itemsIndexed(uiState.messages, key = { _, msg -> msg.id }) { index, message ->
                                val showSeparator = index == 0 ||
                                    !isSameDay(message.timestamp, uiState.messages[index - 1].timestamp)
                                if (showSeparator) {
                                    DateSeparator(formatDateSeparator(message.timestamp))
                                }
                                val prevMessage = if (index > 0) uiState.messages[index - 1] else null
                                val nextMessage = if (index < uiState.messages.size - 1) uiState.messages[index + 1] else null
                                val groupPosition = computeGroupPosition(message, prevMessage, nextMessage)
                                val topPadding = when (groupPosition) {
                                    GroupPosition.MIDDLE, GroupPosition.LAST -> 2.dp
                                    else -> 4.dp
                                }
                                val isOwn = message.senderId == uiState.currentUserId
                                val replyToMessage = message.replyToId?.let { id ->
                                    uiState.messages.find { it.id == id }
                                }
                                val linkPreview = if (message.type == MessageType.TEXT) {
                                    uiState.linkPreviews.entries.firstOrNull { (url, _) ->
                                        message.content.contains(url)
                                    }?.value
                                } else null

                                Column(modifier = Modifier.animateItem().padding(top = topPadding)) {
                                if (message.type == MessageType.POLL) {
                                    PollBubble(
                                        message = message,
                                        isOwnMessage = isOwn,
                                        currentUserId = uiState.currentUserId,
                                        onVote = { optionIds -> viewModel.votePoll(message.id, optionIds) },
                                        onClose = { viewModel.closePoll(message.id) }
                                    )
                                } else if (message.type == MessageType.LIST) {
                                    ListBubble(
                                        message = message,
                                        listData = uiState.listDataCache[message.listId],
                                        isOwnMessage = isOwn,
                                        chatId = viewModel.chatId,
                                        onClick = {
                                            message.listId?.let { onListClick(it) }
                                        },
                                        onUnsharedListClick = {
                                            scope.launch {
                                                snackbarHostState.showSnackbar(
                                                    "This list is no longer shared with this chat",
                                                    duration = SnackbarDuration.Short
                                                )
                                            }
                                        }
                                    )
                                } else {
                                    Box {
                                        MessageBubble(
                                            message = message,
                                            isOwnMessage = isOwn,
                                            groupPosition = groupPosition,
                                            replyToMessage = replyToMessage,
                                            linkPreview = linkPreview,
                                            currentUserId = uiState.currentUserId,
                                            readReceiptsAllowed = uiState.readReceiptsAllowed && !uiState.isBroadcast,
                                            userIdToDisplayName = uiState.participantNameMap,
                                            onDeleteClick = if (isOwn) {
                                                { viewModel.deleteMessage(message.id) }
                                            } else null,
                                            onEditClick = if (isOwn && message.type == MessageType.TEXT) {
                                                { viewModel.startEdit(message) }
                                            } else null,
                                            onReplyClick = { viewModel.setReplyTo(message) },
                                            onReactionClick = { reactionTargetMessage = message },
                                            onSwipeReact = { swipeReactMessage = message },
                                            onForwardClick = { forwardTargetMessage = message },
                                            onStarClick = { viewModel.toggleStar(message) },
                                            onPinClick = {
                                                viewModel.togglePin(message.id, !message.isPinned)
                                            },
                                            onInfoClick = if (isOwn) {
                                                {
                                                    val chatParticipants = uiState.availableChats
                                                        .find { it.id == message.chatId }
                                                        ?.participants ?: emptyList()
                                                    onMessageInfoClick(message, chatParticipants)
                                                }
                                            } else null,
                                            uploadProgress = uploadProgressMap[message.id],
                                            onImageClick = { _ -> fullscreenImageMessage = message },
                                            onCallClick = if (message.type == MessageType.CALL && !uiState.isGroupChat && !uiState.isBroadcast) {
                                                {
                                                    val callIntent = Intent(context, CallActivity::class.java).apply {
                                                        putExtra(CallActivity.EXTRA_ACTION, CallActivity.ACTION_OUTGOING)
                                                        putExtra(CallActivity.EXTRA_CALLEE_ID, viewModel.recipientId)
                                                        putExtra(CallActivity.EXTRA_CALLEE_NAME, uiState.chatName ?: "")
                                                        putExtra(CallActivity.EXTRA_CALLEE_AVATAR_URL, uiState.recipientAvatarUrl)
                                                        putExtra(CallActivity.EXTRA_CHAT_ID, viewModel.chatId)
                                                    }
                                                    context.startActivity(callIntent)
                                                }
                                            } else null
                                        )

                                        // Swipe-to-react panel popup
                                        if (swipeReactMessage?.id == message.id) {
                                            // 52.dp = panel content height (40dp emoji + 6dp×2 padding)
                                            val panelOffsetPx = with(LocalDensity.current) { (-52).dp.roundToPx() }
                                            Popup(
                                                alignment = if (isOwn) Alignment.TopEnd else Alignment.TopStart,
                                                offset = IntOffset(0, panelOffsetPx),
                                                onDismissRequest = { swipeReactMessage = null }
                                            ) {
                                                SwipeReactionPanel(
                                                    recentEmojis = uiState.recentEmojis,
                                                    currentReaction = message.reactions[uiState.currentUserId],
                                                    onEmojiSelected = { emoji ->
                                                        val isAdding = message.reactions[uiState.currentUserId] != emoji
                                                        if (isAdding) reactionScrollTarget = message.id
                                                        viewModel.toggleReaction(message.id, emoji)
                                                        viewModel.addRecentEmoji(emoji)
                                                        swipeReactMessage = null
                                                    },
                                                    onPlusClick = {
                                                        swipeReactMessage = null
                                                        reactionTargetMessage = message
                                                    }
                                                )
                                            }
                                        }
                                    }
                                }
                                } // Column with group spacing
                            }
                        }

                        // Scroll-to-bottom FAB
                        androidx.compose.animation.AnimatedVisibility(
                            visible = showScrollToBottom,
                            modifier = Modifier
                                .align(Alignment.BottomEnd)
                                .padding(end = 12.dp, bottom = 12.dp),
                            enter = fadeIn() + scaleIn(),
                            exit = fadeOut() + scaleOut()
                        ) {
                            SmallFloatingActionButton(
                                onClick = {
                                    scope.launch {
                                        listState.animateScrollToItem(uiState.messages.size - 1)
                                    }
                                },
                                containerColor = MaterialTheme.colorScheme.surfaceVariant,
                                contentColor = MaterialTheme.colorScheme.onSurfaceVariant
                            ) {
                                Icon(
                                    imageVector = Icons.Default.KeyboardArrowDown,
                                    contentDescription = "Scroll to bottom"
                                )
                            }
                        }
                        } // Box
                    }
                }
            }

            // Typing indicator
            if (uiState.typingUserIds.isNotEmpty()) {
                TypingIndicator(
                    dotColor = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(start = 8.dp, bottom = 2.dp)
                )
            }

            // Edit mode banner
            if (uiState.editingMessage != null) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(MaterialTheme.colorScheme.secondaryContainer)
                        .padding(horizontal = 12.dp, vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "Editing message",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSecondaryContainer,
                        modifier = Modifier.weight(1f)
                    )
                    IconButton(onClick = {
                        viewModel.cancelEdit()
                        messageText = ""
                        inputCursor = TextRange(0)
                        pendingEmojiSizes = emptyMap()
                    }) {
                        Icon(
                            imageVector = Icons.Default.Close,
                            contentDescription = "Cancel edit",
                            tint = MaterialTheme.colorScheme.onSecondaryContainer
                        )
                    }
                }
            }

            // Reply-to banner
            if (uiState.replyToMessage != null) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(MaterialTheme.colorScheme.surfaceVariant)
                        .padding(horizontal = 12.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        imageVector = Icons.Default.Reply,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(16.dp)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = "Replying to",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.primary
                        )
                        Text(
                            text = uiState.replyToMessage!!.content.take(60),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                    IconButton(onClick = { viewModel.clearReplyTo() }) {
                        Icon(
                            imageVector = Icons.Default.Close,
                            contentDescription = "Cancel reply",
                            tint = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }

            // Announcement mode banner (when user can't send)
            if (!uiState.canSendMessages && uiState.isAnnouncementMode) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(MaterialTheme.colorScheme.surfaceVariant)
                        .padding(horizontal = 16.dp, vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.Center
                ) {
                    Icon(
                        imageVector = Icons.Default.Lock,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(16.dp)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = "Only admins can send messages",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            // Blocked-recipient banner (replaces the composer for 1:1 chats where
            // the current user has blocked the peer). Tap opens the user profile
            // where Unblock lives — same destination as the header avatar tap.
            if (uiState.isRecipientBlocked && !uiState.isGroupChat && !uiState.isBroadcast) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(MaterialTheme.colorScheme.surfaceVariant)
                        .clickable { onProfileClick(viewModel.recipientId) }
                        .padding(horizontal = 16.dp, vertical = 14.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.Center
                ) {
                    Icon(
                        imageVector = Icons.Default.Block,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(16.dp)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = "You blocked this contact. Tap to unblock.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            // Mention autocomplete picker
            if (uiState.mentionCandidates.isNotEmpty()) {
                LazyColumn(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = 180.dp)
                        .background(MaterialTheme.colorScheme.surface)
                ) {
                    items(uiState.mentionCandidates, key = { it.uid }) { user ->
                        ListItem(
                            headlineContent = { Text(user.displayName) },
                            modifier = Modifier.clickable {
                                val selected = viewModel.selectMention(user, messageText)
                                messageText = selected
                                inputCursor = TextRange(selected.length)
                            }
                        )
                        HorizontalDivider()
                    }
                }
            }

            // Input row — hidden when the user has blocked the recipient; the
            // "You blocked this contact" banner above replaces it.
            if (uiState.canSendMessages && !uiState.isRecipientBlocked) Row(
                modifier = Modifier.fillMaxWidth().padding(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                IconButton(onClick = {
                    keyboardController?.hide()
                    showEmojiSheet = !showEmojiSheet
                }) {
                    Icon(
                        imageVector = Icons.Outlined.EmojiEmotions,
                        contentDescription = "Emoji",
                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }

                val clearInput = {
                    messageText = ""
                    inputCursor = TextRange(0)
                    pendingEmojiSizes = emptyMap()
                    showEmojiSheet = false
                }
                val emojiInputSize = MaterialTheme.typography.bodyMedium.fontSize
                val inputAnnotated = remember(messageText, pendingEmojiSizes, emojiInputSize) {
                    val cappedSizes = pendingEmojiSizes.mapValues { (_, v) -> v.coerceAtMost(INPUT_EMOJI_SIZE_CAP) }
                    addEmojiSpans(messageText, emojiInputSize, cappedSizes)
                }
                val inputValue = remember(inputAnnotated, inputCursor) {
                    TextFieldValue(
                        annotatedString = inputAnnotated,
                        selection = TextRange(
                            inputCursor.start.coerceIn(0, messageText.length),
                            inputCursor.end.coerceIn(0, messageText.length)
                        )
                    )
                }
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .onFocusChanged { if (it.isFocused) showEmojiSheet = false }
                        // No clip: large emoji must overflow the Row's cross-axis height constraint.
                        .border(1.dp, MaterialTheme.colorScheme.outline, RoundedCornerShape(24.dp))
                ) {
                    BasicTextField(
                        value = inputValue,
                        onValueChange = { newValue ->
                            inputCursor = newValue.selection
                            val newText = newValue.text
                            if (newText != messageText) {
                                pendingEmojiSizes = adjustEmojiIndices(messageText, newText, pendingEmojiSizes)
                                messageText = newText
                                if (uiState.editingMessage == null) viewModel.onTypingWithMentions(newText)
                            }
                        },
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(
                                start = 16.dp,
                                end = if (uiState.editingMessage == null) 48.dp else 16.dp,
                            ),
                        textStyle = MaterialTheme.typography.bodyMedium.copy(
                            color = MaterialTheme.colorScheme.onSurface,
                            // Unspecified lets each line expand to its content's natural metrics,
                            // so a large emoji (e.g. 500%) grows the line — and the Box — instead
                            // of overflowing the fixed 20.sp bodyMedium lineHeight and being clipped.
                            lineHeight = TextUnit.Unspecified
                        ),
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                        keyboardActions = KeyboardActions(
                            onSend = {
                                handleSend(viewModel, uiState, messageText, pendingEmojiSizes)
                                clearInput()
                            }
                        ),
                        maxLines = 4,
                        cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                        decorationBox = { innerTextField ->
                            Box(
                                contentAlignment = Alignment.CenterStart,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .defaultMinSize(minHeight = 48.dp)
                            ) {
                                if (messageText.isEmpty()) {
                                    Text(
                                        text = if (uiState.editingMessage != null) "Edit message..."
                                               else stringResource(R.string.type_message),
                                        style = MaterialTheme.typography.bodyMedium,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                                innerTextField()
                            }
                        }
                    )
                    if (uiState.editingMessage == null) {
                        IconButton(
                            onClick = { showAttachmentSheet = true },
                            modifier = Modifier.align(Alignment.CenterEnd)
                        ) {
                            Icon(
                                imageVector = Icons.Default.Add,
                                contentDescription = "Attach",
                                tint = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
                Spacer(modifier = Modifier.width(8.dp))

                IconButton(
                    onClick = {
                        handleSend(viewModel, uiState, messageText, pendingEmojiSizes)
                        clearInput()
                    },
                    enabled = messageText.isNotBlank() && !uiState.isSending
                ) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Filled.Send,
                        contentDescription = stringResource(R.string.send),
                        tint = if (messageText.isNotBlank()) MaterialTheme.colorScheme.primary
                        else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f)
                    )
                }
            }

            // Inline emoji panel — pushes chat list up like the keyboard
            AnimatedVisibility(
                visible = showEmojiSheet,
                enter = slideInVertically { it } + fadeIn(),
                exit = slideOutVertically { it } + fadeOut()
            ) {
                EmojiHandlerPanel(
                    mode = EmojiMode.TEXT_INPUT,
                    recentEmojis = uiState.recentEmojis,
                    onEmojiSelected = { emoji, size ->
                        val insertIdx = messageText.length
                        messageText += emoji
                        inputCursor = TextRange(messageText.length)
                        if (size != 1.0f) {
                            pendingEmojiSizes = pendingEmojiSizes + (insertIdx to size)
                        }
                    },
                    onBackspace = {
                        if (messageText.isNotEmpty()) {
                            val iter = java.text.BreakIterator.getCharacterInstance()
                            iter.setText(messageText)
                            iter.last()
                            val boundary = iter.previous()
                            val removedIdx = boundary
                            messageText = messageText.substring(0, boundary)
                            inputCursor = TextRange(messageText.length)
                            pendingEmojiSizes = pendingEmojiSizes - removedIdx
                        }
                    },
                    onRecentUsed = { viewModel.addRecentEmoji(it) },
                    modifier = Modifier.height(emojiPanelHeightDp.dp)
                )
            }
        }
    }

    // Attachment bottom sheet
    if (showAttachmentSheet) {
        ModalBottomSheet(
            onDismissRequest = { showAttachmentSheet = false },
            sheetState = sheetState
        ) {
            Column(modifier = Modifier.padding(bottom = 32.dp)) {
                AttachmentOption(
                    icon = Icons.Default.Image,
                    label = "Gallery",
                    onClick = {
                        scope.launch { sheetState.hide() }.invokeOnCompletion {
                            showAttachmentSheet = false
                            val permission = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU)
                                Manifest.permission.READ_MEDIA_IMAGES
                            else Manifest.permission.READ_EXTERNAL_STORAGE
                            if (ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED) {
                                galleryLauncher.launch(PickVisualMediaRequest(PickVisualMedia.ImageOnly))
                            } else {
                                galleryPermissionLauncher.launch(permission)
                            }
                        }
                    }
                )
                AttachmentOption(
                    icon = Icons.Default.CameraAlt,
                    label = "Camera",
                    onClick = {
                        scope.launch { sheetState.hide() }.invokeOnCompletion {
                            showAttachmentSheet = false
                            if (ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) {
                                cameraUri = createCameraUri(context)
                                cameraUri?.let { cameraLauncher.launch(it) }
                            } else {
                                cameraPermissionLauncher.launch(Manifest.permission.CAMERA)
                            }
                        }
                    }
                )
                AttachmentOption(
                    icon = Icons.Default.AttachFile,
                    label = "File",
                    onClick = {
                        scope.launch { sheetState.hide() }.invokeOnCompletion {
                            showAttachmentSheet = false
                            fileLauncher.launch(arrayOf("*/*"))
                        }
                    }
                )
                AttachmentOption(
                    icon = Icons.Default.Add,
                    label = "Create Poll",
                    onClick = {
                        scope.launch { sheetState.hide() }.invokeOnCompletion {
                            showAttachmentSheet = false
                            showCreatePollSheet = true
                        }
                    }
                )
                AttachmentOption(
                    icon = Icons.AutoMirrored.Filled.List,
                    label = "Create List",
                    onClick = {
                        scope.launch { sheetState.hide() }.invokeOnCompletion {
                            showAttachmentSheet = false
                            showCreateListSheet = true
                        }
                    }
                )
                AttachmentOption(
                    icon = Icons.Default.LocationOn,
                    label = "Location",
                    onClick = {
                        showAttachmentSheet = false
                        if (context.checkSelfPermission(android.Manifest.permission.ACCESS_FINE_LOCATION) == android.content.pm.PackageManager.PERMISSION_GRANTED) {
                            showLocationSheet = true
                        } else {
                            locationPermissionLauncher.launch(android.Manifest.permission.ACCESS_FINE_LOCATION)
                        }
                    }
                )
            }
        }
    }

    // Create Poll bottom sheet
    if (showCreatePollSheet) {
        CreatePollSheet(
            onDismiss = { showCreatePollSheet = false },
            onCreatePoll = { question, options, isMultipleChoice, isAnonymous ->
                viewModel.sendPoll(question, options, isMultipleChoice, isAnonymous)
                showCreatePollSheet = false
            }
        )
    }

    // Create List bottom sheet
    if (showCreateListSheet) {
        CreateListSheet(
            onDismiss = { showCreateListSheet = false },
            onCreateList = { title, type, _, _ ->
                viewModel.createAndSendList(title, type)
                showCreateListSheet = false
            }
        )
    }

    // Location picker bottom sheet
    if (showLocationSheet) {
        LocationPickerSheet(
            onDismiss = { showLocationSheet = false },
            onSendLocation = { lat, lng, comment ->
                viewModel.sendLocationMessage(lat, lng, comment)
            }
        )
    }

    // Reaction picker bottom sheet
    reactionTargetMessage?.let { targetMsg ->
        ModalBottomSheet(
            onDismissRequest = { reactionTargetMessage = null }
        ) {
            EmojiHandlerPanel(
                mode = EmojiMode.REACTION,
                currentReaction = targetMsg.reactions[uiState.currentUserId],
                recentEmojis = uiState.recentEmojis,
                onEmojiSelected = { emoji, _ ->
                    val isAdding = targetMsg.reactions[uiState.currentUserId] != emoji
                    if (isAdding) reactionScrollTarget = targetMsg.id
                    viewModel.toggleReaction(targetMsg.id, emoji)
                    reactionTargetMessage = null
                },
                onRecentUsed = { viewModel.addRecentEmoji(it) },
                modifier = Modifier.height((screenHeightDp * 2 / 5).dp)
            )
        }
    }

    // Forward picker
    forwardTargetMessage?.let { targetMsg ->
        ForwardChatPicker(
            chats = uiState.availableChats,
            currentUserId = uiState.currentUserId,
            onDismiss = { forwardTargetMessage = null },
            onForward = { chatId, recipientId ->
                viewModel.forwardMessage(targetMsg, chatId, recipientId)
                forwardTargetMessage = null
            },
            users = uiState.chatParticipants
        )
    }

    BackHandler(enabled = fullscreenImageMessage != null) {
        fullscreenImageMessage = null
    }

    AnimatedVisibility(visible = fullscreenImageMessage != null, enter = fadeIn(), exit = fadeOut()) {
        fullscreenImageMessage?.let { msg ->
            FullscreenImageViewer(
                imageUrl = msg.mediaUrl ?: "",
                localUri = msg.localUri,
                onDismiss = { fullscreenImageMessage = null },
                onSaveToDownloads = {
                    viewModel.saveImageToDownloads(msg.localUri, msg.mediaUrl)
                }
            )
        }
    }

    BackHandler(enabled = pendingImageUri != null) {
        pendingImageUri = null
    }

    AnimatedVisibility(visible = pendingImageUri != null, enter = fadeIn(), exit = fadeOut()) {
        pendingImageUri?.let { uri ->
            ImagePreviewScreen(
                imageUri = uri,
                onSend = { caption ->
                    viewModel.sendMediaMessage(uri, pendingImageMimeType, caption)
                    pendingImageUri = null
                },
                onDismiss = { pendingImageUri = null }
            )
        }
    }
}

@Composable
private fun DateSeparator(label: String) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        HorizontalDivider(modifier = Modifier.weight(1f))
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 12.dp)
        )
        HorizontalDivider(modifier = Modifier.weight(1f))
    }
}

private fun handleSend(viewModel: ChatViewModel, uiState: ChatUiState, text: String, emojiSizes: Map<Int, Float> = emptyMap()) {
    if (uiState.editingMessage != null) {
        viewModel.confirmEdit(text)
    } else {
        viewModel.sendMessage(text, emojiSizes)
        viewModel.onTyping("")
    }
}

/**
 * Adjusts emoji size index map when the text changes via keyboard input.
 * Shifts indices after an insertion, drops entries in a deleted range and shifts the rest down.
 */
private fun adjustEmojiIndices(
    oldText: String,
    newText: String,
    sizes: Map<Int, Float>
): Map<Int, Float> {
    if (sizes.isEmpty()) return sizes
    val delta = newText.length - oldText.length
    if (delta == 0) return sizes
    // Find the first position where the strings diverge — that's the edit point.
    val editPos = oldText.zip(newText).indexOfFirst { (a, b) -> a != b }.takeIf { it >= 0 }
        ?: minOf(oldText.length, newText.length)
    return if (delta > 0) {
        // Insertion: shift all indices >= editPos forward by delta.
        sizes.mapKeys { (idx, _) -> if (idx >= editPos) idx + delta else idx }
    } else {
        // Deletion: drop entries in [editPos, editPos - delta) and shift the rest down.
        val deleteEnd = editPos - delta
        sizes.entries
            .filter { (idx, _) -> idx < editPos || idx >= deleteEnd }
            .associate { (idx, v) -> (if (idx >= deleteEnd) idx + delta else idx) to v }
    }
}

private fun createCameraUri(context: Context): Uri {
    val cacheDir = File(context.cacheDir, "camera").also { it.mkdirs() }
    val file = File(cacheDir, "photo_${System.currentTimeMillis()}.jpg")
    return FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
}


@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun AttachmentOption(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    label: String,
    onClick: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .combinedClickable(onClick = onClick, onLongClick = null)
            .padding(horizontal = 24.dp, vertical = 16.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(imageVector = icon, contentDescription = label, tint = MaterialTheme.colorScheme.primary)
        Spacer(modifier = Modifier.width(16.dp))
        Text(text = label, style = MaterialTheme.typography.bodyLarge)
    }
}
