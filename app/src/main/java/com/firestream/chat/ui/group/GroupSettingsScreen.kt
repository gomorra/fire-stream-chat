package com.firestream.chat.ui.group

import android.Manifest
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.result.contract.ActivityResultContracts.PickVisualMedia
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.ExitToApp
import androidx.compose.material.icons.filled.CameraAlt
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Link
import androidx.compose.material.icons.filled.LinkOff
import androidx.compose.material.icons.filled.PersonAdd
import androidx.compose.material.icons.filled.PersonRemove
import androidx.compose.material.icons.filled.QrCode
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import coil.compose.AsyncImage
import com.firestream.chat.ui.chat.FullscreenImageViewer
import java.io.File
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.firestream.chat.domain.model.GroupRole
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun GroupSettingsScreen(
    onBackClick: () -> Unit,
    onAddMemberClick: () -> Unit = {},
    viewModel: GroupSettingsViewModel = hiltViewModel()
) {
    val uiState by viewModel.uiState.collectAsState()
    val context = LocalContext.current
    val snackbarHostState = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()

    var showQrDialog by remember { mutableStateOf(false) }
    var editingName by remember { mutableStateOf(false) }
    var nameInput by remember { mutableStateOf("") }
    var editingDescription by remember { mutableStateOf(false) }
    var descriptionInput by remember { mutableStateOf("") }
    var showLeaveDialog by remember { mutableStateOf(false) }
    var showRemoveDialog by remember { mutableStateOf<String?>(null) }
    var fullscreenGroupAvatar by remember { mutableStateOf(false) }
    var showPhotoSourceDialog by remember { mutableStateOf(false) }
    var cameraUri by remember { mutableStateOf<Uri?>(null) }

    val galleryLauncher = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        uri?.let { viewModel.uploadGroupAvatar(it) }
    }
    val cameraLauncher = rememberLauncherForActivityResult(ActivityResultContracts.TakePicture()) { success ->
        if (success) cameraUri?.let { viewModel.uploadGroupAvatar(it) }
    }
    val cameraPermissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) {
            val uri = createGroupAvatarCameraUri(context)
            cameraUri = uri
            cameraLauncher.launch(uri)
        }
    }

    LaunchedEffect(uiState.leftGroup) {
        if (uiState.leftGroup) onBackClick()
    }

    LaunchedEffect(uiState.error) {
        uiState.error?.let {
            scope.launch { snackbarHostState.showSnackbar(it) }
            viewModel.clearError()
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Group Settings") },
                navigationIcon = {
                    IconButton(onClick = onBackClick) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface,
                    titleContentColor = MaterialTheme.colorScheme.onSurface,
                    navigationIconContentColor = MaterialTheme.colorScheme.onSurface,
                    actionIconContentColor = MaterialTheme.colorScheme.onSurfaceVariant
                )
            )
        },
        snackbarHost = { SnackbarHost(snackbarHostState) }
    ) { padding ->
        if (uiState.isLoading) {
            Box(
                modifier = Modifier.fillMaxSize().padding(padding),
                contentAlignment = Alignment.Center
            ) {
                CircularProgressIndicator()
            }
            return@Scaffold
        }

        val chat = uiState.chat ?: return@Scaffold
        val isAdmin = uiState.currentUserRole != GroupRole.MEMBER

        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
        ) {
            // Group info header
            item {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Box {
                        // Avatar — clickable for fullscreen when a photo exists
                        Box(
                            modifier = Modifier
                                .size(80.dp)
                                .clip(CircleShape)
                                .clickable(enabled = chat.avatarUrl != null) { fullscreenGroupAvatar = true },
                            contentAlignment = Alignment.Center
                        ) {
                            if (chat.avatarUrl != null) {
                                AsyncImage(
                                    model = chat.avatarUrl,
                                    contentDescription = "Group avatar",
                                    contentScale = ContentScale.Crop,
                                    modifier = Modifier.fillMaxSize()
                                )
                            } else {
                                Box(
                                    modifier = Modifier
                                        .fillMaxSize()
                                        .background(MaterialTheme.colorScheme.primaryContainer),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Text(
                                        text = (chat.name ?: "G").take(1).uppercase(),
                                        style = MaterialTheme.typography.headlineMedium,
                                        color = MaterialTheme.colorScheme.onPrimaryContainer
                                    )
                                }
                            }
                            // Upload progress overlay
                            if (uiState.isUploading) {
                                Box(
                                    modifier = Modifier
                                        .fillMaxSize()
                                        .background(Color.Black.copy(alpha = 0.45f)),
                                    contentAlignment = Alignment.Center
                                ) {
                                    CircularProgressIndicator(
                                        modifier = Modifier.size(32.dp),
                                        color = Color.White,
                                        strokeWidth = 3.dp
                                    )
                                }
                            }
                        }
                        // Edit badge (admin only)
                        if (isAdmin) {
                            Box(
                                modifier = Modifier
                                    .size(26.dp)
                                    .align(Alignment.BottomEnd)
                                    .background(MaterialTheme.colorScheme.primary, CircleShape)
                                    .clickable(enabled = !uiState.isUploading) { showPhotoSourceDialog = true },
                                contentAlignment = Alignment.Center
                            ) {
                                Icon(
                                    imageVector = Icons.Default.CameraAlt,
                                    contentDescription = "Change group photo",
                                    tint = MaterialTheme.colorScheme.onPrimary,
                                    modifier = Modifier.size(14.dp)
                                )
                            }
                        }
                    }
                    Spacer(modifier = Modifier.height(8.dp))
                    EditableGroupField(
                        displayValue = chat.name ?: "Group",
                        label = "Group name",
                        isEditable = isAdmin,
                        isSaving = uiState.isSavingName,
                        isEditing = editingName,
                        input = nameInput,
                        onInputChange = { nameInput = it },
                        onEditClick = { nameInput = chat.name ?: ""; editingName = true },
                        onSave = { viewModel.updateGroupName(nameInput); editingName = false },
                        onCancel = { editingName = false },
                        saveEnabled = nameInput.isNotBlank(),
                        displayTextStyle = MaterialTheme.typography.headlineSmall,
                        displayFontWeight = FontWeight.Bold
                    )
                    Text(
                        text = "${chat.participants.size} members",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    if (uiState.uploadError != null) {
                        Text(
                            text = uiState.uploadError!!,
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.error,
                            modifier = Modifier.padding(top = 4.dp)
                        )
                    }
                }
            }

            // Description section
            item {
                SectionHeader("Description")
                EditableGroupField(
                    displayValue = chat.description ?: if (isAdmin) "Add group description" else "No description",
                    label = "Description",
                    isEditable = isAdmin,
                    isSaving = uiState.isSavingDescription,
                    isEditing = editingDescription,
                    input = descriptionInput,
                    onInputChange = { descriptionInput = it },
                    onEditClick = { descriptionInput = chat.description ?: ""; editingDescription = true },
                    onSave = { viewModel.updateDescription(descriptionInput); editingDescription = false },
                    onCancel = { editingDescription = false },
                    displayColor = if (chat.description != null) Color.Unspecified
                                   else MaterialTheme.colorScheme.onSurfaceVariant,
                    rowModifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp)
                )
                Spacer(modifier = Modifier.height(8.dp))
            }

            // Invite link section (admin only)
            if (isAdmin) {
                item {
                    SectionHeader("Invite Link")
                    if (chat.inviteLink != null) {
                        val inviteUrl = "https://firestream.chat/join/${chat.inviteLink}"
                        Card(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 16.dp),
                            shape = RoundedCornerShape(12.dp)
                        ) {
                            Column(modifier = Modifier.padding(12.dp)) {
                                Text(
                                    text = inviteUrl,
                                    style = MaterialTheme.typography.bodyMedium,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                                Spacer(modifier = Modifier.height(8.dp))
                                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    IconButton(onClick = {
                                        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                                        clipboard.setPrimaryClip(ClipData.newPlainText("Invite Link", inviteUrl))
                                        scope.launch { snackbarHostState.showSnackbar("Link copied") }
                                    }) {
                                        Icon(Icons.Default.ContentCopy, "Copy link")
                                    }
                                    IconButton(onClick = {
                                        val intent = Intent(Intent.ACTION_SEND).apply {
                                            type = "text/plain"
                                            putExtra(Intent.EXTRA_TEXT, "Join my group on FireStream: $inviteUrl")
                                        }
                                        context.startActivity(Intent.createChooser(intent, "Share invite link"))
                                    }) {
                                        Icon(Icons.Default.Share, "Share link")
                                    }
                                    IconButton(onClick = { showQrDialog = true }) {
                                        Icon(Icons.Default.QrCode, "Show QR code")
                                    }
                                    IconButton(onClick = { viewModel.revokeInviteLink() }) {
                                        Icon(Icons.Default.LinkOff, "Revoke link",
                                            tint = MaterialTheme.colorScheme.error)
                                    }
                                }
                            }
                        }
                    } else {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { viewModel.generateInviteLink() }
                                .padding(horizontal = 16.dp, vertical = 12.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(Icons.Default.Link, contentDescription = null,
                                tint = MaterialTheme.colorScheme.primary)
                            Spacer(modifier = Modifier.width(12.dp))
                            Text(
                                text = "Generate invite link",
                                style = MaterialTheme.typography.bodyLarge,
                                color = MaterialTheme.colorScheme.primary
                            )
                        }
                    }
                    Spacer(modifier = Modifier.height(8.dp))
                }

                // Require approval toggle
                item {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = "Require approval",
                                style = MaterialTheme.typography.bodyLarge
                            )
                            Text(
                                text = "New members need admin approval to join",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        Switch(
                            checked = chat.requireApproval,
                            onCheckedChange = { viewModel.setRequireApproval(it) }
                        )
                    }
                    Spacer(modifier = Modifier.height(8.dp))
                }
            }

            // Pending members (admin only, if any)
            if (isAdmin && uiState.pendingMembers.isNotEmpty()) {
                item { SectionHeader("Pending Approval (${uiState.pendingMembers.size})") }
                items(uiState.pendingMembers, key = { it.userId }) { member ->
                    PendingMemberRow(
                        member = member,
                        onApprove = { viewModel.approveMember(member.userId) },
                        onReject = { viewModel.rejectMember(member.userId) }
                    )
                }
                item { Spacer(modifier = Modifier.height(8.dp)) }
            }

            // Members list
            item {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    SectionHeader("Members (${uiState.members.size})", modifier = Modifier.weight(1f))
                    if (isAdmin) {
                        IconButton(onClick = onAddMemberClick) {
                            Icon(Icons.Default.PersonAdd, "Add member",
                                tint = MaterialTheme.colorScheme.primary)
                        }
                    }
                }
            }
            items(uiState.members, key = { it.userId }) { member ->
                MemberRow(
                    member = member,
                    isAdmin = isAdmin,
                    isSelf = member.userId == viewModel.uiState.value.chat?.createdBy,
                    onRemove = if (isAdmin && member.userId != viewModel.uiState.value.chat?.createdBy
                        && member.userId != (viewModel.uiState.value.chat?.participants?.firstOrNull()))
                    {
                        { showRemoveDialog = member.userId }
                    } else null
                )
            }

            // Leave group
            item {
                Spacer(modifier = Modifier.height(8.dp))
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { showLeaveDialog = true }
                        .padding(16.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        Icons.AutoMirrored.Filled.ExitToApp,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.error
                    )
                    Spacer(modifier = Modifier.width(12.dp))
                    Text(
                        text = "Leave group",
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.error
                    )
                }
                Spacer(modifier = Modifier.height(32.dp))
            }
        }
    }

    // QR code dialog
    if (showQrDialog && uiState.chat?.inviteLink != null) {
        val inviteUrl = "https://firestream.chat/join/${uiState.chat!!.inviteLink}"
        val qrBitmap = remember(inviteUrl) { QrCodeGenerator.generate(inviteUrl) }
        AlertDialog(
            onDismissRequest = { showQrDialog = false },
            title = { Text("Group QR Code") },
            text = {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Image(
                        bitmap = qrBitmap.asImageBitmap(),
                        contentDescription = "QR Code",
                        modifier = Modifier.size(256.dp)
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = "Scan to join group",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = { showQrDialog = false }) { Text("Close") }
            }
        )
    }

    // Leave group confirmation dialog
    if (showLeaveDialog) {
        AlertDialog(
            onDismissRequest = { showLeaveDialog = false },
            title = { Text("Leave Group") },
            text = { Text("Are you sure you want to leave this group? You won't be able to send or receive messages.") },
            confirmButton = {
                TextButton(onClick = {
                    viewModel.leaveGroup()
                    showLeaveDialog = false
                }) {
                    Text("Leave", color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                TextButton(onClick = { showLeaveDialog = false }) { Text("Cancel") }
            }
        )
    }

    // Remove member confirmation dialog
    showRemoveDialog?.let { userId ->
        val memberName = uiState.members.find { it.userId == userId }?.displayName ?: userId
        AlertDialog(
            onDismissRequest = { showRemoveDialog = null },
            title = { Text("Remove Member") },
            text = { Text("Remove $memberName from the group?") },
            confirmButton = {
                TextButton(onClick = {
                    viewModel.removeGroupMember(userId)
                    showRemoveDialog = null
                }) {
                    Text("Remove", color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                TextButton(onClick = { showRemoveDialog = null }) { Text("Cancel") }
            }
        )
    }

    // Fullscreen group avatar viewer
    val avatarUrl = uiState.chat?.avatarUrl
    BackHandler(enabled = fullscreenGroupAvatar) { fullscreenGroupAvatar = false }
    AnimatedVisibility(visible = fullscreenGroupAvatar && avatarUrl != null, enter = fadeIn(), exit = fadeOut()) {
        if (avatarUrl != null) {
            FullscreenImageViewer(imageUrl = avatarUrl, onDismiss = { fullscreenGroupAvatar = false })
        }
    }

    // Photo source picker dialog (admin only)
    if (showPhotoSourceDialog) {
        AlertDialog(
            onDismissRequest = { showPhotoSourceDialog = false },
            title = { Text("Group photo") },
            text = {
                Column {
                    TextButton(
                        onClick = {
                            showPhotoSourceDialog = false
                            if (ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA)
                                == PackageManager.PERMISSION_GRANTED
                            ) {
                                val uri = createGroupAvatarCameraUri(context)
                                cameraUri = uri
                                cameraLauncher.launch(uri)
                            } else {
                                cameraPermissionLauncher.launch(Manifest.permission.CAMERA)
                            }
                        },
                        modifier = Modifier.fillMaxWidth()
                    ) { Text("Take photo") }

                    TextButton(
                        onClick = {
                            showPhotoSourceDialog = false
                            galleryLauncher.launch(PickVisualMediaRequest(PickVisualMedia.ImageOnly))
                        },
                        modifier = Modifier.fillMaxWidth()
                    ) { Text("Choose from gallery") }
                }
            },
            confirmButton = {},
            dismissButton = {
                TextButton(onClick = { showPhotoSourceDialog = false }) { Text("Cancel") }
            }
        )
    }
}

@Composable
private fun EditableGroupField(
    displayValue: String,
    label: String,
    isEditable: Boolean,
    isSaving: Boolean,
    isEditing: Boolean,
    input: String,
    onInputChange: (String) -> Unit,
    onEditClick: () -> Unit,
    onSave: () -> Unit,
    onCancel: () -> Unit,
    modifier: Modifier = Modifier,
    saveEnabled: Boolean = true,
    displayTextStyle: TextStyle = MaterialTheme.typography.bodyLarge,
    displayFontWeight: FontWeight? = null,
    displayColor: Color = Color.Unspecified,
    rowModifier: Modifier = Modifier
) {
    if (isEditable && isEditing) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = modifier.padding(horizontal = 16.dp)
        ) {
            OutlinedTextField(
                value = input,
                onValueChange = onInputChange,
                singleLine = true,
                label = { Text(label) },
                enabled = !isSaving,
                modifier = Modifier.weight(1f)
            )
            IconButton(onClick = onSave, enabled = saveEnabled && !isSaving) {
                Icon(Icons.Default.Check, contentDescription = "Save $label")
            }
            IconButton(onClick = onCancel) {
                Icon(Icons.Default.Close, contentDescription = "Cancel")
            }
        }
    } else {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = rowModifier.then(modifier)
        ) {
            Text(
                text = displayValue,
                style = displayTextStyle,
                fontWeight = displayFontWeight,
                color = displayColor,
                modifier = Modifier.weight(1f)
            )
            if (isEditable) {
                IconButton(onClick = onEditClick) {
                    Icon(
                        Icons.Default.Edit,
                        contentDescription = "Edit $label",
                        modifier = Modifier.size(18.dp),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
    }
}

private fun createGroupAvatarCameraUri(context: Context): Uri {
    val cacheDir = File(context.cacheDir, "camera").also { it.mkdirs() }
    val file = File(cacheDir, "group_${System.currentTimeMillis()}.jpg")
    return FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
}

@Composable
private fun SectionHeader(title: String, modifier: Modifier = Modifier) {
    Text(
        text = title,
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.primary,
        fontWeight = FontWeight.Bold,
        modifier = modifier.padding(horizontal = 16.dp, vertical = 8.dp)
    )
}

@Composable
private fun MemberRow(
    member: MemberInfo,
    isAdmin: Boolean,
    isSelf: Boolean,
    onRemove: (() -> Unit)?
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            modifier = Modifier
                .size(40.dp)
                .clip(CircleShape)
                .background(MaterialTheme.colorScheme.secondaryContainer),
            contentAlignment = Alignment.Center
        ) {
            Text(
                text = member.displayName.take(1).uppercase(),
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.onSecondaryContainer
            )
        }
        Spacer(modifier = Modifier.width(12.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = member.displayName,
                style = MaterialTheme.typography.bodyLarge
            )
        }
        if (member.role != GroupRole.MEMBER) {
            Text(
                text = member.role.name.lowercase().replaceFirstChar { it.uppercase() },
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier
                    .background(
                        MaterialTheme.colorScheme.primaryContainer,
                        RoundedCornerShape(4.dp)
                    )
                    .padding(horizontal = 6.dp, vertical = 2.dp)
            )
        }
        if (onRemove != null) {
            Spacer(modifier = Modifier.width(8.dp))
            IconButton(onClick = onRemove, modifier = Modifier.size(32.dp)) {
                Icon(
                    Icons.Default.PersonRemove,
                    contentDescription = "Remove",
                    tint = MaterialTheme.colorScheme.error,
                    modifier = Modifier.size(18.dp)
                )
            }
        }
    }
}

@Composable
private fun PendingMemberRow(
    member: MemberInfo,
    onApprove: () -> Unit,
    onReject: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            modifier = Modifier
                .size(40.dp)
                .clip(CircleShape)
                .background(MaterialTheme.colorScheme.tertiaryContainer),
            contentAlignment = Alignment.Center
        ) {
            Text(
                text = member.displayName.take(1).uppercase(),
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.onTertiaryContainer
            )
        }
        Spacer(modifier = Modifier.width(12.dp))
        Text(
            text = member.displayName,
            style = MaterialTheme.typography.bodyLarge,
            modifier = Modifier.weight(1f)
        )
        IconButton(onClick = onApprove, modifier = Modifier.size(32.dp)) {
            Icon(
                Icons.Default.Check,
                contentDescription = "Approve",
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(20.dp)
            )
        }
        IconButton(onClick = onReject, modifier = Modifier.size(32.dp)) {
            Icon(
                Icons.Default.Close,
                contentDescription = "Reject",
                tint = MaterialTheme.colorScheme.error,
                modifier = Modifier.size(20.dp)
            )
        }
    }
}
