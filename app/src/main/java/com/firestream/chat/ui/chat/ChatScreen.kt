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
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateIntAsState
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.tween
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
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.isImeVisible
import androidx.compose.foundation.layout.navigationBars
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
import androidx.compose.material.icons.outlined.Keyboard
import androidx.compose.material.icons.filled.CameraAlt
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Videocam
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.Group
import androidx.compose.material.icons.filled.Checklist
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Stop
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
import androidx.compose.material3.SnackbarResult
import androidx.compose.runtime.Composable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.MutableIntState
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.layout.layout
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
import androidx.compose.ui.unit.Constraints
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
import com.firestream.chat.ui.chat.command.resolveRemindTarget
import com.firestream.chat.ui.chat.widget.RemindWidget
import com.firestream.chat.ui.components.UserAvatar
import com.firestream.chat.domain.model.MessageStatus
import com.firestream.chat.domain.model.MessageType
import com.firestream.chat.domain.model.TimerState
import com.firestream.chat.ui.theme.FsSurface3
import com.firestream.chat.ui.theme.LocalIsDarkTheme
import com.firestream.chat.ui.theme.SentBubble
import androidx.compose.runtime.snapshotFlow
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

// Max emoji size multiplier shown in the input field — keeps tall emoji from overflowing maxLines.
private const val INPUT_EMOJI_SIZE_CAP = 2.0f

private val searchResultDateFormat = SimpleDateFormat("MMM d, HH:mm", Locale.getDefault())

// Chronological↔reversed index translation at the LazyColumn boundary.
// The LazyColumn runs `reverseLayout = true` so `firstVisibleItemIndex` and
// every `scrollToItem` target is in reversed space (0 = newest); the message
// list, `indexOfFirst`, persistence, and `computeGroupPosition` all use
// chronological space (0 = oldest). Since reversal is an involution, the
// same function works both directions.
private fun List<Message>.toReversedIndex(idx: Int): Int = lastIndex - idx

// Which way the jump-to-reaction FAB's arrow points: UP if the reacted message
// is above the current viewport, DOWN if below.
private enum class ReactionFabDirection { UP, DOWN }

@OptIn(
    ExperimentalMaterial3Api::class,
    ExperimentalLayoutApi::class,
    kotlinx.coroutines.FlowPreview::class,
)
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
    // Invoked once when the message list (or the empty state of a fresh chat)
    // becomes visible. MainActivity uses it to release the splash screen.
    onContentSettled: () -> Unit = {},
    viewModel: ChatViewModel = hiltViewModel()
) {
    val uiState by viewModel.uiState.collectAsState()
    val uploadProgressMap by viewModel.uploadProgress.collectAsState()
    var messageText by rememberSaveable { mutableStateOf("") }
    // Tracks char-index → size multiplier for emojis inserted via the picker.
    // Indices are based on messageText.length at insertion time and cleared on send/cancel.
    var pendingEmojiSizes by remember { mutableStateOf(emptyMap<Int, Float>()) }
    var inputCursor by remember { mutableStateOf(TextRange(0)) }
    // The IME's composing region, echoed back into every rebuilt TextFieldValue.
    // Must be nulled on programmatic text writes (send/clear/dictation/emoji/…) —
    // but never silently dropped on IME edits, or Compose restarts the input
    // session per keystroke (see buildComposerValue / docs/GOTCHAS.md).
    var inputComposition by remember { mutableStateOf<TextRange?>(null) }
    // Per-session anchor for live dictation. -1 = no active dictation session.
    // First commit sets the anchor at inputCursor.start; each subsequent partial
    // replaces text from anchor to anchor+lastLen.
    var dictationAnchor by remember { mutableIntStateOf(-1) }
    var dictationLastLen by remember { mutableIntStateOf(0) }
    // Sentinel: messageText value last written by the dictation collector. If
    // BasicTextField.onValueChange fires while listening with text != this, the
    // change came from the user (typing, cursor move) → cancel dictation.
    var lastDictationWriteText by remember { mutableStateOf<String?>(null) }
    val listState = rememberLazyListState()
    var initialScrollDone by remember { mutableStateOf(false) }
    // Content-first: the message area shows as soon as messages are loaded AND
    // the initial scroll target is resolvable (the DataStore read is prefetched
    // in ChatViewModel.init and only gates the process-death restore path).
    val persistedScroll by viewModel.persistedScrollState.collectAsState()
    val contentReady = isChatContentReady(
        isLoading = uiState.session.isLoading,
        fromNotification = fromNotification,
        hasSavedScrollIndex = viewModel.savedScrollIndex >= 0,
        persistedScrollResolved = persistedScroll is PersistedScrollState.Ready,
    )
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val density = LocalDensity.current
    var showAttachmentSheet by remember { mutableStateOf(false) }
    var showCreatePollSheet by remember { mutableStateOf(false) }
    var showCreateListSheet by remember { mutableStateOf(false) }
    var showLocationSheet by remember { mutableStateOf(false) }
    var showEmojiPanel by remember { mutableStateOf(false) }
    var showOverflowMenu by remember { mutableStateOf(false) }
    // Lives in ChatUiState (OverlaysState slice) so the open viewer survives
    // activity recreation on rotation — see FullscreenImage in ChatOverlaysState.
    val fullscreenImage = uiState.overlays.fullscreenImage
    val fullscreenVideo = uiState.overlays.fullscreenVideo
    val sheetState = rememberModalBottomSheetState()
    val keyboardController = LocalSoftwareKeyboardController.current
    val composerFocusRequester = remember { FocusRequester() }
    val configuration = LocalConfiguration.current
    val screenHeightDp = configuration.screenHeightDp
    val screenWidthDp = configuration.screenWidthDp
    // Height that shows exactly 5 emoji rows: search bar + 5 rows + category toolbar
    val emojiPanelHeightDp = run {
        val cellDp = (screenWidthDp - 30) / 8  // 8 cols, 16dp h-padding + 7×2dp gaps
        52 + 5 * cellDp + 4 * 2 + 40           // search + rows + row-gaps + toolbar
    }

    val imeVisible = WindowInsets.isImeVisible
    // Largest IME overlap (ime − navBars) seen in this configuration: the emoji
    // panel adopts the keyboard's height so the keyboard↔panel handoff is
    // same-height. Keyed on orientation/size so rotation and split-screen
    // resizes discard stale heights.
    val imeMaxOverlapPx = remember(configuration.orientation, screenWidthDp, screenHeightDp) {
        mutableIntStateOf(0)
    }
    val imeInsets = WindowInsets.ime
    val navBarInsets = WindowInsets.navigationBars
    val panelContentDp =
        if (imeMaxOverlapPx.intValue > 0) with(density) { imeMaxOverlapPx.intValue.toDp() }
        else emojiPanelHeightDp.dp
    val panelTargetPx = if (showEmojiPanel) with(density) { panelContentDp.roundToPx() } else 0
    // Snap while the IME is on screen — the IME's own slide is the animation and
    // the reserved space must change instantly beneath it. Tween only for cold
    // open/close with no keyboard involved.
    val animatedPanelPx by animateIntAsState(
        targetValue = panelTargetPx,
        animationSpec = if (imeVisible) snap() else tween(250, easing = FastOutSlowInEasing),
        label = "emojiPanelHeight"
    )

    // Registered before the dictation BackHandler below so dictation keeps
    // precedence (Compose gives it to the later-registered handler).
    BackHandler(enabled = showEmojiPanel && !imeVisible) { showEmojiPanel = false }

    // Reaction picker state
    var reactionTargetMessage by remember { mutableStateOf<Message?>(null) }
    // Swipe-to-react panel state
    var swipeReactMessage by remember { mutableStateOf<Message?>(null) }
    // ID of the message whose reaction chips should be scrolled into view after reacting
    var reactionScrollTarget by remember { mutableStateOf<String?>(null) }

    // Snackbar host state
    val snackbarHostState = remember { SnackbarHostState() }
    // Separate host for snackbars shown while the fullscreen image viewer is covering the Scaffold.
    val fullscreenSnackbarHostState = remember { SnackbarHostState() }

    // Forward picker state
    var forwardTargetMessage by remember { mutableStateOf<Message?>(null) }

    // Snooze picker sheet state
    var snoozeTargetMessage by remember { mutableStateOf<Message?>(null) }

    // Highlight the source message when the user taps a reply preview.
    // Cleared after the animation window so the border fades out.
    var highlightedMessageId by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(highlightedMessageId) {
        if (highlightedMessageId != null) {
            delay(1500)
            highlightedMessageId = null
        }
    }

    // A reaction landed on one of my messages that's currently off-screen: hold
    // its id so the pink jump-to-reaction FAB appears. Cleared when the user taps
    // the FAB or scrolls the message into view (see the auto-clear effect below).
    var pendingReactionMessageId by remember { mutableStateOf<String?>(null) }

    // Deep-link jump target (reminder / FCM notification tap), collected as
    // state: a warm tap while this chat is already on top re-navigates
    // launchSingleTop, reusing the entry + ViewModel, so the value can change in
    // place. The route always carries the query key, so blank ⇒ null.
    val targetMessageId = viewModel.targetMessageId.collectAsState().value
        ?.takeIf { it.isNotBlank() }
    // Consumed-once flag for the deep-link jump, keyed on the target id: a new
    // target (warm re-tap) resets it so the new jump runs; rotation restores the
    // saved value so the same jump doesn't re-fire.
    var targetJumpConsumed by rememberSaveable(targetMessageId) { mutableStateOf(false) }

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

    // Apply the initial scroll position in the SAME frame the list first
    // composes with data: SideEffect runs after this composition applies but
    // before its measure/draw, and requestScrollToItem (non-suspending) sets
    // the position the first layout pass will use — the first visible frame is
    // already at the target, so there is no populate-then-jump. When opened
    // from a notification, always land on the newest message — the saved
    // index would point to wherever the user last scrolled.
    // Precedence: SavedStateHandle (same-process) > DataStore (cross-process) > tail.
    //
    // Saved indices are chronological; `toReversedIndex` flips them at the
    // scroll boundary. "Newest message" is reversed index 0.
    if (contentReady && !initialScrollDone && uiState.messages.messages.isNotEmpty()) {
        val messages = uiState.messages.messages
        val savedIndex = viewModel.savedScrollIndex
        val persistedPos = (persistedScroll as? PersistedScrollState.Ready)?.pos
        // A pending deep-link jump suppresses the persisted-position restore
        // (like fromNotification): land on the newest message, then the jump
        // effect scrolls to the target once it loads — so the restore doesn't
        // fight the jump.
        val targetJumpPending = targetMessageId != null && !targetJumpConsumed
        val (initialIndex, initialOffset) = when {
            fromNotification || targetJumpPending -> 0 to 0
            savedIndex in messages.indices ->
                messages.toReversedIndex(savedIndex) to viewModel.savedScrollOffset
            persistedPos != null && persistedPos.index in messages.indices ->
                messages.toReversedIndex(persistedPos.index) to persistedPos.offset
            else -> 0 to 0
        }
        SideEffect {
            listState.requestScrollToItem(initialIndex, initialOffset)
            initialScrollDone = true
            onContentSettled()
        }
    }

    // A chat with no messages yet has nothing to scroll — mark done so the
    // tail-follow effects arm, and release the splash on a launch restore.
    LaunchedEffect(contentReady, uiState.messages.messages.isEmpty()) {
        if (contentReady && uiState.messages.messages.isEmpty() && !initialScrollDone) {
            initialScrollDone = true
            onContentSettled()
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

    // Notification deep link: once the target message has loaded from Room,
    // scroll to and flash it (reusing jumpToSourceMessage's 1.5s highlight).
    // Runs after the initial scroll positioning (which suppresses the persisted
    // restore for a targeted open — see the initial-scroll block above) so the
    // jump isn't overwritten. An older message may not be in the first cached
    // batch, so wait up to 3s for it to appear before giving up.
    LaunchedEffect(targetMessageId, initialScrollDone) {
        val targetId = targetMessageId ?: return@LaunchedEffect
        if (targetJumpConsumed || !initialScrollDone) return@LaunchedEffect
        val found = withTimeoutOrNull(3000L) {
            snapshotFlow { uiState.messages.messages.any { it.id == targetId } }
                .first { it }
        }
        targetJumpConsumed = true
        if (found == true) {
            jumpToSourceMessage(targetId)
        } else {
            snackbarHostState.showSnackbar(
                "Message no longer available",
                duration = SnackbarDuration.Short,
            )
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

    // Same treatment for dictation errors — without this the recording bar opens
    // briefly, the recognizer fires onError, and the bar closes with no user feedback.
    LaunchedEffect(uiState.dictation.error) {
        uiState.dictation.error?.let { error ->
            snackbarHostState.showSnackbar(error.message, duration = SnackbarDuration.Short)
            viewModel.clearDictationError()
        }
    }

    // Forward any snackbarEvent emissions (e.g. "Saved to Downloads") to the correct host.
    // When the fullscreen viewer is covering the Scaffold, route to fullscreenSnackbarHostState
    // so the message appears on top of the viewer rather than being hidden behind it.
    LaunchedEffect(Unit) {
        viewModel.snackbarEvent.collect { event ->
            // Live read (not the composition-captured local): this collector runs
            // for the screen's lifetime and must route based on the viewer's
            // CURRENT visibility, not its state when the effect launched.
            val host = if (uiState.overlays.fullscreenImage != null) fullscreenSnackbarHostState else snackbarHostState
            val result = host.showSnackbar(
                message = event.message,
                actionLabel = event.actionLabel,
                duration = SnackbarDuration.Short,
            )
            if (result == SnackbarResult.ActionPerformed && event.actionUri != null) {
                context.startActivity(
                    Intent(Intent.ACTION_VIEW, event.actionUri).apply {
                        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    }
                )
            }
        }
    }

    // A reaction another user just added to one of my messages, delivered on the
    // MessagesState slice alongside the very list it was diffed from (see
    // ChatMessageLoader.detectReactionCue). On-screen reacted bubble → flash its
    // pink border in place; off-screen → hold the id so the pink jump-to-reaction
    // FAB can offer to scroll there.
    val newOwnReaction = uiState.messages.newOwnReaction
    LaunchedEffect(newOwnReaction) {
        val alert = newOwnReaction ?: return@LaunchedEffect
        val chronoIdx = uiState.messages.messages.indexOfFirst { it.id == alert.messageId }
        if (chronoIdx < 0) {
            viewModel.consumeReactionCue()
            return@LaunchedEffect
        }
        val reversedIdx = uiState.messages.messages.toReversedIndex(chronoIdx)
        // Wait for a laid-out list before ruling on visible-vs-off-screen. On the
        // frame a reaction lands visibleItemsInfo can still be empty, and reading it
        // too early silently swallows the cue: "not visible" is indistinguishable
        // from "nothing measured yet", so an off-screen reaction would flash a bubble
        // nobody can see instead of raising the FAB.
        val visibleIndices = withTimeoutOrNull(1000L) {
            snapshotFlow { listState.layoutInfo.visibleItemsInfo.map { it.index } }
                .first { it.isNotEmpty() }
        }
        if (visibleIndices != null && reversedIdx in visibleIndices) {
            highlightedMessageId = alert.messageId
            if (pendingReactionMessageId == alert.messageId) pendingReactionMessageId = null
        } else {
            pendingReactionMessageId = alert.messageId
        }
        viewModel.consumeReactionCue()
    }

    // Dismiss the jump-to-reaction FAB once its target scrolls into view on its own.
    LaunchedEffect(pendingReactionMessageId) {
        val id = pendingReactionMessageId ?: return@LaunchedEffect
        snapshotFlow {
            val chronoIdx = uiState.messages.messages.indexOfFirst { it.id == id }
            if (chronoIdx < 0) return@snapshotFlow true // message gone → drop the FAB
            val reversedIdx = uiState.messages.messages.toReversedIndex(chronoIdx)
            listState.layoutInfo.visibleItemsInfo.any { it.index == reversedIdx }
        }
            .distinctUntilChanged()
            .collect { visible -> if (visible) pendingReactionMessageId = null }
    }

    LaunchedEffect(uiState.composer.editingMessage) {
        val editing = uiState.composer.editingMessage
        if (editing != null) {
            messageText = editing.content
            inputCursor = TextRange(editing.content.length)
            inputComposition = null
            pendingEmojiSizes = editing.emojiSizes
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
    var cameraVideoUri by rememberSaveable { mutableStateOf<Uri?>(null) }
    var pendingMediaUri by rememberSaveable { mutableStateOf<Uri?>(null) }
    var pendingMediaMimeType by rememberSaveable { mutableStateOf("image/jpeg") }

    val galleryLauncher = rememberLauncherForActivityResult(PickVisualMedia()) { uri ->
        if (uri != null) {
            pendingMediaMimeType = context.contentResolver.getType(uri) ?: "image/jpeg"
            pendingMediaUri = uri
        }
    }

    val cameraLauncher = rememberLauncherForActivityResult(ActivityResultContracts.TakePicture()) { success ->
        if (success) cameraUri?.let {
            pendingMediaMimeType = "image/jpeg"
            pendingMediaUri = it
        }
    }

    val cameraVideoLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CaptureVideo()) { success ->
        if (success) cameraVideoUri?.let {
            pendingMediaMimeType = "video/mp4"
            pendingMediaUri = it
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

    val cameraVideoPermissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) {
            cameraVideoUri = createCameraVideoUri(context)
            cameraVideoUri?.let { cameraVideoLauncher.launch(it) }
        }
    }

    val galleryPermissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { _ ->
        galleryLauncher.launch(PickVisualMediaRequest(PickVisualMedia.ImageAndVideo))
    }

    val locationPermissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) showLocationSheet = true
    }

    val micPermissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) viewModel.startDictation()
        else openAppSettings(context)
    }

    // Hide the IME while dictating (matches Gboard's own voice input, and
    // avoids the IME composing-region vs programmatic-rewrite conflict that
    // can wedge Gboard on this field); reset the anchor when dictation ends
    // (user stop, cancel, or back-press).
    LaunchedEffect(uiState.dictation.isListening) {
        if (uiState.dictation.isListening) {
            keyboardController?.hide()
        } else {
            dictationAnchor = -1
            dictationLastLen = 0
            lastDictationWriteText = null
        }
    }

    // Live-write dictation events into the editable message field. Each Partial
    // and Final replaces the text from the anchor to anchor+lastLen with the
    // event's cumulative text and advances the cursor to the end of the
    // dictated region. Anchor reset is handled by the isListening effect above.
    // Liveness is read from the ViewModel's StateFlow, not the collectAsState
    // local — the Compose copy lags a frame, and a stale-true value here is
    // exactly what lets a leaked recognizer overwrite user typing.
    LaunchedEffect(Unit) {
        viewModel.dictationCommits.collect { event ->
            val apply = applyDictationCommit(
                currentText = messageText,
                cursorStart = inputCursor.start,
                anchor = dictationAnchor,
                lastLen = dictationLastLen,
                commitText = event.text,
                isListening = viewModel.uiState.value.dictation.isListening,
            ) ?: return@collect
            dictationAnchor = apply.newAnchor
            dictationLastLen = apply.newLastLen
            messageText = apply.newText
            lastDictationWriteText = apply.newText
            inputCursor = TextRange(apply.newCursorIndex)
            inputComposition = null
        }
    }

    BackHandler(enabled = uiState.dictation.isListening) { viewModel.cancelDictation() }

    DisposableEffect(Unit) {
        onDispose { viewModel.cancelDictation() }
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
                // No blanket imePadding(): the IME lift is provided by the
                // keyboard/emoji bottom region at the end of this Column
                // (imeOrPanelHeight), so the emoji panel can share the
                // keyboard's space for a same-height handoff.
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
                    !contentReady -> {
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

                                // Keep the date separator and the bubble inside ONE lazy-item node.
                                // With reverseLayout=true, multiple sibling composables emitted
                                // directly by a single item are placed bottom-to-top, which would
                                // render the separator *below* its message — landing it between two
                                // consecutive same-day messages instead of above the first. Wrapping
                                // them in one Column pins the separator above the day's first message.
                                // fadeInSpec = null: no per-item appearance fade — on the
                                // first population EVERY item would animate, which is the
                                // biggest avoidable cost while the nav slide is running.
                                // Placement animation (inserts/reorders) stays.
                                Column(modifier = Modifier.animateItem(fadeInSpec = null).fillMaxWidth()) {
                                if (showSeparator) {
                                    DateSeparator(formatDateSeparator(message.timestamp))
                                }
                                Column(modifier = Modifier.padding(top = topPadding)) {
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
                                        currentUserId = uiState.session.currentUserId,
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
                                        },
                                        onLongPress = { reactionTargetMessage = message }
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
                                                    viewModel.showFullscreenImage(
                                                        FullscreenImage(
                                                            imageUrl = message.mediaUrl,
                                                            localUri = message.localUri,
                                                            canSaveToDownloads = true,
                                                        )
                                                    )
                                                },
                                                onPreviewImageClick = { url ->
                                                    viewModel.showFullscreenImage(FullscreenImage(imageUrl = url))
                                                },
                                                onVideoClick = { source ->
                                                    viewModel.showFullscreenVideo(source)
                                                },
                                                onSaveImage = if (message.type == MessageType.IMAGE) {
                                                    { viewModel.saveImageToDownloads(message.localUri, message.mediaUrl) }
                                                } else null,
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
                                                onCancelTimer = if (message.type == MessageType.TIMER && message.timerState == TimerState.RUNNING) {
                                                    { viewModel.cancelTimer(message.id) }
                                                } else null,
                                                onPauseTimer = if (message.type == MessageType.TIMER && message.timerState == TimerState.RUNNING && !isOwn) {
                                                    { remainingMs -> viewModel.pauseTimer(message.id, remainingMs) }
                                                } else null,
                                                onResumeTimer = if (message.type == MessageType.TIMER && message.timerState == TimerState.PAUSED && !isOwn) {
                                                    { viewModel.resumeTimer(message.id) }
                                                } else null,
                                                onRetrySend = if (isOwn && message.status == MessageStatus.FAILED) {
                                                    { viewModel.retrySend(message) }
                                                } else null,
                                                onSnooze = if (message.deletedAt == null &&
                                                    message.id !in uiState.messages.pendingReminderIds
                                                ) {
                                                    { snoozeTargetMessage = message }
                                                } else null,
                                                onCancelReminder = if (message.id in uiState.messages.pendingReminderIds) {
                                                    { viewModel.cancelReminder(message.id) }
                                                } else null,
                                            ),
                                            state = MessageBubbleState(
                                                uploadProgress = uploadProgressMap[message.id],
                                                isHighlighted = highlightedMessageId == message.id,
                                                hasReminder = message.id in uiState.messages.pendingReminderIds,
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
                                } // inner Column with group spacing
                                } // outer Column wrapping separator + bubble
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

                        // Jump-to-reaction FAB — appears when a reaction lands on one
                        // of my messages that's off-screen. Always bottom-right: it
                        // shares the scroll-to-bottom FAB's spot when that one is
                        // hidden (at the bottom), and lifts above it when it's showing
                        // (scrolled up). The arrow points up if the reacted message is
                        // above the viewport, down if below.
                        val pendingReactionDirection by remember(pendingReactionMessageId, uiState.messages.messages) {
                            derivedStateOf {
                                val id = pendingReactionMessageId ?: return@derivedStateOf null
                                val chronoIdx = uiState.messages.messages.indexOfFirst { it.id == id }
                                if (chronoIdx < 0) return@derivedStateOf null
                                val reversedIdx = uiState.messages.messages.toReversedIndex(chronoIdx)
                                val visibleIndices = listState.layoutInfo.visibleItemsInfo.map { it.index }
                                val lastVisible = visibleIndices.maxOrNull()
                                when {
                                    // On screen; the auto-clear effect will drop the id.
                                    reversedIdx in visibleIndices -> null
                                    // Nothing measured yet — show it rather than hide the
                                    // only cue the user gets; the arrow settles next frame.
                                    lastVisible == null -> ReactionFabDirection.UP
                                    reversedIdx > lastVisible -> ReactionFabDirection.UP    // older → above view
                                    else -> ReactionFabDirection.DOWN                       // newer → below view
                                }
                            }
                        }
                        androidx.compose.animation.AnimatedVisibility(
                            visible = pendingReactionDirection != null,
                            modifier = Modifier
                                .align(Alignment.BottomEnd)
                                // Lift above the scroll-to-bottom FAB when it's visible.
                                .padding(end = 12.dp, bottom = if (showScrollToBottom) 72.dp else 12.dp),
                            enter = fadeIn() + scaleIn(),
                            exit = fadeOut() + scaleOut()
                        ) {
                            SmallFloatingActionButton(
                                onClick = {
                                    pendingReactionMessageId?.let { jumpToSourceMessage(it) }
                                    pendingReactionMessageId = null
                                },
                                containerColor = MaterialTheme.colorScheme.tertiaryContainer,
                                contentColor = MaterialTheme.colorScheme.onTertiaryContainer
                            ) {
                                Icon(
                                    imageVector = if (pendingReactionDirection == ReactionFabDirection.DOWN)
                                        Icons.Default.KeyboardArrowDown else Icons.Default.KeyboardArrowUp,
                                    contentDescription = "Jump to reaction"
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
                        inputComposition = null
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
                val replyTo = uiState.composer.replyToMessage!!
                val isImageReply = replyTo.type == MessageType.IMAGE
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
                    if (isImageReply) {
                        ReplyImageThumbnail(
                            message = replyTo,
                            modifier = Modifier.size(36.dp)
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                    }
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = "Replying to",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.primary
                        )
                        val snippet = if (isImageReply) {
                            replyTo.content.take(60)
                                .ifBlank { stringResource(R.string.reply_preview_photo) }
                        } else {
                            replyTo.content.take(60)
                        }
                        Text(
                            text = snippet,
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
                                inputComposition = null
                            }
                        )
                        HorizontalDivider()
                    }
                }
            }

            // Recording control bar — slides in above the composer while dictation is active.
            AnimatedVisibility(
                visible = uiState.dictation.isListening,
                enter = slideInVertically { it } + fadeIn(),
                exit = slideOutVertically { it } + fadeOut(),
            ) {
                DictationControlBar(
                    audioLevel = viewModel.dictationAudioLevel,
                    onCancel = { viewModel.cancelDictation() },
                )
            }

            // Exact-alarm permission banner — shown after a timer was scheduled
            // with the inexact fallback because the user denied SCHEDULE_EXACT_ALARM
            // on Android 12+. Tap "Allow" deep-links to system settings.
            ExactAlarmBanner(
                visible = uiState.commands.exactAlarmBannerVisible,
                onDismiss = { viewModel.dismissExactAlarmBanner() },
            )

            // .command palette — appears above the composer when the user types
            // `.` at message start. Tapping a row navigates into a parent or
            // mounts a leaf widget. State is owned by ChatCommandsManager and
            // driven by the LaunchedEffect(messageText) below.
            CommandPalette(
                visible = uiState.commands.isPaletteOpen,
                currentPath = uiState.commands.currentPath,
                candidates = uiState.commands.candidates,
                onCommandTap = { cmd ->
                    val newPath = uiState.commands.currentPath.append(cmd.id)
                    val mirrorText = if (cmd.children.isEmpty()) newPath.displayString()
                                     else newPath.displayString() + "."
                    messageText = mirrorText
                    inputCursor = TextRange(mirrorText.length)
                    inputComposition = null
                    pendingEmojiSizes = emptyMap()
                    viewModel.onComposerTextChangedForCommands(mirrorText)
                },
            )

            // Active command widget mounted above the composer (e.g. the timer
            // hh:mm:ss picker when `.timer.set` is the active leaf).
            uiState.commands.activeWidget?.let { widget ->
                val clearComposerInput = {
                    messageText = ""
                    inputCursor = TextRange(0)
                    inputComposition = null
                    pendingEmojiSizes = emptyMap()
                }
                when (widget) {
                    // The `.remind` widget needs the target message + sender name,
                    // which the generic ChatCommandWidget.Render contract can't supply.
                    // ChatScreen owns the slices, so it resolves the target (reply-target
                    // if selected, else newest) and calls snoozeMessage directly — a
                    // LOCAL action, mirroring the long-press SnoozePickerSheet path; no
                    // message is sent to the recipient.
                    is RemindWidget -> {
                        val target = resolveRemindTarget(
                            uiState.composer.replyToMessage,
                            uiState.messages.messages,
                        )
                        // Same name source as ChatMessageActions.senderNameFor:
                        // participantAvatars covers both 1:1 and group chats;
                        // participantNameMap is group-only.
                        val senderName = target?.senderId?.let { id ->
                            if (id == uiState.session.currentUserId) "You"
                            else uiState.session.participantAvatars[id]?.displayName
                                ?: uiState.session.chatName
                        }
                        widget.RenderContent(
                            targetMessage = target,
                            senderName = senderName,
                            detectSnoozeTime = { text -> viewModel.detectSnoozeTime(text) },
                            onConfirm = { fireAtMs ->
                                target?.let { viewModel.snoozeMessage(it, fireAtMs) }
                                viewModel.dismissCommandWidget()
                                clearComposerInput()
                            },
                            onCancel = {
                                viewModel.dismissCommandWidget()
                                clearComposerInput()
                            },
                        )
                    }
                    else -> widget.Render(
                        chatId = viewModel.chatId,
                        composerText = messageText,
                        onSend = { payload ->
                            viewModel.onCommandSubmit(payload)
                            clearComposerInput()
                        },
                        onCancel = {
                            viewModel.dismissCommandWidget()
                            clearComposerInput()
                        },
                    )
                }
            }

            // Input row — hidden when the user has blocked the recipient; the
            // "You blocked this contact" banner above replaces it.
            if (uiState.composer.canSendMessages && !uiState.session.isRecipientBlocked) Row(
                modifier = Modifier.fillMaxWidth().padding(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                IconButton(onClick = {
                    if (showEmojiPanel && !imeVisible) {
                        // Panel showing → slide the keyboard up over it. The
                        // flag stays true: the panel remains mounted underneath.
                        composerFocusRequester.requestFocus()
                        keyboardController?.show()
                    } else {
                        // Keyboard (or nothing) showing → reveal/open the panel
                        showEmojiPanel = true
                        keyboardController?.hide()
                    }
                }) {
                    val panelShowing = showEmojiPanel && !imeVisible
                    Icon(
                        imageVector = if (panelShowing) Icons.Outlined.Keyboard
                                      else Icons.Outlined.EmojiEmotions,
                        contentDescription = if (panelShowing) "Keyboard" else "Emoji",
                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }

                val clearInput = {
                    messageText = ""
                    inputCursor = TextRange(0)
                    inputComposition = null
                    pendingEmojiSizes = emptyMap()
                }
                val emojiInputSize = MaterialTheme.typography.bodyMedium.fontSize
                val inputAnnotated = remember(messageText, pendingEmojiSizes, emojiInputSize) {
                    val cappedSizes = pendingEmojiSizes.mapValues { (_, v) -> v.coerceAtMost(INPUT_EMOJI_SIZE_CAP) }
                    addEmojiSpans(messageText, emojiInputSize, cappedSizes)
                }
                val inputValue = remember(inputAnnotated, inputCursor, inputComposition) {
                    buildComposerValue(inputAnnotated, inputCursor, inputComposition)
                }
                Box(
                    modifier = Modifier
                        .weight(1f)
                        // No clip: large emoji must overflow the Row's cross-axis height constraint.
                        .border(1.dp, MaterialTheme.colorScheme.outline, RoundedCornerShape(24.dp))
                ) {
                    BasicTextField(
                        value = inputValue,
                        onValueChange = { newValue ->
                            // User interaction during dictation cancels the session
                            // without emitting a final commit, so the partial we
                            // already wrote stays and the user's edit applies on top.
                            if (uiState.dictation.isListening && newValue.text != lastDictationWriteText) {
                                viewModel.cancelDictation()
                            }
                            inputCursor = newValue.selection
                            inputComposition = newValue.composition
                            val newText = newValue.text
                            if (newText != messageText) {
                                pendingEmojiSizes = adjustEmojiIndices(messageText, newText, pendingEmojiSizes)
                                messageText = newText
                                if (uiState.composer.editingMessage == null) viewModel.onTypingWithMentions(newText)
                                viewModel.onComposerTextChangedForCommands(newText)
                            }
                        },
                        modifier = Modifier
                            .fillMaxWidth()
                            .focusRequester(composerFocusRequester)
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

                val showMic = messageText.isBlank() &&
                    uiState.composer.editingMessage == null &&
                    uiState.dictation.isAvailable
                when {
                    uiState.dictation.isListening -> IconButton(
                        onClick = { viewModel.stopDictation() }
                    ) {
                        Icon(
                            imageVector = Icons.Default.Stop,
                            contentDescription = stringResource(R.string.dictation_stop),
                            tint = MaterialTheme.colorScheme.error,
                        )
                    }
                    showMic -> IconButton(
                        onClick = {
                            if (ContextCompat.checkSelfPermission(
                                    context,
                                    Manifest.permission.RECORD_AUDIO,
                                ) == PackageManager.PERMISSION_GRANTED
                            ) {
                                viewModel.startDictation()
                            } else {
                                micPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
                            }
                        }
                    ) {
                        Icon(
                            imageVector = Icons.Default.Mic,
                            contentDescription = stringResource(R.string.dictation_start),
                            tint = MaterialTheme.colorScheme.primary,
                        )
                    }
                    else -> IconButton(
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
            }

            // Bottom region — the space "under" the composer that the keyboard
            // and the emoji panel share. Height = max(live IME overlap, animated
            // panel target): opening the panel while the keyboard is up is a
            // constant-height handoff (the IME slides off a panel already laid
            // out beneath it) and the keyboard slides back up over the still-
            // mounted panel. Unconditional so IME spacing also works when the
            // composer row is hidden (blocked contact) or the IME opens for
            // in-chat search. Replaces the Column's old blanket imePadding().
            Box(
                modifier = Modifier
                    .imeOrPanelHeight(
                        ime = imeInsets,
                        navBars = navBarInsets,
                        panelPx = { animatedPanelPx },
                        imeMaxOverlapPx = imeMaxOverlapPx
                    )
                    .clipToBounds()
            ) {
                if (showEmojiPanel || animatedPanelPx > 0) {
                    EmojiHandlerPanel(
                        mode = EmojiMode.TEXT_INPUT,
                        recentEmojis = uiState.overlays.recentEmojis,
                        onEmojiSelected = { emoji, size ->
                            val insertIdx = messageText.length
                            messageText += emoji
                            inputCursor = TextRange(messageText.length)
                            inputComposition = null
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
                                inputComposition = null
                                pendingEmojiSizes = pendingEmojiSizes - removedIdx
                            }
                        },
                        onRecentUsed = { viewModel.addRecentEmoji(it) },
                        modifier = Modifier
                            .align(Alignment.TopCenter)
                            .fillMaxWidth()
                            .height(panelContentDp)
                    )
                }
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
                            val permissions = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU)
                                arrayOf(Manifest.permission.READ_MEDIA_IMAGES, Manifest.permission.READ_MEDIA_VIDEO)
                            else arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE)
                            val hasPermission = permissions.all {
                                ContextCompat.checkSelfPermission(context, it) == PackageManager.PERMISSION_GRANTED
                            }
                            if (hasPermission) {
                                galleryLauncher.launch(PickVisualMediaRequest(PickVisualMedia.ImageAndVideo))
                            } else {
                                galleryPermissionLauncher.launch(permissions)
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
                    icon = Icons.Default.Videocam,
                    label = stringResource(R.string.attachment_record_video),
                    onClick = {
                        scope.launch { sheetState.hide() }.invokeOnCompletion {
                            showAttachmentSheet = false
                            if (ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) {
                                cameraVideoUri = createCameraVideoUri(context)
                                cameraVideoUri?.let { cameraVideoLauncher.launch(it) }
                            } else {
                                cameraVideoPermissionLauncher.launch(Manifest.permission.CAMERA)
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

    // Snooze picker sheet
    snoozeTargetMessage?.let { targetMsg ->
        SnoozePickerSheet(
            message = targetMsg,
            onDismiss = { snoozeTargetMessage = null },
            onTimeSelected = { fireAtMs ->
                viewModel.snoozeMessage(targetMsg, fireAtMs)
                snoozeTargetMessage = null
            },
            detectSnoozeTime = { text -> viewModel.detectSnoozeTime(text) },
        )
    }

    BackHandler(enabled = fullscreenImage != null) {
        viewModel.dismissFullscreenImage()
    }

    // The IME is a system window that floats above the black overlay (the
    // adjustResize window can't cover it), so an open keyboard would stay
    // parked over the fullscreen image — always retract it on entry. Keyed on
    // the boolean so switching images doesn't re-trigger.
    LaunchedEffect(fullscreenImage != null) {
        if (fullscreenImage != null) keyboardController?.hide()
    }

    AnimatedVisibility(visible = fullscreenImage != null, enter = fadeIn(), exit = fadeOut()) {
        fullscreenImage?.let { req ->
            FullscreenImageViewer(
                imageUrl = req.imageUrl,
                localUri = req.localUri,
                onDismiss = { viewModel.dismissFullscreenImage() },
                onSaveToDownloads = if (req.canSaveToDownloads) {
                    { viewModel.saveImageToDownloads(req.localUri, req.imageUrl) }
                } else null,
                snackbarHostState = fullscreenSnackbarHostState,
            )
        }
    }

    BackHandler(enabled = fullscreenVideo != null) {
        viewModel.dismissFullscreenVideo()
    }

    // Same rationale as the fullscreen-image IME retract above — the player's
    // controller overlay would otherwise sit under a parked keyboard.
    LaunchedEffect(fullscreenVideo != null) {
        if (fullscreenVideo != null) keyboardController?.hide()
    }

    AnimatedVisibility(visible = fullscreenVideo != null, enter = fadeIn(), exit = fadeOut()) {
        fullscreenVideo?.let { req ->
            FullscreenVideoPlayer(
                source = req.source,
                onDismiss = { viewModel.dismissFullscreenVideo() },
            )
        }
    }

    BackHandler(enabled = pendingMediaUri != null) {
        pendingMediaUri = null
    }

    AnimatedVisibility(visible = pendingMediaUri != null, enter = fadeIn(), exit = fadeOut()) {
        pendingMediaUri?.let { uri ->
            ImagePreviewScreen(
                imageUri = uri,
                mimeType = pendingMediaMimeType,
                recentEmojis = uiState.overlays.recentEmojis,
                onEmojiUsed = viewModel::addRecentEmoji,
                onSend = { caption ->
                    viewModel.sendMediaMessage(uri, pendingMediaMimeType, caption)
                    pendingMediaUri = null
                },
                onDismiss = { pendingMediaUri = null }
            )
        }
    }
}

@Composable
private fun DateSeparator(label: String) {
    val isDark = LocalIsDarkTheme.current
    val pillColor = if (isDark) FsSurface3 else SentBubble
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 8.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier
                .background(pillColor, RoundedCornerShape(10.dp))
                .padding(horizontal = 10.dp, vertical = 4.dp)
        )
    }
}

private fun handleSend(viewModel: ChatViewModel, uiState: ChatUiState, text: String, emojiSizes: Map<Int, Float> = emptyMap()) {
    if (uiState.composer.editingMessage != null) {
        viewModel.confirmEdit(text, emojiSizes)
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

private fun createCameraVideoUri(context: Context): Uri {
    val cacheDir = File(context.cacheDir, "camera").also { it.mkdirs() }
    val file = File(cacheDir, "video_${System.currentTimeMillis()}.mp4")
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

private fun openAppSettings(context: Context) {
    val intent = Intent(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
        data = Uri.fromParts("package", context.packageName, null)
        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    }
    runCatching { context.startActivity(intent) }
}

/**
 * Sizes the composer's bottom region to max(ime − navBars, emoji panel target),
 * replacing a blanket `imePadding()` so the keyboard and the emoji panel can
 * share the same reserved space (the keyboard slides over/off an always-there
 * panel). Insets and the animated panel value are read at measure time, so IME
 * animation frames cause relayout only — the screen never recomposes per frame
 * (the same deferral `imePadding()` relies on internally). Also records the
 * largest observed IME overlap — the keyboard's height — so the panel can match
 * it for a same-height handoff. The PATTERNS.md `snapshotFlow{ime}` ban targets
 * list-scroll coupling; this modifier never touches the list.
 */
private fun Modifier.imeOrPanelHeight(
    ime: WindowInsets,
    navBars: WindowInsets,
    panelPx: () -> Int,
    imeMaxOverlapPx: MutableIntState
): Modifier = layout { measurable, constraints ->
    val overlap = (ime.getBottom(this) - navBars.getBottom(this)).coerceAtLeast(0)
    if (overlap > imeMaxOverlapPx.intValue) imeMaxOverlapPx.intValue = overlap
    val height = maxOf(overlap, panelPx())
    val placeable = measurable.measure(Constraints.fixed(constraints.maxWidth, height))
    layout(placeable.width, placeable.height) { placeable.place(0, 0) }
}
