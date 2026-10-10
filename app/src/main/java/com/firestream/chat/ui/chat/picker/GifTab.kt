package com.firestream.chat.ui.chat.picker

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.staggeredgrid.LazyVerticalStaggeredGrid
import androidx.compose.foundation.lazy.staggeredgrid.StaggeredGridCells
import androidx.compose.foundation.lazy.staggeredgrid.StaggeredGridItemSpan
import androidx.compose.foundation.lazy.staggeredgrid.items
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import com.firestream.chat.domain.model.OnlineMedia
import com.firestream.chat.domain.model.OnlineMediaKind
import com.firestream.chat.ui.chat.gif.OnlineMediaFeed
import com.firestream.chat.ui.chat.gif.OnlineMediaUiState
import com.firestream.chat.ui.components.StickerImage
import com.firestream.chat.ui.components.rememberAnimatedImageRequest

/** What the online parts of the picker hand back. One bundle, to stay under the parameter ceiling (docs/GOTCHAS.md). */
@Immutable
internal data class OnlineMediaCallbacks(
    /** A tab is on screen and shows this query, or what is trending when it is blank. */
    val onQuery: (OnlineMediaKind, String) -> Unit = { _, _ -> },
    val onLoadMore: (OnlineMediaKind) -> Unit = {},
    val onRetry: (OnlineMediaKind) -> Unit = {},
    val onAcceptNotice: () -> Unit = {},
    val onPick: (OnlineMedia) -> Unit = {},
)

/** The test tag of an online item's cell, in the GIFs tab and in the online stickers. [key] is `OnlineMedia.key`. */
internal fun onlineMediaTag(key: String) = "online:$key"

/**
 * GIFs from KLIPY as a picker tab: what is trending, or the results for
 * [query]. A tap sends the GIF.
 *
 * The grid keeps the order KLIPY returned. Previews load straight from KLIPY
 * and play, which is what a GIF is picked by.
 *
 * Until the first-use notice is accepted the tab shows the notice and requests
 * nothing.
 */
@Composable
internal fun GifTab(
    query: String,
    online: OnlineMediaUiState,
    callbacks: OnlineMediaCallbacks,
    modifier: Modifier = Modifier,
) {
    LaunchedEffect(query) { callbacks.onQuery(OnlineMediaKind.GIF, query) }
    val feed = online.gifs

    Column(modifier = modifier.fillMaxSize()) {
        Box(modifier = Modifier.weight(1f).fillMaxWidth()) {
            when (online.noticeAccepted) {
                // The stored answer is not read yet. Neither the notice nor a grid is right.
                null -> Unit
                false -> OnlineMediaNotice(onAccept = callbacks.onAcceptNotice, modifier = Modifier.align(Alignment.Center))
                true -> LazyVerticalStaggeredGrid(
                    columns = StaggeredGridCells.Fixed(2),
                    contentPadding = PaddingValues(8.dp),
                    verticalItemSpacing = 6.dp,
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    modifier = Modifier.fillMaxSize(),
                ) {
                    items(feed.items, key = { it.key }) { media ->
                        GifCell(media = media, onPick = callbacks.onPick)
                    }
                    item(key = "footer", span = StaggeredGridItemSpan.FullLine) {
                        OnlineMediaFooter(kind = OnlineMediaKind.GIF, feed = feed, callbacks = callbacks)
                    }
                }
            }
        }
        PoweredByKlipy(modifier = Modifier.align(Alignment.End).padding(horizontal = 12.dp, vertical = 2.dp))
    }
}

@Composable
private fun GifCell(media: OnlineMedia, onPick: (OnlineMedia) -> Unit) {
    val preview = media.preview
    // The shape is known before the file loads, so the grid does not jump.
    val ratio = if (preview.width > 0 && preview.height > 0) preview.width.toFloat() / preview.height else 1f
    AsyncImage(
        model = rememberAnimatedImageRequest(preview.url),
        contentDescription = media.title.ifBlank { "GIF" },
        contentScale = ContentScale.Crop,
        modifier = Modifier
            .fillMaxWidth()
            .aspectRatio(ratio.coerceIn(0.4f, 2.5f))
            .clip(MaterialTheme.shapes.small)
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .clickable(onClickLabel = "Send GIF") { onPick(media) }
            .testTag(onlineMediaTag(media.key)),
    )
}

/**
 * One online sticker in a grid: its first frame, from KLIPY. A tap sends it.
 * There is no long press, because an online sticker cannot be kept.
 */
@Composable
internal fun OnlineStickerCell(media: OnlineMedia, onPick: (OnlineMedia) -> Unit, modifier: Modifier = Modifier) {
    Box(
        modifier = modifier
            .aspectRatio(1f)
            .padding(4.dp)
            .clip(MaterialTheme.shapes.medium)
            .clickable(onClickLabel = "Send sticker") { onPick(media) }
            .testTag(onlineMediaTag(media.key)),
        contentAlignment = Alignment.Center,
    ) {
        StickerImage(
            model = media.preview.url,
            animated = false,
            contentDescription = media.title.ifBlank { "Sticker" },
            modifier = Modifier.fillMaxSize().padding(4.dp),
        )
    }
}

/**
 * The end of an online grid: the spinner while a page loads, the error with a
 * retry, or a line saying that nothing was found. While it is on screen and
 * another page exists, it asks for that page.
 */
@Composable
internal fun OnlineMediaFooter(
    kind: OnlineMediaKind,
    feed: OnlineMediaFeed,
    callbacks: OnlineMediaCallbacks,
    modifier: Modifier = Modifier,
) {
    val error = feed.error
    if (feed.hasNext && !feed.isLoading && error == null) {
        // Runs each time the footer is on screen with nothing loading: after every page
        // while the grid is short, and when the end is scrolled into view. The feed says
        // `hasNext` only while pages still bring something new, so this ends.
        LaunchedEffect(feed.query, feed.page) { callbacks.onLoadMore(kind) }
    }
    Column(
        modifier = modifier.fillMaxWidth().padding(8.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        when {
            feed.isLoading -> CircularProgressIndicator(strokeWidth = 2.dp, modifier = Modifier.size(24.dp))
            error != null -> {
                Text(
                    text = error.message,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                )
                TextButton(onClick = { callbacks.onRetry(kind) }) { Text("Try again") }
            }
            feed.items.isEmpty() && feed.page > 0 -> Text(
                text = if (feed.query.isEmpty()) "Nothing here right now" else "Nothing found for “${feed.query}”",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/**
 * The one-time notice before the first request to KLIPY. It says who learns
 * what, and nothing is requested until [onAccept].
 */
@Composable
internal fun OnlineMediaNotice(onAccept: () -> Unit, modifier: Modifier = Modifier) {
    Column(
        modifier = modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 12.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            text = "GIFs and online stickers come from KLIPY",
            style = MaterialTheme.typography.titleSmall,
            textAlign = TextAlign.Center,
        )
        Text(
            text = "What you search for here goes to KLIPY. The people you send a pick to load it from KLIPY, " +
                "which sees their IP address and yours.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(top = 6.dp),
        )
        Button(onClick = onAccept, modifier = Modifier.padding(top = 10.dp)) {
            Text("Continue")
        }
    }
}

/** KLIPY's attribution mark. It sits wherever KLIPY's results are shown. */
@Composable
internal fun PoweredByKlipy(modifier: Modifier = Modifier) {
    Text(
        text = "Powered by KLIPY",
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = modifier,
    )
}
