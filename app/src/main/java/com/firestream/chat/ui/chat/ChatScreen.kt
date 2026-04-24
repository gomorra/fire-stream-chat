@file:OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class)

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
import androidx.compose.foundation.layout.consumeWindowInsets
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
import androidx.compose.runtime.Immutable
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
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.testTagsAsResourceId
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
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

// Max emoji size multiplier shown in the input field — keeps tall emoji from overflowing maxLines.
private const val INPUT_EMOJI_SIZE_CAP = 2.0f

private val searchResultDateFormat = SimpleDateFormat("MMM d, HH:mm", Locale.getDefault())

// Holder for the currently-shown fullscreen image. Both message images and
// link-preview thumbnails feed into the same FullscreenImageViewer overlay,
// but they carry different data: a tapped message image has localUri +
// save-to-downloads support, while a link-preview thumbnail only has a remote
// URL. Encoding it as one nullable holder keeps the viewer block dispatch
// trivial and lets us add more fullscreen sources later (group avatars in
// settings, etc.) without growing the state surface.
@Immutable
private data class FullscreenImage(
    val imageUrl: String,
    val localUri: String? = null,
    val onSaveToDownloads: (() -> Unit)? = null,
)

// Chronological↔reversed index translation at the LazyColumn boundary.
// The LazyColumn runs `reverseLayout = true` so `firstVisibleItemIndex` and
// every `scrollToItem` target is in reversed space (0 = newest); the message
// list, `indexOfFirst`, persistence, and `computeGroupPosition` all use
// chronological space (0 = oldest). Since reversal is an involution, the
// same function works both directions.
private fun List<Message>.toReversedIndex(idx: Int): Int = lastIndex - idx

@OptIn(ExperimentalMaterial3Api::class, kotlinx.coroutines.FlowPreview::class)
@Composable
fun ChatScreen(
    onBackClick: () -> Unit,
    onMessageInfoClick: (Message, List<String>) -> Unit = { _, _ -> },
    onProfileClick: (userId: String) -> Unit = {},
    onGroupSettingsClick: () -> Unit = {},
    onSharedMediaClick: () -> Unit = {},
    onSharedListsClick: () -> Unit = {},
    onListClick: (listId: String) -> Unit = {},
    fromNotification: Boolean = false,
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
    var fullscreenImage by remember { mutableStateOf<FullscreenImage?>(null) }
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

    // Highlight the source message when the user taps a reply preview.
    // Cleared after the animation window so the border fades out.
    var highlightedMessageId by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(highlightedMessageId) {
        if (highlightedMessageId != null) {
            delay(1500)
            highlightedMessageId = null
        }
    }

    // Scroll the LazyColumn (reverseLayout=true) to `reversedIdx` and nudge the
    // item to the centre of the viewport. One frame of delay lets the scroll settle
    // before reading itemInfo.
    suspend fun scrollToAndCenter(reversedIdx: Int) {
        listState.scrollToItem(reversedIdx)
        delay(16)
        val viewportHeight = listState.layoutInfo.viewportSize.height
        val itemInfo = listState.layoutInfo.visibleItemsInfo
            .firstOrNull { it.index == reversedIdx }
        if (itemInfo != null && viewportHeight > 0) {
            listState.animateScrollBy(-(viewportHeight - itemInfo.size) / 2f)
        }
    }

    fun jumpToSourceMessage(sourceId: String) {
        val chronoIdx = uiState.messages.messages.indexOfFirst { it.id == sourceId }
        if (chronoIdx < 0) return
        highlightedMessageId = sourceId
        scope.launch {
            scrollToAndCenter(uiState.messages.messages.toReversedIndex(chronoIdx))
        }
    }

    // Save scroll position when leaving so it can be restored on re-entry.
    // SavedStateHandle survives config changes (fast path); DataStore survives
    // process death (slow path). See ChatViewModel.persistScrollPosition.
    // Persistence stores chronological indices; translation happens via
    // `toReversedIndex` at the LazyColumn boundary.
    DisposableEffect(Unit) {
        onDispose {
            val messages = uiState.messages.messages
            if (messages.isNotEmpty()) {
                val chronoIndex = messages.toReversedIndex(listState.firstVisibleItemIndex)
                val offset = listState.firstVisibleItemScrollOffset
                viewModel.saveScrollPosition(chronoIndex, offset)
                viewModel.persistScrollPosition(chronoIndex, offset)
            }
        }
    }

    // Catch background/kill mid-scroll — the DisposableEffect's onDispose fires
    // when the composable leaves composition, but if the process is killed before
    // navigation the onDispose never runs. A debounced snapshotFlow writes the
    // position while the user is still in the chat.
    LaunchedEffect(Unit) {
        snapshotFlow { listState.firstVisibleItemIndex to listState.firstVisibleItemScrollOffset }
            .distinctUntilChanged()
            .debounce(500)
            .collect { (reversedIdx, offset) ->
                val messages = uiState.messages.messages
                if (messages.isNotEmpty()) {
                    viewModel.persistScrollPosition(messages.toReversedIndex(reversedIdx), offset)
                }
            }
    }

    // Restore saved scroll position once messages are first available.
    // When opened from a notification, always land on the newest message —
    // the saved index would point to wherever the user last scrolled.
    // Precedence: SavedStateHandle (same-process) > DataStore (cross-process) > tail.
    //
    // Saved indices are chronological; `toReversedIndex` flips them at the
    // scroll boundary. "Newest message" is reversed index 0.
    LaunchedEffect(uiState.messages.messages.isNotEmpty()) {
        if (uiState.messages.messages.isNotEmpty() && !initialScrollDone) {
            initialScrollDone = true
            if (fromNotification) {
                listState.scrollToItem(0)
                return@LaunchedEffect
            }
            val messages = uiState.messages.messages
            val savedIndex = viewModel.savedScrollIndex
            if (savedIndex in messages.indices) {
                listState.scrollToItem(messages.toReversedIndex(savedIndex), viewModel.savedScrollOffset)
                return@LaunchedEffect
            }
            val persisted = viewModel.readPersistedScroll()
            if (persisted != null && persisted.index in messages.indices) {
                listState.scrollToItem(messages.toReversedIndex(persisted.index), persisted.offset)
            } else {
                listState.scrollToItem(0)
            }
        }
    }

    // getMessages() emits the cached batch first, then grows as the remote batch
    // merges in. If we only scroll to the end of the cached batch above, the
    // nearBottom hook below refuses to follow when the remote delta is large
    // (since the cached-last item is outside the visible viewport). For a
    // notification open we want the latest message — keep snapping to the tail
    // for a short window until the size settles.
    LaunchedEffect(fromNotification) {
        if (!fromNotification) return@LaunchedEffect
        withTimeoutOrNull(1500L) {
            snapshotFlow { uiState.messages.messages.size }
                .collect { size ->
                    if (size > 0) listState.scrollToItem(0)
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
    // network, Signal, Storage upload, …) into uiState.session.error; without this the error
    // is silently dropped on the next state update.
    LaunchedEffect(uiState.session.error) {
        uiState.session.error?.let { error ->
            snackbarHostState.showSnackbar(error.message, duration = SnackbarDuration.Short)
            viewModel.clearError()
        }
    }

    // Forward any snackbarEvent emissions (e.g. "Saved to Downloads") to the host.
    LaunchedEffect(Unit) {
        viewModel.snackbarEvent.collect { message ->
            snackbarHostState.showSnackbar(message, duration = SnackbarDuration.Short)
        }
    }

    LaunchedEffect(uiState.composer.editingMessage) {
        val editing = uiState.composer.editingMessage
        if (editing != null) {
            messageText = editing.content
            inputCursor = TextRange(editing.content.length)
        }
    }

    // Auto-scroll to the newest message only when the user is already near it
    // (within ~1 screen of reversed index 0). Skip until the initial scroll
    // restore has run to avoid racing with it.
    //
    // In reverseLayout, animateScrollToItem(0) anchors the newest message at
    // the viewport's visual bottom; async image decode / link-preview load
    // grow the item upward without clipping its bottom.
    LaunchedEffect(uiState.messages.messages.size) {
        if (!initialScrollDone) return@LaunchedEffect
        if (uiState.messages.messages.isNotEmpty()) {
            val firstVisible = listState.layoutInfo.visibleItemsInfo.firstOrNull()?.index ?: 0
            val visibleCount = listState.layoutInfo.visibleItemsInfo.size
            val nearBottom = firstVisible <= visibleCount
            if (nearBottom) {
                listState.animateScrollToItem(0)
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
    // In reverseLayout, "at the bottom" means the newest item (reversed index 0)
    // is visible at the viewport's visual bottom.
    LaunchedEffect(Unit) {
        snapshotFlow {
            val layoutInfo = listState.layoutInfo
            val firstVisibleIndex = layoutInfo.visibleItemsInfo.firstOrNull()?.index
            layoutInfo.totalItemsCount > 0 && firstVisibleIndex == 0
        }
            .distinctUntilChanged()
            .collect { viewModel.setAtBottom(it) }
    }

    // Always scroll to the newest message when the user sends a message.
    LaunchedEffect(uiState.messages.scrollToBottomTrigger) {
        if (uiState.messages.scrollToBottomTrigger > 0 && uiState.messages.messages.isNotEmpty()) {
            listState.animateScrollToItem(0)
        }
    }

    // After a reaction is added, scroll so the reaction chips (rendered below the
    // bubble inside the item) stay fully visible. indexOfFirst gives a chronological
    // index; the LazyColumn sees a reversed view so convert with `lastIndex - idx`.
    LaunchedEffect(reactionScrollTarget) {
        val target = reactionScrollTarget ?: return@LaunchedEffect
        val chronoIdx = uiState.messages.messages.indexOfFirst { it.id == target }
        if (chronoIdx < 0) { reactionScrollTarget = null; return@LaunchedEffect }
        val reversedIdx = uiState.messages.messages.toReversedIndex(chronoIdx)

        // Wait for the reaction row to render (Firestore round-trip + recomposition)
        delay(250)

        val viewportHeight = listState.layoutInfo.viewportSize.height
        var item = listState.layoutInfo.visibleItemsInfo.firstOrNull { it.index == reversedIdx }
        if (item == null) {
            listState.animateScrollToItem(reversedIdx)
            delay(100)
            item = listState.layoutInfo.visibleItemsInfo.firstOrNull { it.index == reversedIdx }
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
        modifier = Modifier.semantics { testTagsAsResourceId = true },
        snackbarHost = { SnackbarHost(snackbarHostState) },
        topBar = {
            TopAppBar(
                title = {
                    Row(
                        modifier = Modifier.clickable {
                            if (uiState.session.isGroupChat) onGroupSettingsClick()
                            else if (!uiState.session.isBroadcast) onProfileClick(viewModel.recipientId)
                        },
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        UserAvatar(
                            avatarUrl = uiState.avatarUrl,
                            contentDescription = null,
                            icon = if (uiState.session.isGroupChat) Icons.Default.Group else Icons.Default.Person,
                            size = 36.dp,
                            modifier = Modifier.size(36.dp),
                            localAvatarPath = uiState.localAvatarPath
                        )
                        Spacer(modifier = Modifier.width(10.dp))
                        Column {
                            Text(
                                text = uiState.session.chatName ?: "Chat",
                                style = MaterialTheme.typography.titleLarge,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                            when {
                                uiState.session.isRecipientOnline -> Text(
                                    text = "Online",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.8f)
                                )
                                uiState.session.isBroadcast && uiState.broadcastRecipientCount > 0 -> Text(
                                    text = "${uiState.broadcastRecipientCount} ${if (uiState.broadcastRecipientCount == 1) "recipient" else "recipients"}",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.7f)
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
                    if (!uiState.session.isGroupChat && !uiState.session.isBroadcast) {
                        IconButton(onClick = {
                            val callIntent = Intent(context, CallActivity::class.java).apply {
                                putExtra(CallActivity.EXTRA_ACTION, CallActivity.ACTION_OUTGOING)
                                putExtra(CallActivity.EXTRA_CALLEE_ID, viewModel.recipientId)
                                putExtra(CallActivity.EXTRA_CALLEE_NAME, uiState.session.chatName ?: "")
                                putExtra(CallActivity.EXTRA_CALLEE_AVATAR_URL, uiState.session.recipientAvatarUrl)
                                putExtra(CallActivity.EXTRA_CHAT_ID, viewModel.chatId)
                            }
                            context.startActivity(callIntent)
                        }) {
                            Icon(
                                imageVector = Icons.Default.Phone,
                                contentDescription = "Voice call",
                                tint = MaterialTheme.colorScheme.onBackground
                            )
                        }
                    }
                    IconButton(onClick = { viewModel.toggleSearch() }) {
                        Icon(
                            imageVector = Icons.Default.Search,
                            contentDescription = "Search messages",
                            tint = MaterialTheme.colorScheme.onBackground
                        )
                    }
                    Box {
                        IconButton(onClick = { showOverflowMenu = true }) {
                            Icon(
                                imageVector = Icons.Default.MoreVert,
                                contentDescription = "More options",
                                tint = MaterialTheme.colorScheme.onBackground
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
                    containerColor = MaterialTheme.colorScheme.background,
                    titleContentColor = MaterialTheme.colorScheme.onBackground,
                    navigationIconContentColor = MaterialTheme.colorScheme.onBackground
                )
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .consumeWindowInsets(padding)
                .imePadding()
        ) {
            // Pinned message banner
            if (uiState.messages.pinnedMessages.isNotEmpty()) {
                val pinned = uiState.messages.pinnedMessages.last()
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(MaterialTheme.colorScheme.secondaryContainer)
                        .clickable {
                            val chronoIdx = uiState.messages.messages.indexOfFirst { it.id == pinned.id }
                            if (chronoIdx >= 0) {
                                val reversedIdx = uiState.messages.messages.toReversedIndex(chronoIdx)
                                scope.launch { listState.animateScrollToItem(reversedIdx) }
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
                visible = uiState.overlays.isSearchActive,
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
                        value = uiState.overlays.searchQuery,
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
            if (uiState.overlays.isSearchActive && uiState.overlays.searchQuery.isNotBlank()) {
                if (uiState.overlays.searchResults.isEmpty()) {
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
                        text = "${uiState.overlays.searchResults.size} ${if (uiState.overlays.searchResults.size == 1) "result" else "results"}",
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
                        items(uiState.overlays.searchResults, key = { "search_${it.id}" }) { message ->
                            Column(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable {
                                        val chronoIdx = uiState.messages.messages.indexOfFirst { it.id == message.id }
                                        if (chronoIdx >= 0) {
                                            scope.launch {
                                                scrollToAndCenter(uiState.messages.messages.toReversedIndex(chronoIdx))
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

            val showingSearchResults = uiState.overlays.isSearchActive && uiState.overlays.searchQuery.isNotBlank() && uiState.overlays.searchResults.isNotEmpty()
            if (!showingSearchResults) {
                when {
                    uiState.session.isLoading -> {
                        Box(modifier = Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                            CircularProgressIndicator()
                        }
                    }
                    else -> {
                        // Scroll-to-bottom: show FAB when more than 2 screens from the newest
                        // message. With reverseLayout, "at the bottom" means firstVisibleIdx == 0,
                        // so distance to the newest is just `firstVisibleIdx`.
                        // derivedStateOf prevents recomposition on every scroll frame — the
                        // boolean only changes when the FAB needs to appear or disappear.
                        val totalItems = uiState.messages.messages.size
                        val showScrollToBottom by remember(totalItems) {
                            derivedStateOf {
                                val visInfo = listState.layoutInfo.visibleItemsInfo
                                val firstIdx = visInfo.firstOrNull()?.index ?: 0
                                val visible = visInfo.size
                                totalItems > 0 && firstIdx > visible * 2
                            }
                        }

                        Box(modifier = Modifier.weight(1f).fillMaxWidth()) {
                        // Index 0 = newest (anchored at viewport bottom by reverseLayout=true),
                        // lastIndex = oldest.
                        val reversed = remember(uiState.messages.messages) {
                            uiState.messages.messages.asReversed()
                        }
                        LazyColumn(
                            state = listState,
                            reverseLayout = true,
                            modifier = Modifier
                                .fillMaxSize()
                                .padding(horizontal = 8.dp)
                                .testTag("message_list")
                        ) {
                            itemsIndexed(reversed, key = { _, msg -> msg.id }) { index, message ->
                                // Date separator renders above the oldest message of each day.
                                // In the reversed view, the chronologically older neighbour lives
                                // at `index + 1`; `index == reversed.lastIndex` is the very oldest.
                                val showSeparator = index == reversed.lastIndex ||
                                    !isSameDay(message.timestamp, reversed[index + 1].timestamp)
                                if (showSeparator) {
                                    DateSeparator(formatDateSeparator(message.timestamp))
                                }
                                // computeGroupPosition expects (message, chronologically-previous,
                                // chronologically-next). In the reversed view, chronologically-prev
                                // is the older neighbour at `index + 1` and chronologically-next
                                // is the newer neighbour at `index - 1`.
                                val prevMessage = if (index < reversed.lastIndex) reversed[index + 1] else null
                                val nextMessage = if (index > 0) reversed[index - 1] else null
                                val groupPosition = computeGroupPosition(message, prevMessage, nextMessage)
                                val topPadding = when (groupPosition) {
                                    GroupPosition.MIDDLE, GroupPosition.LAST -> 2.dp
                                    else -> 4.dp
                                }
                                val isOwn = message.senderId == uiState.session.currentUserId
                                val replyToMessage = message.replyToId?.let { id ->
                                    uiState.messages.messages.find { it.id == id }
                                }
                                val linkPreview = if (message.type == MessageType.TEXT) {
                                    uiState.overlays.linkPreviews.entries.firstOrNull { (url, _) ->
                                        message.content.contains(url)
                                    }?.value
                                } else null

                                Column(modifier = Modifier.animateItem().padding(top = topPadding)) {
                                if (message.type == MessageType.POLL) {
                                    PollBubble(
                                        message = message,
                                        isOwnMessage = isOwn,
                                        currentUserId = uiState.session.currentUserId,
                                        onVote = { optionIds -> viewModel.votePoll(message.id, optionIds) },
                                        onClose = { viewModel.closePoll(message.id) }
                                    )
                                } else if (message.type == MessageType.LIST) {
                                    ListBubble(
                                        message = message,
                                        listData = uiState.overlays.listDataCache[message.listId],
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
                                            currentUserId = uiState.session.currentUserId,
                                            readReceiptsAllowed = uiState.session.readReceiptsAllowed && !uiState.session.isBroadcast,
                                            userIdToDisplayName = uiState.session.participantNameMap,
                                            callbacks = MessageBubbleCallbacks(
                                                onDelete = if (isOwn) {
                                                    { viewModel.deleteMessage(message.id) }
                                                } else null,
                                                onEdit = if (isOwn && message.type == MessageType.TEXT) {
                                                    { viewModel.startEdit(message) }
                                                } else null,
                                                onReply = { viewModel.setReplyTo(message) },
                                                onReaction = { reactionTargetMessage = message },
                                                onForward = { forwardTargetMessage = message },
                                                onStar = { viewModel.toggleStar(message) },
                                                onPin = {
                                                    viewModel.togglePin(message.id, !message.isPinned)
                                                },
                                                onInfo = if (isOwn) {
                                                    {
                                                        val chatParticipants = uiState.session.availableChats
                                                            .find { it.id == message.chatId }
                                                            ?.participants ?: emptyList()
                                                        onMessageInfoClick(message, chatParticipants)
                                                    }
                                                } else null,
                                                onSwipeReact = { swipeReactMessage = message },
                                                onImageClick = { _ ->
                                                    fullscreenImage = FullscreenImage(
                                                        imageUrl = message.mediaUrl ?: "",
                                                        localUri = message.localUri,
                                                        onSaveToDownloads = {
                                                            viewModel.saveImageToDownloads(message.localUri, message.mediaUrl)
                                                        },
                                                    )
                                                },
                                                onPreviewImageClick = { url ->
                                                    fullscreenImage = FullscreenImage(imageUrl = url)
                                                },
                                                onReplyPreviewClick = {
                                                    replyToMessage?.id?.let { jumpToSourceMessage(it) }
                                                },
                                                onCall = if (message.type == MessageType.CALL && !uiState.session.isGroupChat && !uiState.session.isBroadcast) {
                                                    {
                                                        val callIntent = Intent(context, CallActivity::class.java).apply {
                                                            putExtra(CallActivity.EXTRA_ACTION, CallActivity.ACTION_OUTGOING)
                                                            putExtra(CallActivity.EXTRA_CALLEE_ID, viewModel.recipientId)
                                                            putExtra(CallActivity.EXTRA_CALLEE_NAME, uiState.session.chatName ?: "")
                                                            putExtra(CallActivity.EXTRA_CALLEE_AVATAR_URL, uiState.session.recipientAvatarUrl)
                                                            putExtra(CallActivity.EXTRA_CHAT_ID, viewModel.chatId)
                                                        }
                                                        context.startActivity(callIntent)
                                                    }
                                                } else null,
                                            ),
                                            state = MessageBubbleState(
                                                uploadProgress = uploadProgressMap[message.id],
                                                isHighlighted = highlightedMessageId == message.id,
                                            ),
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
                                                    recentEmojis = uiState.overlays.recentEmojis,
                                                    currentReaction = message.reactions[uiState.session.currentUserId],
                                                    onEmojiSelected = { emoji ->
                                                        val isAdding = message.reactions[uiState.session.currentUserId] != emoji
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
                                        listState.animateScrollToItem(0)
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
            if (uiState.session.typingUserIds.isNotEmpty()) {
                TypingIndicator(
                    dotColor = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(start = 8.dp, top = 6.dp, bottom = 6.dp)
                )
            }

            // Edit mode banner
            if (uiState.composer.editingMessage != null) {
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
            if (uiState.composer.replyToMessage != null) {
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
                            text = uiState.composer.replyToMessage!!.content.take(60),
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
            if (!uiState.composer.canSendMessages && uiState.composer.isAnnouncementMode) {
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
            if (uiState.session.isRecipientBlocked && !uiState.session.isGroupChat && !uiState.session.isBroadcast) {
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
            if (uiState.composer.mentionCandidates.isNotEmpty()) {
                LazyColumn(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = 180.dp)
                        .background(MaterialTheme.colorScheme.surface)
                ) {
                    items(uiState.composer.mentionCandidates, key = { it.uid }) { user ->
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
            if (uiState.composer.canSendMessages && !uiState.session.isRecipientBlocked) Row(
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
                                if (uiState.composer.editingMessage == null) viewModel.onTypingWithMentions(newText)
                            }
                        },
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(
                                start = 16.dp,
                                end = if (uiState.composer.editingMessage == null) 48.dp else 16.dp,
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
                                        text = if (uiState.composer.editingMessage != null) "Edit message..."
                                               else stringResource(R.string.type_message),
                                        style = MaterialTheme.typography.bodyMedium,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                                innerTextField()
                            }
                        }
                    )
                    if (uiState.composer.editingMessage == null) {
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
                    enabled = messageText.isNotBlank() && !uiState.composer.isSending
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
                    recentEmojis = uiState.overlays.recentEmojis,
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
                currentReaction = targetMsg.reactions[uiState.session.currentUserId],
                recentEmojis = uiState.overlays.recentEmojis,
                onEmojiSelected = { emoji, _ ->
                    val isAdding = targetMsg.reactions[uiState.session.currentUserId] != emoji
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
            chats = uiState.session.availableChats,
            currentUserId = uiState.session.currentUserId,
            onDismiss = { forwardTargetMessage = null },
            onForward = { chatId, recipientId ->
                viewModel.forwardMessage(targetMsg, chatId, recipientId)
                forwardTargetMessage = null
            },
            users = uiState.session.chatParticipants
        )
    }

    BackHandler(enabled = fullscreenImage != null) {
        fullscreenImage = null
    }

    AnimatedVisibility(visible = fullscreenImage != null, enter = fadeIn(), exit = fadeOut()) {
        fullscreenImage?.let { req ->
            FullscreenImageViewer(
                imageUrl = req.imageUrl,
                localUri = req.localUri,
                onDismiss = { fullscreenImage = null },
                onSaveToDownloads = req.onSaveToDownloads,
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
    if (uiState.composer.editingMessage != null) {
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
