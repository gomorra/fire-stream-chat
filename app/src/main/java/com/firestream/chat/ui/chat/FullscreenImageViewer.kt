package com.firestream.chat.ui.chat

import android.util.Log
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.BrokenImage
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Download
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
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import coil.compose.SubcomposeAsyncImage
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
// the swipeable gallery pager below.
internal data class FullscreenMediaItem(
    val imageUrl: String?,
    val localUri: String? = null,
)

@Composable
internal fun FullscreenImageViewer(
    imageUrl: String?,
    localUri: String? = null,
    onDismiss: () -> Unit,
    onSaveToDownloads: (() -> Unit)? = null,
    snackbarHostState: SnackbarHostState? = null,
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
 */
@Composable
internal fun FullscreenImagePager(
    items: List<FullscreenMediaItem>,
    initialIndex: Int,
    onDismiss: () -> Unit,
    snackbarHostState: SnackbarHostState? = null,
) {
    if (items.isEmpty()) {
        LaunchedEffect(Unit) { onDismiss() }
        return
    }
    val pagerState = rememberPagerState(
        initialPage = initialIndex.coerceIn(0, items.lastIndex),
    ) { items.size }
    var currentPageZoomed by remember { mutableStateOf(false) }

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
            snackbarHostState = snackbarHostState,
        )
    }
}

/**
 * The zoomable/pannable image surface. Owns its own [scale]/[offset] and reports
 * whether it is zoomed via [onZoomChange]. When [isActive] flips to false (the
 * page scrolled out of view in a pager) it resets its zoom so it isn't left
 * zoomed the next time it scrolls back in. [onTap] fires on a single tap at 1x.
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

    var scale by remember { mutableFloatStateOf(1f) }
    var offset by remember { mutableStateOf(Offset.Zero) }

    // Reset zoom when this page scrolls out of view so it isn't left zoomed.
    LaunchedEffect(isActive) {
        if (!isActive) {
            scale = 1f
            offset = Offset.Zero
            onZoomChange(false)
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .pointerInput(Unit) {
                detectTapGestures(
                    onTap = { if (scale == 1f) onTap() },
                    onDoubleTap = { tapPos ->
                        val targetScale = when {
                            scale >= 6f -> 1f
                            scale >= 2f -> 6f
                            else -> 3f
                        }
                        if (targetScale == 1f) {
                            scale = 1f
                            offset = Offset.Zero
                        } else {
                            // graphicsLayer pivots on the composable center, so to keep the
                            // tapped content point under the finger we solve for newOffset in:
                            //   tap = center + (content - center) * newScale + newOffset
                            // where content = center + (tap - center - offset) / scale.
                            val center = Offset(size.width / 2f, size.height / 2f)
                            offset = tapPos - center - (tapPos - center - offset) * (targetScale / scale)
                            scale = targetScale
                        }
                        onZoomChange(scale > 1f)
                    }
                )
            }
            .pointerInput(Unit) {
                detectTransformGestures { centroid, pan, zoom, _ ->
                    val newScale = (scale * zoom).coerceIn(1f, 10f)
                    if (newScale > 1f) {
                        // Keep the content point under the centroid fixed:
                        // translate so centroid maps to the same content point
                        // at the new scale.
                        val center = Offset(size.width / 2f, size.height / 2f)
                        val newOffset = centroid - center -
                            (centroid - center - offset) * (newScale / scale) + pan
                        offset = newOffset
                    } else {
                        offset = Offset.Zero
                    }
                    scale = newScale
                    onZoomChange(scale > 1f)
                }
            },
        contentAlignment = Alignment.Center
    ) {
        if (request != null) {
            SubcomposeAsyncImage(
                model = request,
                contentDescription = "Full screen image",
                contentScale = ContentScale.Fit,
                modifier = Modifier
                    .fillMaxSize()
                    .graphicsLayer(
                        scaleX = scale,
                        scaleY = scale,
                        translationX = offset.x,
                        translationY = offset.y
                    ),
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
 * file. canRead() catches MediaStore files written by a previous install of this
 * app — they exist but EACCES on direct open.
 */
@Composable
private fun rememberFullscreenImageRequest(imageUrl: String?, localUri: String?): ImageRequest? {
    val localFile = remember(localUri) {
        localUri?.let { File(it) }?.takeIf { it.exists() && it.isFile && it.canRead() }
    }
    val imageModel: Any? = when {
        localFile != null -> localFile
        !imageUrl.isNullOrBlank() -> imageUrl
        else -> {
            Log.w(
                "FullscreenImageViewer",
                "No model — localUri=$localUri, imageUrl=$imageUrl",
            )
            null
        }
    }
    val context = LocalContext.current
    return remember(imageModel) {
        imageModel?.let { model ->
            ImageRequest.Builder(context)
                .data(model)
                .crossfade(true)
                .listener(
                    onError = { req, result ->
                        Log.w(
                            "FullscreenImageViewer",
                            "Load failed for ${req.data}",
                            result.throwable,
                        )
                    },
                )
                .build()
        }
    }
}

/** Top-right Save/Close controls and optional snackbar shared by both viewers. */
@Composable
private fun BoxScope.FullscreenOverlayControls(
    onDismiss: () -> Unit,
    onSaveToDownloads: (() -> Unit)? = null,
    snackbarHostState: SnackbarHostState? = null,
) {
    Row(
        modifier = Modifier
            .align(Alignment.TopEnd)
            .windowInsetsPadding(WindowInsets.statusBars)
            .padding(12.dp)
    ) {
        if (onSaveToDownloads != null) {
            Box(
                modifier = Modifier
                    .size(36.dp)
                    .background(color = Color.Black.copy(alpha = 0.5f), shape = CircleShape)
                    .clickable(onClick = onSaveToDownloads),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = Icons.Default.Download,
                    contentDescription = "Save to Downloads",
                    tint = Color.White,
                    modifier = Modifier.size(20.dp)
                )
            }
            Spacer(modifier = Modifier.width(8.dp))
        }
        Box(
            modifier = Modifier
                .size(36.dp)
                .background(color = Color.Black.copy(alpha = 0.5f), shape = CircleShape)
                .clickable(onClick = onDismiss),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                imageVector = Icons.Default.Close,
                contentDescription = "Close",
                tint = Color.White,
                modifier = Modifier.size(20.dp)
            )
        }
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
