package com.firestream.chat.ui.chat

import android.content.Context
import android.util.Log
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.BrokenImage
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Snackbar
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import coil.compose.SubcomposeAsyncImage
import coil.memory.MemoryCache
import coil.request.ImageRequest
import java.io.File

// Saver for the (remote url, local path) pair host screens keep in
// rememberSaveable, so the viewer survives activity recreation (rotation)
// instead of snapping back to the underlying screen.
internal val FullscreenImageArgsSaver = listSaver<Pair<String?, String?>?, String?>(
    save = { it?.toList() ?: emptyList() },
    restore = { if (it.isEmpty()) null else it[0] to it[1] },
)

// A single fullscreen media entry (remote url + optional local path), used by
// the swipeable gallery pager below. [messageId] is the message the image was
// sent in, carried so a host screen showing an in-chat gallery can scroll back
// to the message the user swiped to; screens with no message context (the
// Shared Media grid, avatars) leave it null.
internal data class FullscreenMediaItem(
    val imageUrl: String?,
    val localUri: String? = null,
    val messageId: String? = null,
)

@Composable
internal fun FullscreenImageViewer(
    imageUrl: String?,
    localUri: String? = null,
    onDismiss: () -> Unit,
    onSaveToDownloads: (() -> Unit)? = null,
    snackbarHostState: SnackbarHostState? = null,
    onEdit: (() -> Unit)? = null,
) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black),
        contentAlignment = Alignment.Center,
    ) {
        ZoomableImage(
            imageUrl = imageUrl,
            localUri = localUri,
            isActive = true,
            onTap = onDismiss,
            onZoomChange = {},
        )
        FullscreenOverlayControls(
            onDismiss = onDismiss,
            onSaveToDownloads = onSaveToDownloads,
            onEdit = onEdit,
            snackbarHostState = snackbarHostState,
        )
    }
}

/**
 * Swipeable fullscreen gallery: shows [items] in a [HorizontalPager] starting at
 * [initialIndex], so the user can swipe left/right through shared media.
 *
 * Gesture reconciliation: [ZoomableImage] owns single-finger pan while zoomed in,
 * which competes with the pager's horizontal-drag paging. We gate the pager's
 * `userScrollEnabled` on whether the current page is zoomed — at 1x the pager
 * swipes freely; once zoomed (scale > 1f) paging is disabled so the image pans.
 * Pages reset their zoom when scrolled out of view.
 *
 * [onPageChanged] reports the settled page so a host can follow along (the chat
 * screen uses it to scroll to the swiped-to message on close). [onSaveToDownloads]
 * and [onEdit] are handed the item currently on screen, not a fixed one.
 */
@Composable
internal fun FullscreenImagePager(
    items: List<FullscreenMediaItem>,
    initialIndex: Int,
    onDismiss: () -> Unit,
    snackbarHostState: SnackbarHostState? = null,
    onPageChanged: ((Int) -> Unit)? = null,
    onSaveToDownloads: ((FullscreenMediaItem) -> Unit)? = null,
    onEdit: ((FullscreenMediaItem) -> Unit)? = null,
) {
    if (items.isEmpty()) {
        LaunchedEffect(Unit) { onDismiss() }
        return
    }
    val pagerState = rememberPagerState(
        initialPage = initialIndex.coerceIn(0, items.lastIndex),
    ) { items.size }
    var currentPageZoomed by remember { mutableStateOf(false) }

    // Fires once on open too (with the initial page), so the host never has to
    // seed the index itself. rememberUpdatedState keeps the effect from holding
    // a stale lambda when the host recomposes with a new one.
    val currentOnPageChanged by rememberUpdatedState(onPageChanged)
    LaunchedEffect(pagerState) {
        snapshotFlow { pagerState.currentPage }
            .collect { page -> currentOnPageChanged?.invoke(page) }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black),
        contentAlignment = Alignment.Center,
    ) {
        HorizontalPager(
            state = pagerState,
            userScrollEnabled = !currentPageZoomed,
            beyondViewportPageCount = 1,
            modifier = Modifier.fillMaxSize(),
        ) { page ->
            val isActive = page == pagerState.currentPage
            ZoomableImage(
                imageUrl = items[page].imageUrl,
                localUri = items[page].localUri,
                isActive = isActive,
                onTap = onDismiss,
                onZoomChange = { zoomed -> if (isActive) currentPageZoomed = zoomed },
            )
        }
        FullscreenOverlayControls(
            onDismiss = onDismiss,
            onSaveToDownloads = onSaveToDownloads?.let { save ->
                { items.getOrNull(pagerState.currentPage)?.let(save) }
            },
            onEdit = onEdit?.let { edit ->
                { items.getOrNull(pagerState.currentPage)?.let(edit) }
            },
            snackbarHostState = snackbarHostState,
        )
    }
}

/**
 * The zoomable/pannable image surface for one pager page. Zoom/pan lives in the
 * shared [ZoomableBox]; this only resolves the Coil request and renders it.
 * [onTap] fires on a single tap at 1x.
 */
@Composable
private fun ZoomableImage(
    imageUrl: String?,
    localUri: String?,
    isActive: Boolean,
    onTap: () -> Unit,
    onZoomChange: (Boolean) -> Unit,
) {
    val request = rememberFullscreenImageRequest(imageUrl, localUri)

    ZoomableBox(
        isActive = isActive,
        onZoomChange = onZoomChange,
        onTap = onTap,
    ) { transform ->
        if (request != null) {
            SubcomposeAsyncImage(
                model = request,
                contentDescription = "Full screen image",
                contentScale = ContentScale.Fit,
                modifier = Modifier
                    .fillMaxSize()
                    .then(transform),
                loading = {
                    Box(
                        modifier = Modifier.fillMaxSize(),
                        contentAlignment = Alignment.Center,
                    ) {
                        CircularProgressIndicator(color = Color.White)
                    }
                },
                error = { ErrorState(label = "Failed to load") },
            )
        } else {
            ErrorState(label = "No image data")
        }
    }
}

/**
 * Resolves the Coil [ImageRequest] for a fullscreen image, preferring a readable
 * local file over the remote URL. The check is synchronous so we never hand Coil
 * the remote URL during a transient "don't know yet" window — that race made
 * cold-restart taps always start a network load before swapping to the local
 * file.
 */
@Composable
private fun rememberFullscreenImageRequest(imageUrl: String?, localUri: String?): ImageRequest? {
    val imageModel = remember(imageUrl, localUri) {
        fullscreenImageModel(imageUrl, localUri).also {
            if (it == null) Log.w("FullscreenImageViewer", "No model — localUri=$localUri, imageUrl=$imageUrl")
        }
    }
    val context = LocalContext.current
    return remember(imageModel) { imageModel?.let { fullscreenImageRequest(context, it) } }
}

/**
 * What a fullscreen viewer shows for a photo: its local file when that is
 * readable, else the remote URL, else nothing. One function rather than the
 * choice inlined, because [ChatViewModel.editFromViewer] has to name the bitmap
 * the viewer is showing (see [fullscreenImageCacheKey]) and a second copy of
 * the rule would drift from this one. canRead() catches MediaStore files
 * written by a previous install of this app — they exist but EACCES on open.
 */
internal fun fullscreenImageModel(imageUrl: String?, localUri: String?): Any? {
    val localFile = localUri?.let(::File)?.takeIf { it.isFile && it.canRead() }
    return localFile ?: imageUrl?.takeIf { it.isNotBlank() }
}

/**
 * The memory-cache key a fullscreen viewer files [model]'s bitmap under.
 * Explicit rather than Coil's default, so the send preview opened from a viewer
 * can ask for that very bitmap as its placeholder ([previewImageRequest]): the
 * preview shows a *copy* of the file, which under default keys shares nothing
 * with what the viewer has just drawn, and its first frame was black until the
 * copy had decoded. A file's key keeps the modification time Coil's own key
 * carries, so a file overwritten in place is never served from a stale entry.
 */
internal fun fullscreenImageCacheKey(model: Any): String =
    if (model is File) "fullscreen:${model.path}:${model.lastModified()}" else "fullscreen:$model"

internal fun fullscreenImageRequest(context: Context, model: Any): ImageRequest =
    ImageRequest.Builder(context)
        .data(model)
        .memoryCacheKey(MemoryCache.Key(fullscreenImageCacheKey(model)))
        .crossfade(true)
        .listener(
            onError = { req, result ->
                Log.w("FullscreenImageViewer", "Load failed for ${req.data}", result.throwable)
            },
        )
        .build()

/**
 * Top-right controls and optional snackbar shared by both viewers.
 *
 * Edit, Save and Close sit side by side, always visible. Each action is opt-in
 * per host through its nullable lambda: a null hides the button rather than
 * greying it, so the avatar viewers and the share preview show only Close, and
 * a link-preview thumbnail gets neither Save nor Edit.
 *
 * A chevron-folded tray (Close on screen, the rest behind a `<`) shipped in
 * `5406ef3c` and was reversed the same day at the user's direction: hiding the
 * actions cost more than the tidier frame bought.
 */
@Composable
private fun BoxScope.FullscreenOverlayControls(
    onDismiss: () -> Unit,
    onSaveToDownloads: (() -> Unit)? = null,
    onEdit: (() -> Unit)? = null,
    snackbarHostState: SnackbarHostState? = null,
) {
    Row(
        modifier = Modifier
            .align(Alignment.TopEnd)
            .windowInsetsPadding(WindowInsets.statusBars)
            // 6 dp plus each button's own 6 dp inset keeps the circles where the
            // 12 dp padding used to put them.
            .padding(6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (onEdit != null) {
            OverlayControlButton(Icons.Default.Edit, "Edit", onEdit)
        }
        if (onSaveToDownloads != null) {
            OverlayControlButton(Icons.Default.Download, "Save to Downloads", onSaveToDownloads)
        }
        OverlayControlButton(Icons.Default.Close, "Close", onDismiss)
    }
    if (snackbarHostState != null) {
        SnackbarHost(
            hostState = snackbarHostState,
            modifier = Modifier.align(Alignment.BottomCenter),
            snackbar = { data ->
                Snackbar(
                    snackbarData = data,
                    containerColor = Color(0xFF323232),
                    contentColor = Color.White,
                )
            }
        )
    }
}

/**
 * A 36 dp translucent circle, drawn inside a 48 dp touch target so the smaller
 * visual still gets a finger-sized hit area.
 */
@Composable
private fun OverlayControlButton(
    icon: ImageVector,
    contentDescription: String,
    onClick: () -> Unit,
) {
    Box(
        modifier = Modifier
            .size(48.dp)
            .clip(CircleShape)
            .clickable(onClick = onClick, role = Role.Button),
        contentAlignment = Alignment.Center,
    ) {
        Box(
            modifier = Modifier
                .size(36.dp)
                .background(color = Color.Black.copy(alpha = 0.5f), shape = CircleShape),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector = icon,
                contentDescription = contentDescription,
                tint = Color.White,
                modifier = Modifier.size(20.dp),
            )
        }
    }
}

@Composable
private fun ErrorState(label: String) {
    Column(
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Icon(
            imageVector = Icons.Default.BrokenImage,
            contentDescription = label,
            tint = Color.White,
            modifier = Modifier.size(64.dp),
        )
        Spacer(modifier = Modifier.height(8.dp))
        Text(
            text = label,
            color = Color.White,
            style = MaterialTheme.typography.bodySmall,
        )
    }
}
