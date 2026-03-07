package com.firestream.chat.ui.chat

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.result.contract.ActivityResultContracts.PickVisualMedia
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.AttachFile
import androidx.compose.material.icons.filled.CameraAlt
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Image
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import androidx.hilt.navigation.compose.hiltViewModel
import coil.compose.AsyncImage
import com.firestream.chat.R
import com.firestream.chat.domain.model.Message
import com.firestream.chat.domain.model.MessageStatus
import com.firestream.chat.domain.model.MessageType
import kotlinx.coroutines.launch
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChatScreen(
    onBackClick: () -> Unit,
    viewModel: ChatViewModel = hiltViewModel()
) {
    val uiState by viewModel.uiState.collectAsState()
    var messageText by rememberSaveable { mutableStateOf("") }
    val listState = rememberLazyListState()
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var showAttachmentSheet by remember { mutableStateOf(false) }
    var fullscreenImageUrl by remember { mutableStateOf<String?>(null) }
    val sheetState = rememberModalBottomSheetState()

    // Pre-fill text when entering edit mode
    LaunchedEffect(uiState.editingMessage) {
        val editing = uiState.editingMessage
        if (editing != null) {
            messageText = editing.content
        }
    }

    LaunchedEffect(uiState.messages.size) {
        if (uiState.messages.isNotEmpty()) {
            listState.animateScrollToItem(uiState.messages.size - 1)
        }
    }

    // Camera URI state
    var cameraUri by remember { mutableStateOf<Uri?>(null) }

    // Activity result launchers
    val galleryLauncher = rememberLauncherForActivityResult(
        PickVisualMedia()
    ) { uri ->
        uri?.let { viewModel.sendMediaMessage(it, "image/jpeg") }
    }

    val cameraLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.TakePicture()
    ) { success ->
        if (success) {
            cameraUri?.let { viewModel.sendMediaMessage(it, "image/jpeg") }
        }
    }

    val fileLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        uri?.let {
            val mimeType = context.contentResolver.getType(it) ?: "application/octet-stream"
            viewModel.sendMediaMessage(it, mimeType)
        }
    }

    val cameraPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) {
            cameraUri = createCameraUri(context)
            cameraUri?.let { cameraLauncher.launch(it) }
        }
    }

    val galleryPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { _ ->
        galleryLauncher.launch(
            PickVisualMediaRequest(PickVisualMedia.ImageOnly)
        )
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(text = "Chat") },
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
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .imePadding()
        ) {
            when {
                uiState.isLoading -> {
                    Box(
                        modifier = Modifier.weight(1f).fillMaxWidth(),
                        contentAlignment = Alignment.Center
                    ) {
                        CircularProgressIndicator()
                    }
                }
                else -> {
                    LazyColumn(
                        state = listState,
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxWidth()
                            .padding(horizontal = 8.dp),
                        verticalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        items(uiState.messages, key = { it.id }) { message ->
                            val isOwn = message.senderId == uiState.currentUserId
                            MessageBubble(
                                message = message,
                                isOwnMessage = isOwn,
                                onDeleteClick = if (isOwn) {
                                    { viewModel.deleteMessage(message.id) }
                                } else null,
                                onEditClick = if (isOwn && message.type == MessageType.TEXT) {
                                    { viewModel.startEdit(message) }
                                } else null,
                                onImageClick = { url -> fullscreenImageUrl = url }
                            )
                        }
                    }
                }
            }

            if (uiState.typingUserIds.isNotEmpty()) {
                Text(
                    text = "typing...",
                    style = MaterialTheme.typography.bodySmall.copy(fontStyle = FontStyle.Italic),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(start = 8.dp, bottom = 2.dp)
                )
            }

            // Edit mode banner
            if (uiState.editingMessage != null) {
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
                    }) {
                        Icon(
                            imageVector = Icons.Default.Close,
                            contentDescription = "Cancel edit",
                            tint = MaterialTheme.colorScheme.onSecondaryContainer
                        )
                    }
                }
            }

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                // Attachment button (only shown when not in edit mode)
                if (uiState.editingMessage == null) {
                    IconButton(onClick = { showAttachmentSheet = true }) {
                        Icon(
                            imageVector = Icons.Default.Add,
                            contentDescription = "Attach",
                            tint = MaterialTheme.colorScheme.primary
                        )
                    }
                }

                OutlinedTextField(
                    value = messageText,
                    onValueChange = {
                        messageText = it
                        if (uiState.editingMessage == null) viewModel.onTyping(it)
                    },
                    modifier = Modifier.weight(1f),
                    placeholder = {
                        Text(
                            if (uiState.editingMessage != null) "Edit message..."
                            else stringResource(R.string.type_message)
                        )
                    },
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                    keyboardActions = KeyboardActions(
                        onSend = {
                            handleSend(viewModel, uiState, messageText)
                            messageText = ""
                        }
                    ),
                    maxLines = 4,
                    shape = RoundedCornerShape(24.dp)
                )
                Spacer(modifier = Modifier.width(8.dp))
                IconButton(
                    onClick = {
                        handleSend(viewModel, uiState, messageText)
                        messageText = ""
                    },
                    enabled = messageText.isNotBlank() && !uiState.isSending
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
    }

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
                            val permission = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                                Manifest.permission.READ_MEDIA_IMAGES
                            } else {
                                Manifest.permission.READ_EXTERNAL_STORAGE
                            }
                            if (ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED) {
                                galleryLauncher.launch(
                                    PickVisualMediaRequest(PickVisualMedia.ImageOnly)
                                )
                            } else {
                                galleryPermissionLauncher.launch(permission)
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
                    icon = Icons.Default.AttachFile,
                    label = "File",
                    onClick = {
                        scope.launch { sheetState.hide() }.invokeOnCompletion {
                            showAttachmentSheet = false
                            fileLauncher.launch(arrayOf("*/*"))
                        }
                    }
                )
            }
        }
    }

    // Fullscreen image viewer
    AnimatedVisibility(
        visible = fullscreenImageUrl != null,
        enter = fadeIn(),
        exit = fadeOut()
    ) {
        fullscreenImageUrl?.let { url ->
            FullscreenImageViewer(
                imageUrl = url,
                onDismiss = { fullscreenImageUrl = null }
            )
        }
    }
}

private fun handleSend(viewModel: ChatViewModel, uiState: ChatUiState, text: String) {
    if (uiState.editingMessage != null) {
        viewModel.confirmEdit(text)
    } else {
        viewModel.sendMessage(text)
        viewModel.onTyping("")
    }
}

private fun createCameraUri(context: Context): Uri {
    val cacheDir = File(context.cacheDir, "camera").also { it.mkdirs() }
    val file = File(cacheDir, "photo_${System.currentTimeMillis()}.jpg")
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

@Composable
private fun FullscreenImageViewer(imageUrl: String, onDismiss: () -> Unit) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(androidx.compose.ui.graphics.Color.Black)
            .clickable(onClick = onDismiss),
        contentAlignment = Alignment.Center
    ) {
        AsyncImage(
            model = imageUrl,
            contentDescription = "Full screen image",
            contentScale = ContentScale.Fit,
            modifier = Modifier.fillMaxSize()
        )
        // Close button
        Box(
            modifier = Modifier
                .align(Alignment.TopEnd)
                .windowInsetsPadding(WindowInsets.statusBars)
                .padding(12.dp)
                .size(36.dp)
                .background(
                    color = androidx.compose.ui.graphics.Color.Black.copy(alpha = 0.5f),
                    shape = CircleShape
                )
                .clickable(onClick = onDismiss),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                imageVector = Icons.Default.Close,
                contentDescription = "Close",
                tint = androidx.compose.ui.graphics.Color.White,
                modifier = Modifier.size(20.dp)
            )
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun MessageBubble(
    message: Message,
    isOwnMessage: Boolean,
    onDeleteClick: (() -> Unit)?,
    onEditClick: (() -> Unit)?,
    onImageClick: (String) -> Unit = {}
) {
    val bubbleColor = if (isOwnMessage) {
        MaterialTheme.colorScheme.primary
    } else {
        MaterialTheme.colorScheme.surfaceVariant
    }
    val textColor = if (isOwnMessage) {
        MaterialTheme.colorScheme.onPrimary
    } else {
        MaterialTheme.colorScheme.onSurfaceVariant
    }
    val alignment = if (isOwnMessage) Alignment.End else Alignment.Start

    var showMenu by remember { mutableStateOf(false) }

    Column(
        modifier = Modifier.fillMaxWidth(),
        horizontalAlignment = alignment
    ) {
        Box {
            Box(
                modifier = Modifier
                    .widthIn(max = 280.dp)
                    .background(
                        color = bubbleColor,
                        shape = RoundedCornerShape(
                            topStart = 16.dp,
                            topEnd = 16.dp,
                            bottomStart = if (isOwnMessage) 16.dp else 4.dp,
                            bottomEnd = if (isOwnMessage) 4.dp else 16.dp
                        )
                    )
                    .then(
                        if (isOwnMessage && (onDeleteClick != null || onEditClick != null)) {
                            Modifier.combinedClickable(
                                onClick = {},
                                onLongClick = { showMenu = true }
                            )
                        } else Modifier
                    )
                    .padding(horizontal = 12.dp, vertical = 8.dp)
            ) {
                Column {
                    when (message.type) {
                        MessageType.IMAGE -> {
                            if (message.status == MessageStatus.SENDING || message.mediaUrl == null) {
                                Box(
                                    modifier = Modifier
                                        .fillMaxWidth(0.65f)
                                        .height(160.dp),
                                    contentAlignment = Alignment.Center
                                ) {
                                    CircularProgressIndicator(color = textColor)
                                }
                            } else {
                                AsyncImage(
                                    model = message.mediaUrl,
                                    contentDescription = "Image",
                                    contentScale = ContentScale.Crop,
                                    modifier = Modifier
                                        .fillMaxWidth(0.65f)
                                        .clip(RoundedCornerShape(8.dp))
                                        .clickable { message.mediaUrl?.let { onImageClick(it) } }
                                )
                            }
                        }
                        MessageType.DOCUMENT -> {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(
                                    imageVector = Icons.Default.AttachFile,
                                    contentDescription = null,
                                    tint = textColor,
                                    modifier = Modifier.size(20.dp)
                                )
                                Spacer(modifier = Modifier.width(4.dp))
                                Text(
                                    text = message.content,
                                    color = textColor,
                                    style = MaterialTheme.typography.bodyMedium
                                )
                            }
                        }
                        else -> {
                            Text(
                                text = message.content,
                                color = textColor,
                                style = MaterialTheme.typography.bodyMedium
                            )
                            if (message.editedAt != null) {
                                Text(
                                    text = "(edited)",
                                    color = textColor.copy(alpha = 0.6f),
                                    style = MaterialTheme.typography.labelSmall
                                )
                            }
                        }
                    }
                    Row(
                        horizontalArrangement = Arrangement.End,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text(
                            text = formatTimestamp(message.timestamp),
                            color = textColor.copy(alpha = 0.7f),
                            style = MaterialTheme.typography.labelSmall
                        )
                        if (isOwnMessage) {
                            Spacer(modifier = Modifier.width(4.dp))
                            Text(
                                text = when (message.status) {
                                    MessageStatus.SENDING -> "○"
                                    MessageStatus.SENT -> "✓"
                                    MessageStatus.DELIVERED -> "✓✓"
                                    MessageStatus.READ -> "✓✓"
                                    MessageStatus.FAILED -> "!"
                                },
                                color = textColor.copy(alpha = 0.7f),
                                style = MaterialTheme.typography.labelSmall
                            )
                        }
                    }
                }
            }

            DropdownMenu(
                expanded = showMenu,
                onDismissRequest = { showMenu = false }
            ) {
                onEditClick?.let {
                    DropdownMenuItem(
                        text = { Text("Edit") },
                        onClick = {
                            showMenu = false
                            it()
                        }
                    )
                }
                onDeleteClick?.let {
                    DropdownMenuItem(
                        text = { Text("Delete for everyone", color = MaterialTheme.colorScheme.error) },
                        onClick = {
                            showMenu = false
                            it()
                        }
                    )
                }
            }
        }
    }
}

private fun formatTimestamp(timestamp: Long): String {
    val sdf = SimpleDateFormat("HH:mm", Locale.getDefault())
    return sdf.format(Date(timestamp))
}
