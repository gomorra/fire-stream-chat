package com.firestream.chat.ui.chat

import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.BrokenImage
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.outlined.EmojiEmotions
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshots.SnapshotStateMap
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.rememberVectorPainter
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import com.firestream.chat.domain.util.SizeEstimate
import com.firestream.chat.ui.chat.imageedit.AdjustImageScreen
import com.firestream.chat.ui.chat.imageedit.DrawImageScreen
import com.firestream.chat.ui.chat.imageedit.HdQualitySheet
import com.firestream.chat.ui.chat.imageedit.ImageEditActions
import com.firestream.chat.ui.chat.imageedit.ImageEditHistory
import com.firestream.chat.ui.chat.imageedit.ImageEditServices
import com.firestream.chat.ui.components.SharedMediaTile
import com.firestream.chat.ui.components.rememberVideoFrameRequest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Full-screen review of everything the user just picked, before any of it is
 * sent. One item or twenty — the screen is a pager either way, and the extra
 * chrome (page counter, thumbnail strip, per-item remove) only appears once
 * there is more than one item, so a single pick looks exactly as it always did.
 *
 * Captions are **per item**: the caption box always edits the page you are
 * looking at. They are held in [captions], keyed by the pick's URI and read
 * only inside [CaptionBar], so a keystroke invalidates the caption row rather
 * than the pager — otherwise every typed character would re-run the full-screen
 * `AsyncImage` for the current page and its neighbours.
 *
 * The editor rail ([ImageEditActions]) floats top-right over the photo, and the
 * history pill ([ImageEditHistory]) appears top-left only once the current item
 * has edits to step through. Both act on the page you are looking at, never on
 * the batch: HD, the edit history and the caption are all per-item.
 *
 * The caller gets the final list back through [onSend], captions, removals and
 * per-item HD already applied; [onDismiss] throws the whole batch away.
 *
 * [defaultIsHd] is the global "send images in HD" preference, used to render the
 * pill for an item whose own `isHd` is still null — that is, one the user has
 * not overridden. Nothing here writes the preference back.
 *
 * ### The history is a list of files the OS may delete
 *
 * `cacheDir` can be reclaimed under storage pressure at any moment, and the
 * rasterizer evicts under its own byte budget deliberately, so a cursor can end
 * up pointing at a file that is no longer there. [editStepExists] is how this
 * screen checks: the page on screen is re-resolved whenever it changes, and the
 * whole batch is re-resolved on send, each falling back to the nearest surviving
 * step and ultimately to the untouched pick. Losing undo *depth* is acceptable;
 * sending a URI that resolves to nothing is not (§4).
 */
@Composable
internal fun ImagePreviewScreen(
    items: List<PendingMedia>,
    recentEmojis: List<String>,
    defaultIsHd: Boolean,
    onEmojiUsed: (String) -> Unit,
    onSend: (List<PendingMedia>) -> Unit,
    onDownload: (PendingMedia) -> Unit,
    onDismiss: () -> Unit,
    /**
     * Everything the editor needs from the ViewModel, bundled rather than passed
     * one lambda at a time — see [ImageEditServices] for why the parameter count
     * of this screen is a correctness concern and not a matter of taste.
     */
    edit: ImageEditServices = ImageEditServices(),
    snackbarHostState: SnackbarHostState? = null,
) {
    val editStepExists = edit.editStepExists
    val onDiscardEditSteps = edit.discardEditSteps
    var drafts by rememberSaveable(items, stateSaver = PendingMedia.ListSaver) {
        mutableStateOf(items)
    }
    val captions = rememberSaveable(items, saver = CaptionsSaver) {
        mutableStateMapOf<String, String>()
    }
    // Where each item's cursor was before the user jumped to the original, so
    // Original⇄Edited comes back to the step they were on rather than to the top
    // (§2.7) — toggling off and back from step 2 of 4 must not silently re-apply
    // steps 3 and 4. Screen state, not model state: the item still has exactly
    // one cursor, and this only remembers where a peek started from.
    val stepBeforePeek = rememberSaveable(items, saver = CursorsSaver) {
        mutableStateMapOf<String, Int>()
    }

    // Removing the last remaining item is a dismissal — there is nothing left to
    // review or send.
    if (drafts.isEmpty()) {
        LaunchedEffect(Unit) { onDismiss() }
        return
    }

    val pagerState = rememberPagerState { drafts.size }
    // currentPage can briefly outrun a shrinking list after a removal.
    val currentIndex = pagerState.currentPage.coerceIn(0, drafts.lastIndex)
    val current = drafts[currentIndex]
    val isBatch = drafts.size > 1

    // Remembered against the URI it estimates, so the sheet's LaunchedEffect
    // does not restart on every recomposition and re-probe the same header.
    val currentUri = current.uri
    val hdEstimate: suspend (Boolean) -> SizeEstimate? =
        remember(currentUri, edit) { { hd -> edit.estimateSendSize(currentUri, hd) } }

    // The page an editor screen is on, pinned to the pick's own URI rather than
    // to an index: an item can be removed from the thumbnail strip while the
    // editor is open, and landing an edit on whatever slid into that index would
    // write it onto the wrong photo. Null means no editor is open.
    var editing by rememberSaveable(items, stateSaver = EditTarget.Saver) {
        mutableStateOf<EditTarget?>(null)
    }

    var currentPageZoomed by remember { mutableStateOf(false) }
    var showEmojiSheet by rememberSaveable { mutableStateOf(false) }
    var showHdSheet by rememberSaveable { mutableStateOf(false) }
    val keyboardController = LocalSoftwareKeyboardController.current

    val onRemove: (Int) -> Unit = remember(items) {
        { index ->
            drafts = drafts.toMutableList().also { list ->
                captions.remove(list[index].originalUri.toString())
                // Nothing can reach this item's steps once it leaves the batch,
                // and undo no longer frees them, so this is their last chance to
                // be collected before the next app start.
                onDiscardEditSteps(list[index].editHistory)
                list.removeAt(index)
            }
        }
    }

    // Replaces the page currently under the pager, which is the only item any of
    // the editor controls ever act on.
    fun updateCurrent(transform: (PendingMedia) -> PendingMedia) {
        drafts = drafts.toMutableList().also { it[currentIndex] = transform(it[currentIndex]) }
    }

    // The step under the cursor may have been evicted — by the OS reclaiming
    // cacheDir, or by the rasterizer's own byte budget. Re-resolve the page the
    // user is looking at rather than rendering a blank pager page; off the main
    // thread because it stats files, and only when the item actually changes.
    LaunchedEffect(currentIndex, current) {
        if (!current.hasEdits) return@LaunchedEffect
        val resolved = withContext(Dispatchers.IO) { current.onSurvivingStep(editStepExists) }
        if (resolved != current) updateCurrent { if (it == current) resolved else it }
    }

    // Throwing the batch away has to take its rasterized steps with it, and
    // `drafts` — not the caller's `items` — is where those steps live: edits
    // land here and are never lifted back out, so nothing outside this screen
    // can see them to collect them. Undo no longer frees the file it steps off,
    // so missing this path leaks every step until the next app start.
    fun dismissBatch() {
        onDiscardEditSteps(drafts.flatMap { it.editHistory })
        onDismiss()
    }

    // One handler rather than several: back closes the adjust screen, then the
    // emoji sheet, and only then throws the batch away. Overlapping BackHandlers
    // would make the answer depend on declaration order — and the one that must
    // never win by accident is the one that discards the whole pick.
    BackHandler {
        when {
            editing != null -> editing = null
            showEmojiSheet -> showEmojiSheet = false
            else -> dismissBatch()
        }
    }

    // The editor replaces this screen's content rather than floating over it as a
    // sibling: `pendingMedia` and the caption map are remembered here and stay
    // alive either way, while a sibling overlay would leave the pager beneath it
    // hit-testable and let a crop drag page the batch (§2.4).
    val editTarget = editing
    if (editTarget != null) {
        val target = editTarget
        // Read now, not when the editor opened: the byte budget evicts globally
        // oldest-first, so every other page's current step has to be named or
        // flattening this one can delete an edit on another (§3).
        val liveSteps = { drafts.map { it.uri }.toSet() }
        // Shared by both editors, because landing a flattened step is the same
        // act whichever screen produced it.
        val onEditDone: (Uri) -> Unit = { rasterized ->
            val index = drafts.indexOfFirst { it.originalUri.toString() == target.key }
            if (index >= 0) {
                val landed = drafts[index].landEdit(rasterized)
                drafts = drafts.toMutableList().also { it[index] = landed.item }
                // The other half of landing an edit, and the only moment an
                // edit file ever becomes deletable: undo cannot free the file
                // it steps off, so a Done that skips this leaks the abandoned
                // tail until `sweepStale` collects it 24 h later.
                onDiscardEditSteps(landed.abandoned)
            } else {
                // The page was removed from the strip while the editor was
                // open, so nothing can reach this step — collect it now
                // rather than leaving an orphan in the cache.
                onDiscardEditSteps(listOf(rasterized.toString()))
            }
            editing = null
        }

        when (target.editor) {
            Editor.ADJUST -> AdjustImageScreen(
                source = target.source,
                isHd = drafts.firstOrNull { it.originalUri.toString() == target.key }
                    ?.let { it.isHd ?: defaultIsHd } ?: defaultIsHd,
                services = edit,
                liveSteps = liveSteps,
                onCancel = { editing = null },
                onDone = onEditDone,
            )

            Editor.DRAW -> DrawImageScreen(
                source = target.source,
                services = edit,
                liveSteps = liveSteps,
                onCancel = { editing = null },
                onDone = onEditDone,
            )
        }
        return
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black)
            .imePadding()
    ) {
        HorizontalPager(
            state = pagerState,
            // A pan inside a zoomed image must move the image, not page away.
            userScrollEnabled = !currentPageZoomed,
            beyondViewportPageCount = 1,
            modifier = Modifier.fillMaxSize()
        ) { page ->
            val item = drafts[page]
            val isActive = page == pagerState.currentPage
            if (item.isVideo) {
                VideoFramePreview(item)
            } else {
                ZoomableBox(
                    isActive = isActive,
                    onZoomChange = { zoomed -> if (isActive) currentPageZoomed = zoomed },
                ) { transform ->
                    AsyncImage(
                        model = item.uri,
                        contentDescription = "Image preview",
                        contentScale = ContentScale.Fit,
                        modifier = Modifier
                            .fillMaxSize()
                            .then(transform)
                    )
                }
            }
        }

        // Back button
        IconButton(
            onClick = { dismissBatch() },
            modifier = Modifier
                .align(Alignment.TopStart)
                .windowInsetsPadding(WindowInsets.statusBars)
                .padding(8.dp)
                .size(40.dp)
                .background(color = Color.Black.copy(alpha = 0.5f), shape = CircleShape)
        ) {
            Icon(
                imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                contentDescription = "Back",
                tint = Color.White,
                modifier = Modifier.size(22.dp)
            )
        }

        // Tools top-right, opposite the back arrow. HD and the three editor entry
        // points are image-only; a video page keeps just the download button.
        ImageEditActions(
            isHd = if (current.isVideo) null else (current.isHd ?: defaultIsHd),
            onToggleHd = { showHdSheet = true },
            showEditTools = !current.isVideo,
            onAdjust = {
                editing = EditTarget(
                    key = current.originalUri.toString(),
                    source = current.uri,
                    editor = Editor.ADJUST,
                )
            },
            onDraw = {
                editing = EditTarget(
                    key = current.originalUri.toString(),
                    source = current.uri,
                    editor = Editor.DRAW,
                )
            },
            onDownload = { onDownload(current) },
            modifier = Modifier
                .align(Alignment.TopEnd)
                .windowInsetsPadding(WindowInsets.statusBars)
                .padding(8.dp)
        )

        // Hidden, not disabled, until this item actually has a step to walk back
        // to — a fresh pick should look untouched. Unreachable until an editor
        // screen starts producing history files.
        if (current.hasEdits) {
            ImageEditHistory(
                canUndo = current.editCursor > 0,
                canRedo = current.editCursor < current.editHistory.size,
                showingOriginal = current.editCursor == 0,
                // Undo and redo are deliberate moves along the axis, so they
                // retire any half-finished peek rather than letting it snap the
                // cursor back to where the user no longer is.
                onUndo = {
                    stepBeforePeek.remove(current.originalUri.toString())
                    updateCurrent { it.copy(editCursor = it.editCursor - 1) }
                },
                onRedo = {
                    stepBeforePeek.remove(current.originalUri.toString())
                    updateCurrent { it.copy(editCursor = it.editCursor + 1) }
                },
                onToggleOriginal = {
                    val key = current.originalUri.toString()
                    updateCurrent { item ->
                        if (item.editCursor == 0) {
                            val back = stepBeforePeek.remove(key) ?: item.editHistory.size
                            item.copy(editCursor = back.coerceIn(0, item.editHistory.size))
                        } else {
                            stepBeforePeek[key] = item.editCursor
                            item.copy(editCursor = 0)
                        }
                    }
                },
                modifier = Modifier
                    .align(Alignment.TopStart)
                    .windowInsetsPadding(WindowInsets.statusBars)
                    .padding(start = 12.dp, top = 56.dp)
            )
        }

        if (isBatch) {
            Text(
                text = "${currentIndex + 1} / ${drafts.size}",
                style = MaterialTheme.typography.labelLarge,
                color = Color.White,
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .windowInsetsPadding(WindowInsets.statusBars)
                    // Below the tool rail, not beside it: the rail is wide enough
                    // that a centred counter on the same line runs into it.
                    .padding(top = 56.dp)
                    .background(
                        color = Color.Black.copy(alpha = 0.5f),
                        shape = RoundedCornerShape(12.dp)
                    )
                    .padding(horizontal = 12.dp, vertical = 4.dp)
            )
        }

        Column(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .windowInsetsPadding(WindowInsets.navigationBars)
                .fillMaxWidth()
        ) {
            if (isBatch) {
                ThumbnailStrip(
                    items = drafts,
                    selectedIndex = currentIndex,
                    onSelect = { index -> pagerState.requestScrollToPage(index) },
                    onRemove = onRemove
                )
            }

            CaptionBar(
                captions = captions,
                // Keyed by the pick, not by `uri`: the displayed URI moves every
                // time an edit lands, and a caption must not move with it.
                captionKey = current.originalUri.toString(),
                isBatch = isBatch,
                itemCount = drafts.size,
                showEmojiSheet = showEmojiSheet,
                recentEmojis = recentEmojis,
                onEmojiUsed = onEmojiUsed,
                onToggleEmojiSheet = {
                    keyboardController?.hide()
                    showEmojiSheet = !showEmojiSheet
                },
                onHideEmojiSheet = { showEmojiSheet = false },
                onSend = {
                    // Resolve every item, not just the visible one: the pages the
                    // user is not looking at have not been through the effect
                    // above, and a vanished step must never reach the send path.
                    // Synchronous, unlike the effect above: the send needs the
                    // answer now. Bounded by the batch size times
                    // PendingMedia.MAX_EDIT_STEPS stat calls on cacheDir, which
                    // is cheaper than the frame it would cost to defer it.
                    onSend(
                        drafts.map {
                            it.onSurvivingStep(editStepExists)
                                .copy(caption = captions[it.originalUri.toString()].orEmpty())
                        }
                    )
                }
            )
        }

        // Snackbars ("Image saved to Downloads") need a host above this overlay:
        // the Scaffold's own is behind it. Same pattern as FullscreenImageViewer.
        if (snackbarHostState != null) {
            SnackbarHost(
                hostState = snackbarHostState,
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .windowInsetsPadding(WindowInsets.navigationBars)
                    .padding(bottom = 88.dp)
            )
        }
    }

    if (showHdSheet && !current.isVideo) {
        HdQualitySheet(
            isHd = current.isHd ?: defaultIsHd,
            estimate = hdEstimate,
            onSelect = { hd ->
                updateCurrent { it.copy(isHd = hd) }
                showHdSheet = false
            },
            onDismiss = { showHdSheet = false },
        )
    }
}

/**
 * The caption field, its emoji panel and the send button.
 *
 * Takes the whole [captions] map rather than a `String` on purpose: reading the
 * current caption *here* keeps a keystroke's invalidation inside this composable
 * instead of re-running the pager and its images.
 */
@Composable
private fun CaptionBar(
    captions: SnapshotStateMap<String, String>,
    captionKey: String,
    isBatch: Boolean,
    itemCount: Int,
    showEmojiSheet: Boolean,
    recentEmojis: List<String>,
    onEmojiUsed: (String) -> Unit,
    onToggleEmojiSheet: () -> Unit,
    onHideEmojiSheet: () -> Unit,
    onSend: () -> Unit,
) {
    val caption = captions[captionKey].orEmpty()
    val screenWidthDp = LocalConfiguration.current.screenWidthDp
    val emojiPanelHeightDp = run {
        val cellDp = (screenWidthDp - 30) / 8
        52 + 5 * cellDp + 4 * 2 + 40
    }

    Column {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(Color.Black.copy(alpha = 0.6f))
                .padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(onClick = onToggleEmojiSheet) {
                Icon(
                    imageVector = Icons.Outlined.EmojiEmotions,
                    contentDescription = "Emoji",
                    tint = Color.White
                )
            }

            BasicTextField(
                value = caption,
                onValueChange = { captions[captionKey] = it },
                modifier = Modifier
                    .weight(1f)
                    .onFocusChanged { if (it.isFocused) onHideEmojiSheet() }
                    .background(
                        color = Color.White.copy(alpha = 0.15f),
                        shape = RoundedCornerShape(24.dp)
                    )
                    .padding(horizontal = 16.dp, vertical = 10.dp),
                textStyle = TextStyle(color = Color.White, fontSize = 16.sp),
                cursorBrush = SolidColor(Color.White),
                maxLines = 5,
                decorationBox = { innerTextField ->
                    if (caption.isEmpty()) {
                        Text(
                            text = if (isBatch) "Add a caption to this one..." else "Add a caption...",
                            style = TextStyle(
                                color = Color.White.copy(alpha = 0.5f),
                                fontSize = 16.sp
                            )
                        )
                    }
                    innerTextField()
                }
            )

            FloatingActionButton(
                onClick = onSend,
                modifier = Modifier
                    .padding(start = 10.dp)
                    .size(48.dp),
                containerColor = MaterialTheme.colorScheme.primary,
                shape = CircleShape
            ) {
                Icon(
                    imageVector = Icons.AutoMirrored.Filled.Send,
                    contentDescription = if (isBatch) "Send $itemCount items" else "Send",
                    tint = MaterialTheme.colorScheme.onPrimary,
                    modifier = Modifier.size(22.dp)
                )
            }
        }

        AnimatedVisibility(
            visible = showEmojiSheet,
            enter = slideInVertically { it } + fadeIn(),
            exit = slideOutVertically { it } + fadeOut()
        ) {
            EmojiHandlerPanel(
                mode = EmojiMode.TEXT_INPUT,
                recentEmojis = recentEmojis,
                onEmojiSelected = { emoji, _ -> captions[captionKey] = caption + emoji },
                onBackspace = {
                    if (caption.isNotEmpty()) {
                        val iter = java.text.BreakIterator.getCharacterInstance()
                        iter.setText(caption)
                        iter.last()
                        captions[captionKey] = caption.substring(0, iter.previous())
                    }
                },
                onRecentUsed = onEmojiUsed,
                modifier = Modifier.height(emojiPanelHeightDp.dp)
            )
        }
    }
}

/** Video page: still frame only — no inline playback, no pinch-zoom. */
@Composable
private fun VideoFramePreview(item: PendingMedia) {
    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        AsyncImage(
            model = rememberVideoFrameRequest(item.uri),
            contentDescription = "Video preview",
            contentScale = ContentScale.Fit,
            modifier = Modifier.fillMaxSize(),
            error = rememberVectorPainter(Icons.Default.BrokenImage)
        )
        Box(
            modifier = Modifier
                .size(64.dp)
                .background(color = Color.Black.copy(alpha = 0.45f), shape = CircleShape),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                imageVector = Icons.Default.PlayArrow,
                contentDescription = "Play video",
                tint = Color.White,
                modifier = Modifier.size(36.dp)
            )
        }
    }
}

/**
 * Filmstrip of the batch. Doubles as the removal affordance — dropping a photo
 * you did not mean to pick should not mean cancelling and re-picking all of them.
 */
@Composable
private fun ThumbnailStrip(
    items: List<PendingMedia>,
    selectedIndex: Int,
    onSelect: (Int) -> Unit,
    onRemove: (Int) -> Unit,
) {
    val listState = rememberLazyListState()
    LaunchedEffect(selectedIndex) {
        listState.animateScrollToItem(selectedIndex)
    }
    LazyRow(
        state = listState,
        modifier = Modifier
            .fillMaxWidth()
            .background(Color.Black.copy(alpha = 0.6f))
            .padding(vertical = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        contentPadding = PaddingValues(horizontal = 12.dp)
    ) {
        itemsIndexed(items, key = { _, item -> item.originalUri.toString() }) { index, item ->
            Box {
                val tileModifier = Modifier
                    .size(56.dp)
                    .clip(RoundedCornerShape(6.dp))
                    .border(
                        width = if (index == selectedIndex) 2.dp else 0.dp,
                        color = if (index == selectedIndex) {
                            MaterialTheme.colorScheme.primary
                        } else {
                            Color.Transparent
                        },
                        shape = RoundedCornerShape(6.dp)
                    )
                if (item.isVideo) {
                    AsyncImage(
                        model = rememberVideoFrameRequest(item.uri),
                        contentDescription = null,
                        contentScale = ContentScale.Crop,
                        error = rememberVectorPainter(Icons.Default.BrokenImage),
                        modifier = tileModifier.clickable { onSelect(index) }
                    )
                } else {
                    // SharedMediaTile, not a bare AsyncImage: these are full-size
                    // pre-compression originals, which is exactly the case where
                    // Coil's default BitmapFactory path subsamples to a black bitmap.
                    // The tile wires ScaledImageDecoder and a broken-image fallback.
                    SharedMediaTile(
                        mediaUrl = item.uri.toString(),
                        localUri = null,
                        contentDescription = null,
                        modifier = tileModifier,
                        onClick = { onSelect(index) }
                    )
                }
                Box(
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .padding(2.dp)
                        .size(18.dp)
                        .background(Color.Black.copy(alpha = 0.6f), CircleShape)
                        .clickable { onRemove(index) },
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = Icons.Default.Close,
                        contentDescription = "Remove item ${index + 1}",
                        tint = Color.White,
                        modifier = Modifier.size(12.dp)
                    )
                }
            }
        }
    }
}

/** Which of the editor screens an [EditTarget] is open on. */
private enum class Editor { ADJUST, DRAW }

/**
 * Which item an editor screen is editing, which editor it is, and which of the
 * item's steps it opened on.
 *
 * [key] is the pick's own URI — the one field of `PendingMedia` that never
 * moves — because the item's index can change under the editor when a page is
 * removed from the thumbnail strip, and its `uri` moves every time a step lands.
 * [source] is pinned at open time so the flatten reads the bytes the user is
 * actually looking at, even if the item's cursor moves in between.
 *
 * One type for both editors, rather than one per screen: everything about
 * *which photo* is being edited and what happens to the result on Done is
 * identical, and the differences (a crop frame, a stroke stack) belong to the
 * screens, which save their own.
 */
private data class EditTarget(val key: String, val source: Uri, val editor: Editor) {
    companion object {
        /**
         * Saved, not merely remembered. The editor screens save their own op
         * stacks, crop frames and drawings, and all of that is unreachable if
         * the *screen* closes on rotation — turning the phone mid-crop would
         * throw the crop away and make four tested savers dead code.
         *
         * Null round-trips as an empty list rather than as a null the saver
         * would have to special-case, since `rememberSaveable` treats a null
         * save value as "nothing to restore".
         */
        val Saver: Saver<EditTarget?, Any> = listSaver(
            save = { target ->
                target?.let { listOf(it.key, it.source.toString(), it.editor.name) }.orEmpty()
            },
            restore = { flat ->
                val editor = Editor.entries.firstOrNull { it.name == flat.getOrNull(2) }
                if (flat.size == 3 && editor != null) {
                    EditTarget(flat[0], Uri.parse(flat[1]), editor)
                } else {
                    null
                }
            },
        )
    }
}

/**
 * Flattens the peeked-from cursor map to `[key, cursor]` pairs. Saved rather
 * than merely remembered: a rotation mid-peek that forgot the step would send
 * the user back to the top of the history, which is exactly the re-applied-edits
 * bug the map exists to prevent.
 */
private val CursorsSaver = listSaver<SnapshotStateMap<String, Int>, String>(
    save = { map -> map.entries.flatMap { listOf(it.key, it.value.toString()) } },
    restore = { flat ->
        mutableStateMapOf<String, Int>().apply {
            flat.chunked(2).forEach { pair ->
                if (pair.size == 2) pair[1].toIntOrNull()?.let { put(pair[0], it) }
            }
        }
    }
)

/** Flattens the caption map to `[key, value]` pairs so edits survive rotation. */
private val CaptionsSaver = listSaver<SnapshotStateMap<String, String>, String>(
    save = { map -> map.entries.flatMap { listOf(it.key, it.value) } },
    restore = { flat ->
        mutableStateMapOf<String, String>().apply {
            flat.chunked(2).forEach { pair -> if (pair.size == 2) put(pair[0], pair[1]) }
        }
    }
)
