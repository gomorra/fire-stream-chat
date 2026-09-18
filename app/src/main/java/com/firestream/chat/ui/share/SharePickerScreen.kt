package com.firestream.chat.ui.share

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withLink
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import coil.compose.AsyncImage
import com.firestream.chat.data.remote.LinkPreview
import com.firestream.chat.domain.model.SharedContent
import com.firestream.chat.ui.chat.FullscreenImageViewer
import com.firestream.chat.ui.components.ChatPickerCallbacks
import com.firestream.chat.ui.components.ChatPickerPanel
import com.firestream.chat.ui.components.ChatPickerState

@Composable
fun SharePickerScreen(
    onDone: (chatId: String?, recipientId: String?) -> Unit,
    onBackClick: () -> Unit,
    viewModel: SharePickerViewModel = hiltViewModel()
) {
    val uiState by viewModel.uiState.collectAsState()
    val snackbarHostState = remember { SnackbarHostState() }
    var fullscreenImageUrl by rememberSaveable { mutableStateOf<String?>(null) }

    LaunchedEffect(uiState.error) {
        uiState.error?.let {
            snackbarHostState.showSnackbar(it.message, duration = SnackbarDuration.Short)
            viewModel.clearError()
        }
    }

    ChatPickerPanel(
        state = ChatPickerState(
            title = "Share to\u2026",
            chats = uiState.chats,
            currentUserId = uiState.currentUserId,
            participants = uiState.participantProfiles,
            selectedChatIds = uiState.selectedChatIds,
            searchQuery = uiState.searchQuery,
            isSending = uiState.isSending,
        ),
        callbacks = ChatPickerCallbacks(
            onBack = onBackClick,
            onSearchQueryChange = viewModel::onSearchQueryChange,
            onToggleChat = viewModel::toggleChatSelection,
            onSend = { viewModel.send(onDone) },
        ),
        snackbarHost = { SnackbarHost(snackbarHostState) },
    ) {
        // Fills all the space above the chat picker.
        ContentPreview(
            state = uiState.previewState,
            content = uiState.sharedContent,
            linkPreview = uiState.linkPreview,
            errorMessage = uiState.error?.message,
            onImageClick = { url -> fullscreenImageUrl = url }
        )
    }

    // Fullscreen image overlay
    fullscreenImageUrl?.let { url ->
        FullscreenImageViewer(imageUrl = url, onDismiss = { fullscreenImageUrl = null })
    }
}

/**
 * What is about to be shared, filling the space the chat picker leaves above it.
 */
@Composable
private fun ContentPreview(
    state: PreviewState,
    content: SharedContent?,
    linkPreview: LinkPreview?,
    errorMessage: String?,
    onImageClick: (String) -> Unit
) {
    when (state) {
        PreviewState.Loading -> {
            CircularProgressIndicator(
                modifier = Modifier.size(32.dp),
                strokeWidth = 3.dp
            )
        }
        PreviewState.Ready -> when (content) {
            is SharedContent.Text -> TextPreview(
                text = content.text,
                linkPreview = linkPreview,
                onImageClick = onImageClick
            )
            is SharedContent.Media -> when (content.items.size) {
                1 -> SingleMediaPreview(content.items[0], onImageClick)
                else -> MultiMediaPreview(content.items)
            }
            null -> Unit
        }
        PreviewState.Empty -> {
            Text(
                text = "Nothing to share",
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        PreviewState.Error -> {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                modifier = Modifier.padding(16.dp)
            ) {
                Icon(
                    imageVector = Icons.Default.ErrorOutline,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.error,
                    modifier = Modifier.size(40.dp)
                )
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = errorMessage ?: "Couldn't read shared content",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

@Composable
private fun TextPreview(
    text: String,
    linkPreview: LinkPreview?,
    onImageClick: (String) -> Unit
) {
    val linkColor = MaterialTheme.colorScheme.primary
    val urlInText = linkPreview?.url
    val annotated = remember(text, urlInText, linkColor) {
        buildAnnotatedString {
            if (urlInText != null) {
                val idx = text.indexOf(urlInText)
                if (idx >= 0) {
                    append(text.substring(0, idx))
                    withLink(LinkAnnotation.Url(
                        url = urlInText,
                        styles = TextLinkStyles(SpanStyle(
                            color = linkColor,
                            textDecoration = TextDecoration.Underline
                        ))
                    )) {
                        append(urlInText)
                    }
                    append(text.substring(idx + urlInText.length))
                } else {
                    append(text)
                }
            } else {
                append(text)
            }
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp)
    ) {
        Text(
            text = annotated,
            style = MaterialTheme.typography.bodyLarge.copy(
                color = MaterialTheme.colorScheme.onSurface
            )
        )

        if (linkPreview != null) {
            Spacer(modifier = Modifier.height(12.dp))
            LinkPreviewSection(
                preview = linkPreview,
                onImageClick = onImageClick
            )
        }
    }
}

@Composable
private fun LinkPreviewSection(
    preview: LinkPreview,
    onImageClick: (String) -> Unit
) {
    val linkColor = MaterialTheme.colorScheme.primary

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(
                color = MaterialTheme.colorScheme.surfaceVariant,
                shape = RoundedCornerShape(12.dp)
            )
            .clip(RoundedCornerShape(12.dp))
    ) {
        // OG image — full width, tappable for fullscreen
        if (preview.imageUrl != null) {
            AsyncImage(
                model = preview.imageUrl,
                contentDescription = "Link preview image",
                contentScale = ContentScale.Crop,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(180.dp)
                    .clickable { onImageClick(preview.imageUrl) }
            )
        }

        Column(modifier = Modifier.padding(12.dp)) {
            if (preview.title != null) {
                Text(
                    text = preview.title,
                    style = MaterialTheme.typography.titleSmall,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
                Spacer(modifier = Modifier.height(2.dp))
            }
            if (preview.description != null) {
                Text(
                    text = preview.description,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
                Spacer(modifier = Modifier.height(4.dp))
            }
            // Clickable URL — opens browser via LinkAnnotation
            val urlAnnotated = remember(preview.url, linkColor) {
                buildAnnotatedString {
                    withLink(LinkAnnotation.Url(
                        url = preview.url,
                        styles = TextLinkStyles(SpanStyle(
                            color = linkColor,
                            textDecoration = TextDecoration.Underline
                        ))
                    )) {
                        append(preview.url)
                    }
                }
            }
            Text(
                text = urlAnnotated,
                style = MaterialTheme.typography.labelSmall,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}

@Composable
private fun SingleMediaPreview(
    item: SharedContent.Media.MediaItem,
    onImageClick: (String) -> Unit
) {
    if (item.mimeType.startsWith("image") || item.mimeType.startsWith("video")) {
        AsyncImage(
            model = item.cachedUri,
            contentDescription = item.fileName,
            contentScale = ContentScale.Fit,
            modifier = Modifier
                .fillMaxSize()
                .clickable { onImageClick(item.cachedUri) }
        )
    } else {
        // Document — show icon + filename centered
        Box(
            modifier = Modifier.fillMaxSize(),
            contentAlignment = Alignment.Center
        ) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Icon(
                    imageVector = Icons.Default.Description,
                    contentDescription = null,
                    modifier = Modifier.size(64.dp),
                    tint = MaterialTheme.colorScheme.primary
                )
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = item.fileName,
                    style = MaterialTheme.typography.bodyMedium,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
    }
}

@Composable
private fun MultiMediaPreview(items: List<SharedContent.Media.MediaItem>) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(top = 8.dp)
    ) {
        Text(
            text = "${items.size} items",
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp)
        )
        LazyRow(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(4.dp),
            contentPadding = PaddingValues(horizontal = 16.dp)
        ) {
            items(items, key = { it.cachedUri }) { item ->
                AsyncImage(
                    model = item.cachedUri,
                    contentDescription = item.fileName,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier
                        .size(120.dp)
                        .aspectRatio(1f)
                        .clip(RoundedCornerShape(8.dp))
                )
            }
        }
    }
}
