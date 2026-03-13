package com.firestream.chat.ui.share

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Campaign
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.Group
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
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
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import coil.compose.AsyncImage
import com.firestream.chat.data.remote.LinkPreview
import com.firestream.chat.domain.model.Chat
import com.firestream.chat.domain.model.ChatType
import com.firestream.chat.domain.model.SharedContent
import com.firestream.chat.domain.model.User
import com.firestream.chat.ui.chat.FullscreenImageViewer
import com.firestream.chat.ui.components.UserAvatar

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SharePickerScreen(
    onDone: (chatId: String?, recipientId: String?) -> Unit,
    onBackClick: () -> Unit,
    viewModel: SharePickerViewModel = hiltViewModel()
) {
    val uiState by viewModel.uiState.collectAsState()
    val snackbarHostState = remember { SnackbarHostState() }
    var fullscreenImageUrl by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(uiState.error) {
        uiState.error?.let {
            snackbarHostState.showSnackbar(it)
            viewModel.clearError()
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text("Share to…")
                        if (uiState.selectedChatIds.isNotEmpty()) {
                            Text(
                                text = "${uiState.selectedChatIds.size} chat(s) selected",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onPrimary.copy(alpha = 0.7f)
                            )
                        }
                    }
                },
                navigationIcon = {
                    IconButton(onClick = onBackClick) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = "Back"
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.primary,
                    titleContentColor = MaterialTheme.colorScheme.onPrimary,
                    navigationIconContentColor = MaterialTheme.colorScheme.onPrimary
                )
            )
        },
        floatingActionButton = {
            if (uiState.selectedChatIds.isNotEmpty()) {
                FloatingActionButton(
                    onClick = { viewModel.send(onDone) },
                    containerColor = MaterialTheme.colorScheme.primary
                ) {
                    if (uiState.isSending) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(24.dp),
                            color = MaterialTheme.colorScheme.onPrimary,
                            strokeWidth = 2.dp
                        )
                    } else {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.Send,
                            contentDescription = "Send",
                            tint = MaterialTheme.colorScheme.onPrimary
                        )
                    }
                }
            }
        },
        snackbarHost = { SnackbarHost(snackbarHostState) }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
        ) {
            // Content preview — fills all space above the chat picker
            ContentPreview(
                content = uiState.sharedContent,
                linkPreview = uiState.linkPreview,
                modifier = Modifier.weight(1f),
                onImageClick = { url -> fullscreenImageUrl = url }
            )

            HorizontalDivider()

            // Search bar
            OutlinedTextField(
                value = uiState.searchQuery,
                onValueChange = viewModel::onSearchQueryChange,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 8.dp),
                placeholder = { Text("Search chats") },
                leadingIcon = {
                    Icon(imageVector = Icons.Default.Search, contentDescription = null)
                },
                singleLine = true
            )

            // Chat list — constrained to ~3.5 rows so content preview gets generous space
            when {
                uiState.filteredChats.isEmpty() -> {
                    Box(
                        modifier = Modifier
                            .heightIn(max = 260.dp)
                            .fillMaxWidth(),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = if (uiState.searchQuery.isBlank()) "No chats yet"
                            else "No results for \"${uiState.searchQuery}\"",
                            style = MaterialTheme.typography.bodyLarge,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }

                else -> {
                    LazyColumn(modifier = Modifier.heightIn(max = 260.dp)) {
                        items(uiState.filteredChats, key = { it.id }) { chat ->
                            ShareChatRow(
                                chat = chat,
                                currentUserId = uiState.currentUserId,
                                participantProfiles = uiState.participantProfiles,
                                isSelected = chat.id in uiState.selectedChatIds,
                                onClick = { viewModel.toggleChatSelection(chat.id) }
                            )
                            HorizontalDivider(modifier = Modifier.padding(start = 72.dp))
                        }
                    }
                }
            }
        }
    }

    // Fullscreen image overlay
    fullscreenImageUrl?.let { url ->
        FullscreenImageViewer(imageUrl = url, onDismiss = { fullscreenImageUrl = null })
    }
}

@Composable
private fun ContentPreview(
    content: SharedContent?,
    linkPreview: LinkPreview?,
    modifier: Modifier = Modifier,
    onImageClick: (String) -> Unit
) {
    Box(modifier = modifier.fillMaxWidth()) {
        when (content) {
            is SharedContent.Text -> TextPreview(
                text = content.text,
                linkPreview = linkPreview,
                onImageClick = onImageClick
            )

            is SharedContent.Media -> when (content.items.size) {
                1 -> SingleMediaPreview(content.items[0])
                else -> MultiMediaPreview(content.items)
            }

            null -> Unit
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
private fun SingleMediaPreview(item: SharedContent.Media.MediaItem) {
    if (item.mimeType.startsWith("image") || item.mimeType.startsWith("video")) {
        AsyncImage(
            model = item.cachedUri,
            contentDescription = item.fileName,
            contentScale = ContentScale.Fit,
            modifier = Modifier.fillMaxSize()
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
            items(items.take(8), key = { it.cachedUri }) { item ->
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

@Composable
private fun ShareChatRow(
    chat: Chat,
    currentUserId: String,
    participantProfiles: Map<String, User>,
    isSelected: Boolean,
    onClick: () -> Unit
) {
    val recipientId = chat.participants.firstOrNull { it != currentUserId }
    val profile = recipientId?.let { participantProfiles[it] }
    val displayName = chat.name
        ?: profile?.displayName?.takeIf { it.isNotBlank() }
        ?: recipientId
        ?: "Chat"
    val avatarUrl = chat.avatarUrl ?: profile?.avatarUrl

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        UserAvatar(
            avatarUrl = avatarUrl,
            contentDescription = displayName,
            icon = when (chat.type) {
                ChatType.BROADCAST -> Icons.Default.Campaign
                ChatType.GROUP -> Icons.Default.Group
                else -> Icons.Default.Person
            },
            size = 48.dp,
            modifier = Modifier.size(48.dp)
        )

        Spacer(modifier = Modifier.width(16.dp))

        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = displayName,
                style = MaterialTheme.typography.bodyLarge,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            chat.lastMessage?.let { msg ->
                Text(
                    text = msg.content.take(50),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }

        Checkbox(
            checked = isSelected,
            onCheckedChange = { onClick() }
        )
    }
}
