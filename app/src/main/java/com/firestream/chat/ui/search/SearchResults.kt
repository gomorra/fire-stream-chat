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
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.rememberVectorPainter
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import coil.request.ImageRequest
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

/**
 * Search results, rendered by what the active chip selected. Shared by the
 * in-chat search pane and the global search screen.
 *
 * Photos and Videos get a thumbnail grid — the whole point of browse mode is
 * that a wall of "sent an image" text rows is useless for finding a picture.
 * Docs and Links get icon rows keyed on the filename / URL they carry. Anything
 * else keeps the text rows search has always had.
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

        MessageFilterType.DOCS, MessageFilterType.LINKS -> LazyColumn(modifier = modifier.fillMaxSize()) {
            items(results, key = { "search_${it.id}" }) { message ->
                SearchIconRow(
                    icon = if (filterType == MessageFilterType.DOCS) {
                        Icons.Default.Description
                    } else {
                        Icons.Default.Link
                    },
                    // A DOCUMENT carries its filename in `content`; a link row
                    // shows the URL itself rather than the sentence around it,
                    // because the URL is what the user is scanning for.
                    primary = if (filterType == MessageFilterType.DOCS) {
                        message.content.ifBlank { "Document" }
                    } else {
                        MessageUrls.extractUrl(message.content) ?: message.content
                    },
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
