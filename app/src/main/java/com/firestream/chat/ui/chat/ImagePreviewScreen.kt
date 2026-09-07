package com.firestream.chat.ui.chat

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
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
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
import com.firestream.chat.ui.components.SharedMediaTile
import com.firestream.chat.ui.components.rememberVideoFrameRequest

/**
 * Full-screen review of everything the user just picked, before any of it is
 * sent. One item or twenty — the screen is a pager either way, and the extra
 * chrome (page counter, thumbnail strip, per-item remove) only appears once
 * there is more than one item, so a single pick looks exactly as it always did.
 *
 * Captions are **per item**: the caption box always edits the page you are
 * looking at. They are held in [captions], keyed by URI and read only inside
 * [CaptionBar], so a keystroke invalidates the caption row rather than the pager
 * — otherwise every typed character would re-run the full-screen `AsyncImage`
 * for the current page and its neighbours.
 *
 * The caller gets the final list back through [onSend], captions and removals
 * already applied; [onDismiss] throws the whole batch away.
 */
@Composable
internal fun ImagePreviewScreen(
    items: List<PendingMedia>,
    recentEmojis: List<String>,
    onEmojiUsed: (String) -> Unit,
    onSend: (List<PendingMedia>) -> Unit,
    onDismiss: () -> Unit,
) {
    var drafts by rememberSaveable(items, stateSaver = PendingMedia.ListSaver) {
        mutableStateOf(items)
    }
    val captions = rememberSaveable(items, saver = CaptionsSaver) {
        mutableStateMapOf<String, String>()
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

    var currentPageZoomed by remember { mutableStateOf(false) }
    var showEmojiSheet by rememberSaveable { mutableStateOf(false) }
    val keyboardController = LocalSoftwareKeyboardController.current

    val onRemove: (Int) -> Unit = remember(items) {
        { index ->
            drafts = drafts.toMutableList().also { list ->
                captions.remove(list[index].uri.toString())
                list.removeAt(index)
            }
        }
    }

    BackHandler(enabled = showEmojiSheet) { showEmojiSheet = false }

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
            onClick = onDismiss,
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

        if (isBatch) {
            Text(
                text = "${currentIndex + 1} / ${drafts.size}",
                style = MaterialTheme.typography.labelLarge,
                color = Color.White,
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .windowInsetsPadding(WindowInsets.statusBars)
                    .padding(top = 16.dp)
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
                captionKey = current.uri.toString(),
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
                    onSend(drafts.map { it.copy(caption = captions[it.uri.toString()].orEmpty()) })
                }
            )
        }
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
        itemsIndexed(items, key = { _, item -> item.uri.toString() }) { index, item ->
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

/** Flattens the caption map to `[key, value]` pairs so edits survive rotation. */
private val CaptionsSaver = listSaver<SnapshotStateMap<String, String>, String>(
    save = { map -> map.entries.flatMap { listOf(it.key, it.value) } },
    restore = { flat ->
        mutableStateMapOf<String, String>().apply {
            flat.chunked(2).forEach { pair -> if (pair.size == 2) put(pair[0], pair[1]) }
        }
    }
)
