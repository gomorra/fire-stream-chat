package com.firestream.chat.ui.search

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.BrokenImage
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.Link
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.rememberVectorPainter
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import coil.request.ImageRequest
import com.firestream.chat.data.remote.LinkPreview
import com.firestream.chat.domain.model.Message
import com.firestream.chat.domain.model.MessageFilterType
import com.firestream.chat.domain.model.MessageType
import com.firestream.chat.ui.components.ScaledImageDecoder
import com.firestream.chat.ui.components.SharedMediaTile
import com.firestream.chat.ui.components.rememberVideoFrameRequest
import java.io.File
import com.firestream.chat.domain.util.MessageUrls
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private val resultDateFormat = SimpleDateFormat("MMM d, HH:mm", Locale.getDefault())

/** Sized so a link row is about twice a plain result row — see [SearchLinkRow]. */
private val LINK_THUMBNAIL_SIZE = 64.dp

/**
 * Search results, rendered by what the active chip selected. Shared by the
 * in-chat search pane and the global search screen.
 *
 * Photos and Videos get a thumbnail grid — the whole point of browse mode is
 * that a wall of "sent an image" text rows is useless for finding a picture.
 * Links get the same treatment at row scale: a double-height row carrying the
 * page's preview image and title, because a column of bare URLs is the same
 * unreadable wall — the domain is rarely what the user remembers about a link.
 * Docs get a single-height icon row keyed on the filename they carry. Anything
 * else keeps the text rows search has always had.
 *
 * [linkPreviews] (message id → preview, the pairing `OverlaysState.linkPreviews`
 * also uses) is what a link row renders; [onLinkVisible] is how it asks for one
 * it doesn't have yet. Both default to nothing, so a surface with no preview
 * source still renders correct — just plainer — link rows.
 *
 * Text *and* icon rows are labelled with [resultLabel] — the caller resolves
 * it, because the id→name maps live in the caller's state and this file only
 * renders. In a chat that is the sender's name; globally it also has to say
 * which chat. A link or document row without it is a URL with no answer to
 * "who sent me this, and where?", which is the first thing asked of a
 * cross-chat hit.
 */
@Composable
internal fun SearchResultList(
    results: List<Message>,
    filterType: MessageFilterType?,
    resultLabel: (Message) -> String,
    onResultClick: (Message) -> Unit,
    onMediaClick: (Message) -> Unit,
    modifier: Modifier = Modifier,
    linkPreviews: Map<String, LinkPreview> = emptyMap(),
    onLinkVisible: (Message) -> Unit = {},
) {
    when (filterType) {
        MessageFilterType.PHOTOS, MessageFilterType.VIDEOS -> LazyVerticalGrid(
            columns = GridCells.Fixed(3),
            contentPadding = PaddingValues(2.dp),
            horizontalArrangement = Arrangement.spacedBy(2.dp),
            verticalArrangement = Arrangement.spacedBy(2.dp),
            modifier = modifier.fillMaxSize(),
        ) {
            items(results, key = { "search_${it.id}" }) { message ->
                SearchMediaTile(
                    message = message,
                    onClick = { onMediaClick(message) },
                    modifier = Modifier
                        .fillMaxWidth()
                        .aspectRatio(1f),
                )
            }
        }

        MessageFilterType.LINKS -> LazyColumn(modifier = modifier.fillMaxSize()) {
            items(results, key = { "search_${it.id}" }) { message ->
                // Asked for as the row composes, not when the results land, so
                // the fetching follows the viewport — see SearchLinkPreviewLoader.
                LaunchedEffect(message.id, message.content) { onLinkVisible(message) }
                SearchLinkRow(
                    // The URL itself, not the sentence around it: the URL is
                    // what the user is scanning for when no title resolved.
                    url = MessageUrls.extractUrl(message.content) ?: message.content,
                    preview = linkPreviews[message.id],
                    label = resultLabel(message),
                    timestamp = message.timestamp,
                    onClick = { onResultClick(message) },
                )
                HorizontalDivider()
            }
        }

        MessageFilterType.DOCS -> LazyColumn(modifier = modifier.fillMaxSize()) {
            items(results, key = { "search_${it.id}" }) { message ->
                SearchIconRow(
                    icon = Icons.Default.Description,
                    // A DOCUMENT carries its filename in `content`.
                    primary = message.content.ifBlank { "Document" },
                    label = resultLabel(message),
                    timestamp = message.timestamp,
                    onClick = { onResultClick(message) },
                )
                HorizontalDivider()
            }
        }

        else -> LazyColumn(modifier = modifier.fillMaxSize()) {
            items(results, key = { "search_${it.id}" }) { message ->
                SearchTextRow(
                    message = message,
                    label = resultLabel(message),
                    onClick = { onResultClick(message) },
                )
                HorizontalDivider()
            }
        }
    }
}

/**
 * What the results pane says when it has nothing to list, shared by both search
 * surfaces so the two cannot drift into different words for one condition.
 *
 * Three states, not two: nothing selected yet is a *hint*, because an empty
 * screen reporting a failed search before anything was asked for reads as a
 * broken search. Browse mode has no query to have found nothing *for*, so it
 * says what it actually looked at.
 *
 * Takes the whole pane rather than sitting as a one-line label above the
 * conversation: opening "Shared Media" in a chat with no photos would otherwise
 * look like the menu item did nothing.
 */
@Composable
internal fun SearchEmptyState(
    query: String,
    isSelecting: Boolean,
    modifier: Modifier = Modifier,
    hint: String = "Search your messages",
) {
    Box(
        modifier = modifier
            .fillMaxWidth()
            .padding(16.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = when {
                !isSelecting -> hint
                query.isBlank() -> "Nothing matches these filters"
                else -> "No messages found"
            },
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun SearchTextRow(message: Message, label: String, onClick: () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 10.dp)
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.primary
        )
        Text(
            text = message.content.ifBlank { message.type.placeholderLabel },
            style = MaterialTheme.typography.bodyMedium,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis
        )
        Text(
            text = resultDateFormat.format(Date(message.timestamp)),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

/**
 * A link or document result: the [label] on top, then what the row is keyed on,
 * then the date — the same order [SearchTextRow] uses, so the two result shapes
 * read as one list. A blank label (nothing resolvable to say) drops its line
 * rather than leaving an empty one.
 */
@Composable
private fun SearchIconRow(
    icon: ImageVector,
    primary: String,
    label: String,
    timestamp: Long,
    onClick: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.primary,
            modifier = Modifier.size(24.dp),
        )
        Column(modifier = Modifier.padding(start = 12.dp)) {
            if (label.isNotBlank()) {
                Text(
                    text = label,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.primary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Text(
                text = primary,
                style = MaterialTheme.typography.bodyMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = resultDateFormat.format(Date(timestamp)),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/**
 * A link result, two rows tall so it can carry the page's preview image.
 *
 * The image is the point: a Links browse is a column of near-identical URLs,
 * and users recognise a page they were sent by its thumbnail and headline long
 * before they recognise its host. Until the preview resolves — or when it never
 * does — the thumbnail slot holds the link icon at the same size, so the list
 * doesn't reflow row by row as previews arrive.
 *
 * The URL stays on screen even when a title resolved, demoted to the second
 * line: a title alone can't answer "is this the shop or the review of it?".
 */
@Composable
private fun SearchLinkRow(
    url: String,
    preview: LinkPreview?,
    label: String,
    timestamp: Long,
    onClick: () -> Unit,
) {
    val title = preview?.title?.takeIf { it.isNotBlank() }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier
                .size(LINK_THUMBNAIL_SIZE)
                .clip(RoundedCornerShape(8.dp))
                .background(MaterialTheme.colorScheme.surfaceVariant),
            contentAlignment = Alignment.Center,
        ) {
            if (preview?.imageUrl != null) {
                AsyncImage(
                    model = preview.imageUrl,
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize(),
                )
            } else {
                Icon(
                    imageVector = Icons.Default.Link,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(24.dp),
                )
            }
        }
        Column(modifier = Modifier.padding(start = 12.dp)) {
            if (label.isNotBlank()) {
                Text(
                    text = label,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.primary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Text(
                text = title ?: url,
                style = MaterialTheme.typography.bodyMedium.copy(
                    fontWeight = if (title != null) FontWeight.SemiBold else FontWeight.Normal,
                ),
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            // Only once a title has taken the line above — otherwise this would
            // print the URL twice.
            if (title != null) {
                Text(
                    text = url,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Text(
                text = resultDateFormat.format(Date(timestamp)),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/**
 * One grid tile. Images reuse [SharedMediaTile] so the search grid and the
 * profile grid decode identically; videos need their own request, because
 * handing a local `.mp4` to an image decoder yields a broken-image icon —
 * a local video decodes through coil-video's frame decoder, and only a
 * remote-only video falls back to the JPEG thumbnail uploaded alongside it.
 */
@Composable
private fun SearchMediaTile(
    message: Message,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    if (message.type != MessageType.VIDEO) {
        SharedMediaTile(
            mediaUrl = message.mediaUrl,
            localUri = message.localUri,
            thumbnailUrl = message.mediaThumbnailUrl,
            modifier = modifier,
            onClick = onClick,
        )
        return
    }

    val context = LocalContext.current
    val localFile = remember(message.localUri) {
        message.localUri?.let { File(it) }?.takeIf { it.exists() && it.isFile && it.canRead() }
    }
    val model: Any? = if (localFile != null) {
        rememberVideoFrameRequest(localFile)
    } else {
        message.mediaThumbnailUrl?.let { url ->
            remember(url) {
                ImageRequest.Builder(context)
                    .data(url)
                    .decoderFactory(ScaledImageDecoder.Factory())
                    .crossfade(true)
                    .build()
            }
        }
    }

    Box(modifier = modifier.clickable(onClick = onClick)) {
        AsyncImage(
            model = model,
            contentDescription = null,
            contentScale = ContentScale.Crop,
            error = rememberVectorPainter(Icons.Default.BrokenImage),
            modifier = Modifier
                .fillMaxSize()
                .background(MaterialTheme.colorScheme.surfaceVariant),
        )
        Icon(
            imageVector = Icons.Default.PlayArrow,
            contentDescription = "Play video",
            tint = Color.White,
            modifier = Modifier
                .align(Alignment.Center)
                .background(Color.Black.copy(alpha = 0.45f), CircleShape)
                .padding(4.dp)
                .size(20.dp),
        )
    }
}

/** What a bubble with no text says it is, so a media row isn't a blank line. */
private val MessageType.placeholderLabel: String
    get() = when (this) {
        MessageType.IMAGE -> "Photo"
        MessageType.VIDEO -> "Video"
        MessageType.VOICE -> "Voice message"
        MessageType.DOCUMENT -> "Document"
        MessageType.LOCATION -> "Location"
        MessageType.POLL -> "Poll"
        MessageType.CALL -> "Call"
        MessageType.LIST -> "List"
        MessageType.TIMER -> "Timer"
        MessageType.TEXT -> ""
    }
