package com.firestream.chat.ui.chat

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.media.MediaPlayer
import android.media.MediaRecorder
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
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.offset
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
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Reply
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.AssistChip
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import androidx.hilt.navigation.compose.hiltViewModel
import coil.compose.AsyncImage
import com.firestream.chat.R
import com.firestream.chat.data.remote.LinkPreview
import com.firestream.chat.domain.model.Message
import com.firestream.chat.domain.model.MessageStatus
import com.firestream.chat.domain.model.MessageType
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.roundToInt

private val QUICK_REACTIONS = listOf("👍", "❤️", "😂", "😮", "😢", "🙏")

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChatScreen(
    onBackClick: () -> Unit,
    onMessageInfoClick: (Message) -> Unit = {},
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

    // Voice recording state
    var isRecording by remember { mutableStateOf(false) }
    var recordingSeconds by remember { mutableIntStateOf(0) }
    var recordedFileUri by remember { mutableStateOf<Uri?>(null) }
    var recordedDuration by remember { mutableIntStateOf(0) }
    val mediaRecorder = remember { mutableStateOf<MediaRecorder?>(null) }

    // Reaction picker state
    var reactionTargetMessage by remember { mutableStateOf<Message?>(null) }

    // Forward picker state
    var forwardTargetMessage by remember { mutableStateOf<Message?>(null) }

    LaunchedEffect(isRecording) {
        if (isRecording) {
            recordingSeconds = 0
            while (isRecording) {
                delay(1000)
                recordingSeconds++
            }
        }
    }

    LaunchedEffect(uiState.editingMessage) {
        val editing = uiState.editingMessage
        if (editing != null) messageText = editing.content
    }

    LaunchedEffect(uiState.messages.size) {
        if (uiState.messages.isNotEmpty()) {
            listState.animateScrollToItem(uiState.messages.size - 1)
        }
    }

    var cameraUri by remember { mutableStateOf<Uri?>(null) }

    val galleryLauncher = rememberLauncherForActivityResult(PickVisualMedia()) { uri ->
        uri?.let { viewModel.sendMediaMessage(it, "image/jpeg") }
    }

    val cameraLauncher = rememberLauncherForActivityResult(ActivityResultContracts.TakePicture()) { success ->
        if (success) cameraUri?.let { viewModel.sendMediaMessage(it, "image/jpeg") }
    }

    val fileLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri?.let {
            val mimeType = context.contentResolver.getType(it) ?: "application/octet-stream"
            viewModel.sendMediaMessage(it, mimeType)
        }
    }

    val cameraPermissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) {
            cameraUri = createCameraUri(context)
            cameraUri?.let { cameraLauncher.launch(it) }
        }
    }

    val galleryPermissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { _ ->
        galleryLauncher.launch(PickVisualMediaRequest(PickVisualMedia.ImageOnly))
    }

    val audioPermissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) {
            startRecording(context, mediaRecorder) { uri ->
                recordedFileUri = uri
                isRecording = true
            }
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(text = "Chat") },
                navigationIcon = {
                    IconButton(onClick = onBackClick) {
                        Icon(imageVector = Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
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
                    Box(modifier = Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                        CircularProgressIndicator()
                    }
                }
                else -> {
                    LazyColumn(
                        state = listState,
                        modifier = Modifier.weight(1f).fillMaxWidth().padding(horizontal = 8.dp),
                        verticalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        items(uiState.messages, key = { it.id }) { message ->
                            val isOwn = message.senderId == uiState.currentUserId
                            val replyToMessage = message.replyToId?.let { id ->
                                uiState.messages.find { it.id == id }
                            }
                            val linkPreview = if (message.type == MessageType.TEXT) {
                                uiState.linkPreviews.entries.firstOrNull { (url, _) ->
                                    message.content.contains(url)
                                }?.value
                            } else null

                            MessageBubble(
                                message = message,
                                isOwnMessage = isOwn,
                                replyToMessage = replyToMessage,
                                linkPreview = linkPreview,
                                currentUserId = uiState.currentUserId,
                                onDeleteClick = if (isOwn) {
                                    { viewModel.deleteMessage(message.id) }
                                } else null,
                                onEditClick = if (isOwn && message.type == MessageType.TEXT) {
                                    { viewModel.startEdit(message) }
                                } else null,
                                onReplyClick = { viewModel.setReplyTo(message) },
                                onReactionClick = { reactionTargetMessage = message },
                                onForwardClick = { forwardTargetMessage = message },
                                onInfoClick = if (isOwn) {
                                    { onMessageInfoClick(message) }
                                } else null,
                                onImageClick = { url -> fullscreenImageUrl = url }
                            )
                        }
                    }
                }
            }

            // Typing indicator
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

            // Reply-to banner
            if (uiState.replyToMessage != null) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(MaterialTheme.colorScheme.surfaceVariant)
                        .padding(horizontal = 12.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        imageVector = Icons.Default.Reply,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(16.dp)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = "Replying to",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.primary
                        )
                        Text(
                            text = uiState.replyToMessage!!.content.take(60),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                    IconButton(onClick = { viewModel.clearReplyTo() }) {
                        Icon(
                            imageVector = Icons.Default.Close,
                            contentDescription = "Cancel reply",
                            tint = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }

            // Voice recording indicator
            if (isRecording) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(MaterialTheme.colorScheme.errorContainer)
                        .padding(horizontal = 16.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        imageVector = Icons.Default.Mic,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.error,
                        modifier = Modifier.size(16.dp)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = "Recording  ${recordingSeconds / 60}:${(recordingSeconds % 60).toString().padStart(2, '0')}",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onErrorContainer,
                        modifier = Modifier.weight(1f)
                    )
                    IconButton(onClick = {
                        stopRecording(mediaRecorder)
                        isRecording = false
                        recordedDuration = recordingSeconds
                        // Discard
                        recordedFileUri = null
                        recordedDuration = 0
                    }) {
                        Icon(
                            imageVector = Icons.Default.Close,
                            contentDescription = "Cancel recording",
                            tint = MaterialTheme.colorScheme.error
                        )
                    }
                    IconButton(onClick = {
                        val uri = recordedFileUri
                        stopRecording(mediaRecorder)
                        isRecording = false
                        recordedDuration = recordingSeconds
                        if (uri != null && recordedDuration > 0) {
                            viewModel.sendVoiceMessage(uri, recordedDuration)
                        }
                        recordedFileUri = null
                        recordedDuration = 0
                    }) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.Send,
                            contentDescription = "Send voice message",
                            tint = MaterialTheme.colorScheme.primary
                        )
                    }
                }
            }

            // Input row
            Row(
                modifier = Modifier.fillMaxWidth().padding(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                if (uiState.editingMessage == null && !isRecording) {
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

                if (messageText.isBlank() && uiState.editingMessage == null && !isRecording) {
                    // Mic button for voice recording
                    IconButton(onClick = {
                        if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO)
                            == PackageManager.PERMISSION_GRANTED) {
                            startRecording(context, mediaRecorder) { uri ->
                                recordedFileUri = uri
                                isRecording = true
                            }
                        } else {
                            audioPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
                        }
                    }) {
                        Icon(
                            imageVector = Icons.Default.Mic,
                            contentDescription = "Record voice message",
                            tint = MaterialTheme.colorScheme.primary
                        )
                    }
                } else {
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
    }

    // Attachment bottom sheet
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
                            val permission = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU)
                                Manifest.permission.READ_MEDIA_IMAGES
                            else Manifest.permission.READ_EXTERNAL_STORAGE
                            if (ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED) {
                                galleryLauncher.launch(PickVisualMediaRequest(PickVisualMedia.ImageOnly))
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

    // Reaction picker bottom sheet
    reactionTargetMessage?.let { targetMsg ->
        ModalBottomSheet(
            onDismissRequest = { reactionTargetMessage = null }
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 8.dp, ),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text(
                    text = "React",
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.padding(bottom = 12.dp)
                )
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceEvenly
                ) {
                    QUICK_REACTIONS.forEach { emoji ->
                        val isSelected = targetMsg.reactions[uiState.currentUserId] == emoji
                        Box(
                            modifier = Modifier
                                .size(48.dp)
                                .clip(CircleShape)
                                .background(
                                    if (isSelected) MaterialTheme.colorScheme.primaryContainer
                                    else androidx.compose.ui.graphics.Color.Transparent
                                )
                                .clickable {
                                    viewModel.toggleReaction(targetMsg.id, emoji)
                                    reactionTargetMessage = null
                                },
                            contentAlignment = Alignment.Center
                        ) {
                            Text(text = emoji, style = MaterialTheme.typography.headlineMedium)
                        }
                    }
                }
                Spacer(modifier = Modifier.height(24.dp))
            }
        }
    }

    // Forward picker
    forwardTargetMessage?.let { targetMsg ->
        ForwardChatPicker(
            chats = emptyList(), // In a full implementation, inject GetChatsUseCase here
            onDismiss = { forwardTargetMessage = null },
            onForward = { chatId, recipientId ->
                viewModel.forwardMessage(targetMsg, chatId, recipientId)
                forwardTargetMessage = null
            }
        )
    }

    // Fullscreen image viewer
    AnimatedVisibility(visible = fullscreenImageUrl != null, enter = fadeIn(), exit = fadeOut()) {
        fullscreenImageUrl?.let { url ->
            FullscreenImageViewer(imageUrl = url, onDismiss = { fullscreenImageUrl = null })
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

private fun startRecording(
    context: Context,
    recorderHolder: androidx.compose.runtime.MutableState<MediaRecorder?>,
    onReady: (Uri) -> Unit
) {
    try {
        val audioDir = File(context.cacheDir, "voice").also { it.mkdirs() }
        val file = File(audioDir, "voice_${System.currentTimeMillis()}.aac")
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)

        @Suppress("DEPRECATION")
        val recorder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            MediaRecorder(context)
        } else {
            MediaRecorder()
        }
        recorder.setAudioSource(MediaRecorder.AudioSource.MIC)
        recorder.setOutputFormat(MediaRecorder.OutputFormat.AAC_ADTS)
        recorder.setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
        recorder.setOutputFile(file.absolutePath)
        recorder.prepare()
        recorder.start()
        recorderHolder.value = recorder
        onReady(uri)
    } catch (_: Exception) {
        /* permission or hardware unavailable */
    }
}

private fun stopRecording(recorderHolder: androidx.compose.runtime.MutableState<MediaRecorder?>) {
    try {
        recorderHolder.value?.apply {
            stop()
            release()
        }
    } catch (_: Exception) { /* ignore stop errors */ }
    recorderHolder.value = null
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
        Box(
            modifier = Modifier
                .align(Alignment.TopEnd)
                .windowInsetsPadding(WindowInsets.statusBars)
                .padding(12.dp)
                .size(36.dp)
                .background(color = androidx.compose.ui.graphics.Color.Black.copy(alpha = 0.5f), shape = CircleShape)
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

@Composable
private fun ForwardChatPicker(
    chats: List<Pair<String, String>>, // chatId to displayName pairs
    onDismiss: () -> Unit,
    onForward: (chatId: String, recipientId: String) -> Unit
) {
    // Simple dialog — in a real app, list recent chats from GetChatsUseCase
    androidx.compose.material3.AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Forward to") },
        text = {
            if (chats.isEmpty()) {
                Text(
                    "Open a chat first, then use Forward from within that chat.",
                    style = MaterialTheme.typography.bodyMedium
                )
            } else {
                Column {
                    chats.forEach { (chatId, name) ->
                        Text(
                            text = name,
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { onForward(chatId, "") }
                                .padding(vertical = 8.dp)
                        )
                    }
                }
            }
        },
        confirmButton = {
            androidx.compose.material3.TextButton(onClick = onDismiss) { Text("Cancel") }
        }
    )
}

@OptIn(ExperimentalFoundationApi::class, ExperimentalLayoutApi::class)
@Composable
private fun MessageBubble(
    message: Message,
    isOwnMessage: Boolean,
    replyToMessage: Message?,
    linkPreview: LinkPreview?,
    currentUserId: String,
    onDeleteClick: (() -> Unit)?,
    onEditClick: (() -> Unit)?,
    onReplyClick: () -> Unit,
    onReactionClick: () -> Unit,
    onForwardClick: () -> Unit,
    onInfoClick: (() -> Unit)?,
    onImageClick: (String) -> Unit = {}
) {
    val bubbleColor = if (isOwnMessage) MaterialTheme.colorScheme.primary
    else MaterialTheme.colorScheme.surfaceVariant
    val textColor = if (isOwnMessage) MaterialTheme.colorScheme.onPrimary
    else MaterialTheme.colorScheme.onSurfaceVariant
    val alignment = if (isOwnMessage) Alignment.End else Alignment.Start

    var showMenu by remember { mutableStateOf(false) }
    var swipeOffset by remember { mutableFloatStateOf(0f) }

    // Grouped reactions: emoji → count
    val groupedReactions = message.reactions.values
        .groupBy { it }
        .mapValues { it.value.size }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .offset { IntOffset(swipeOffset.roundToInt(), 0) }
            .pointerInput(Unit) {
                detectHorizontalDragGestures(
                    onDragEnd = {
                        if (swipeOffset > 60f) {
                            onReplyClick()
                        }
                        swipeOffset = 0f
                    },
                    onHorizontalDrag = { _, dragAmount ->
                        if (!isOwnMessage || dragAmount > 0) {
                            swipeOffset = (swipeOffset + dragAmount).coerceIn(0f, 80f)
                        }
                    }
                )
            },
        horizontalAlignment = alignment
    ) {
        // Swipe-to-reply hint icon
        if (swipeOffset > 20f) {
            Box(modifier = Modifier.align(Alignment.Start).padding(start = 4.dp)) {
                Icon(
                    imageVector = Icons.Default.Reply,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary.copy(alpha = swipeOffset / 80f),
                    modifier = Modifier.size(20.dp)
                )
            }
        }

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
                    .combinedClickable(
                        onClick = {},
                        onLongClick = { showMenu = true }
                    )
                    .padding(horizontal = 12.dp, vertical = 8.dp)
            ) {
                Column {
                    // Forwarded label
                    if (message.isForwarded) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(
                                imageVector = Icons.Default.Share,
                                contentDescription = null,
                                tint = textColor.copy(alpha = 0.6f),
                                modifier = Modifier.size(12.dp)
                            )
                            Spacer(modifier = Modifier.width(4.dp))
                            Text(
                                text = "Forwarded",
                                style = MaterialTheme.typography.labelSmall,
                                color = textColor.copy(alpha = 0.6f),
                                fontStyle = FontStyle.Italic
                            )
                        }
                        Spacer(modifier = Modifier.height(4.dp))
                    }

                    // Quoted reply snippet
                    if (replyToMessage != null) {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .background(
                                    color = textColor.copy(alpha = 0.1f),
                                    shape = RoundedCornerShape(8.dp)
                                )
                                .padding(horizontal = 8.dp, vertical = 4.dp)
                        ) {
                            Text(
                                text = replyToMessage.content.take(80),
                                style = MaterialTheme.typography.bodySmall,
                                color = textColor.copy(alpha = 0.8f),
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis,
                                fontStyle = FontStyle.Italic
                            )
                        }
                        Spacer(modifier = Modifier.height(4.dp))
                    }

                    // Message content
                    when (message.type) {
                        MessageType.IMAGE -> {
                            if (message.status == MessageStatus.SENDING || message.mediaUrl == null) {
                                Box(
                                    modifier = Modifier.fillMaxWidth(0.65f).height(160.dp),
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
                        MessageType.VOICE -> {
                            VoiceMessagePlayer(
                                mediaUrl = message.mediaUrl,
                                durationSeconds = message.duration ?: 0,
                                textColor = textColor
                            )
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
                            // Link preview card
                            if (linkPreview != null) {
                                Spacer(modifier = Modifier.height(6.dp))
                                LinkPreviewCard(preview = linkPreview, textColor = textColor)
                            }
                        }
                    }

                    // Timestamp + status row
                    Row(horizontalArrangement = Arrangement.End, modifier = Modifier.fillMaxWidth()) {
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
                                color = if (message.status == MessageStatus.READ)
                                    MaterialTheme.colorScheme.inversePrimary
                                else textColor.copy(alpha = 0.7f),
                                style = MaterialTheme.typography.labelSmall,
                                fontWeight = if (message.status == MessageStatus.READ) FontWeight.Bold else null
                            )
                        }
                    }
                }
            }

            // Context menu
            DropdownMenu(expanded = showMenu, onDismissRequest = { showMenu = false }) {
                DropdownMenuItem(
                    text = { Text("Reply") },
                    leadingIcon = { Icon(Icons.Default.Reply, null) },
                    onClick = { showMenu = false; onReplyClick() }
                )
                DropdownMenuItem(
                    text = { Text("React") },
                    onClick = { showMenu = false; onReactionClick() }
                )
                DropdownMenuItem(
                    text = { Text("Forward") },
                    leadingIcon = { Icon(Icons.Default.Share, null) },
                    onClick = { showMenu = false; onForwardClick() }
                )
                onEditClick?.let {
                    DropdownMenuItem(
                        text = { Text("Edit") },
                        onClick = { showMenu = false; it() }
                    )
                }
                onInfoClick?.let {
                    DropdownMenuItem(
                        text = { Text("Message Info") },
                        leadingIcon = { Icon(Icons.Default.Info, null) },
                        onClick = { showMenu = false; it() }
                    )
                }
                onDeleteClick?.let {
                    DropdownMenuItem(
                        text = { Text("Delete for everyone", color = MaterialTheme.colorScheme.error) },
                        onClick = { showMenu = false; it() }
                    )
                }
            }
        }

        // Reaction chips below bubble
        if (groupedReactions.isNotEmpty()) {
            FlowRow(
                modifier = Modifier.padding(top = 2.dp),
                horizontalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                groupedReactions.forEach { (emoji, count) ->
                    val myReaction = message.reactions[currentUserId] == emoji
                    AssistChip(
                        onClick = { /* handled by reaction picker */ },
                        label = {
                            Text(
                                text = if (count > 1) "$emoji $count" else emoji,
                                style = MaterialTheme.typography.labelSmall
                            )
                        },
                        modifier = Modifier.height(28.dp),
                        border = if (myReaction) androidx.compose.material3.AssistChipDefaults.assistChipBorder(
                            borderColor = MaterialTheme.colorScheme.primary
                        ) else androidx.compose.material3.AssistChipDefaults.assistChipBorder()
                    )
                }
            }
        }
    }
}

@Composable
private fun VoiceMessagePlayer(
    mediaUrl: String?,
    durationSeconds: Int,
    textColor: androidx.compose.ui.graphics.Color
) {
    var isPlaying by remember { mutableStateOf(false) }
    var progress by remember { mutableFloatStateOf(0f) }
    var speed by remember { mutableFloatStateOf(1f) }
    val mediaPlayer = remember { mutableStateOf<MediaPlayer?>(null) }

    DisposableEffect(mediaUrl) {
        onDispose {
            mediaPlayer.value?.release()
            mediaPlayer.value = null
        }
    }

    LaunchedEffect(isPlaying) {
        if (isPlaying) {
            val total = (mediaPlayer.value?.duration ?: (durationSeconds * 1000)).toFloat()
            while (isPlaying) {
                val current = mediaPlayer.value?.currentPosition ?: 0
                progress = if (total > 0) current / total else 0f
                if (mediaPlayer.value?.isPlaying == false) {
                    isPlaying = false
                    progress = 0f
                }
                delay(100)
            }
        }
    }

    Column(modifier = Modifier.widthIn(min = 160.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconButton(
                onClick = {
                    if (mediaUrl == null) return@IconButton
                    if (isPlaying) {
                        mediaPlayer.value?.pause()
                        isPlaying = false
                    } else {
                        if (mediaPlayer.value == null) {
                            mediaPlayer.value = MediaPlayer().apply {
                                setDataSource(mediaUrl)
                                playbackParams = playbackParams.setSpeed(speed)
                                prepare()
                            }
                        }
                        mediaPlayer.value?.let {
                            it.playbackParams = it.playbackParams.setSpeed(speed)
                            it.start()
                        }
                        isPlaying = true
                    }
                },
                modifier = Modifier.size(36.dp)
            ) {
                Icon(
                    imageVector = if (isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow,
                    contentDescription = if (isPlaying) "Pause" else "Play",
                    tint = textColor
                )
            }
            LinearProgressIndicator(
                progress = { progress },
                modifier = Modifier.weight(1f).height(3.dp),
                color = textColor,
                trackColor = textColor.copy(alpha = 0.3f)
            )
            Spacer(modifier = Modifier.width(8.dp))
            // Speed toggle
            AssistChip(
                onClick = {
                    speed = when (speed) {
                        1f -> 1.5f
                        1.5f -> 2f
                        else -> 1f
                    }
                    mediaPlayer.value?.let {
                        if (it.isPlaying) it.playbackParams = it.playbackParams.setSpeed(speed)
                    }
                },
                label = { Text("${speed}x", style = MaterialTheme.typography.labelSmall) },
                modifier = Modifier.height(24.dp)
            )
        }
        Text(
            text = formatDuration(durationSeconds),
            style = MaterialTheme.typography.labelSmall,
            color = textColor.copy(alpha = 0.7f),
            modifier = Modifier.padding(start = 36.dp)
        )
    }
}

@Composable
private fun LinkPreviewCard(
    preview: LinkPreview,
    textColor: androidx.compose.ui.graphics.Color
) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .background(
                color = textColor.copy(alpha = 0.08f),
                shape = RoundedCornerShape(8.dp)
            )
            .padding(8.dp)
    ) {
        Column {
            if (preview.imageUrl != null) {
                AsyncImage(
                    model = preview.imageUrl,
                    contentDescription = "Link preview image",
                    contentScale = ContentScale.Crop,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(80.dp)
                        .clip(RoundedCornerShape(6.dp))
                )
                Spacer(modifier = Modifier.height(4.dp))
            }
            if (preview.title != null) {
                Text(
                    text = preview.title,
                    style = MaterialTheme.typography.bodySmall.copy(fontWeight = FontWeight.SemiBold),
                    color = textColor,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
            }
            if (preview.description != null) {
                Text(
                    text = preview.description,
                    style = MaterialTheme.typography.labelSmall,
                    color = textColor.copy(alpha = 0.7f),
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
            }
            Text(
                text = preview.url,
                style = MaterialTheme.typography.labelSmall.copy(textDecoration = TextDecoration.Underline),
                color = textColor.copy(alpha = 0.6f),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}

private fun formatTimestamp(timestamp: Long): String {
    val sdf = SimpleDateFormat("HH:mm", Locale.getDefault())
    return sdf.format(Date(timestamp))
}

private fun formatDuration(seconds: Int): String {
    val m = seconds / 60
    val s = seconds % 60
    return "$m:${s.toString().padStart(2, '0')}"
}
