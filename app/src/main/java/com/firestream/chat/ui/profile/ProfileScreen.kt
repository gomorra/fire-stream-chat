package com.firestream.chat.ui.profile

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.result.contract.ActivityResultContracts.PickVisualMedia
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Block
import androidx.compose.material.icons.filled.CameraAlt
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Circle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Phone
import androidx.compose.material.icons.filled.PhotoLibrary
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import androidx.hilt.navigation.compose.hiltViewModel
import coil.compose.AsyncImage
import com.firestream.chat.domain.model.Message
import com.firestream.chat.ui.chat.FullscreenImageViewer
import com.firestream.chat.ui.components.resolveAvatarModel
import com.firestream.chat.ui.theme.OnlineGreen
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ProfileScreen(
    onBackClick: () -> Unit,
    viewModel: ProfileViewModel = hiltViewModel()
) {
    val uiState by viewModel.uiState.collectAsState()
    val context = LocalContext.current

    var showBlockDialog by remember { mutableStateOf(false) }
    var showPhotoSourceDialog by remember { mutableStateOf(false) }
    var fullscreenAvatar by remember { mutableStateOf(false) }
    var cameraUri by remember { mutableStateOf<Uri?>(null) }
    var editingName by remember { mutableStateOf(false) }
    var nameInput by remember { mutableStateOf("") }
    var editingAbout by remember { mutableStateOf(false) }
    var aboutInput by remember { mutableStateOf("") }

    val galleryLauncher = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        uri?.let { viewModel.uploadAvatar(it) }
    }
    val cameraLauncher = rememberLauncherForActivityResult(ActivityResultContracts.TakePicture()) { success ->
        if (success) cameraUri?.let { viewModel.uploadAvatar(it) }
    }
    val cameraPermissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) {
            val uri = createAvatarCameraUri(context)
            cameraUri = uri
            cameraLauncher.launch(uri)
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(if (uiState.isCurrentUser) "My Profile" else "Profile") },
                navigationIcon = {
                    IconButton(onClick = onBackClick) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
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
        when {
            uiState.isLoading -> {
                Box(
                    modifier = Modifier.fillMaxSize().padding(padding),
                    contentAlignment = Alignment.Center
                ) { CircularProgressIndicator() }
            }
            uiState.user != null -> {
                val user = uiState.user!!
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(padding)
                        .verticalScroll(rememberScrollState()),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Spacer(Modifier.height(24.dp))

                    Box {
                        // Avatar — clickable for fullscreen when a photo exists
                        Box(
                            modifier = Modifier
                                .size(96.dp)
                                .clip(CircleShape)
                                .clickable(enabled = user.avatarUrl != null || user.localAvatarPath != null) { fullscreenAvatar = true },
                            contentAlignment = Alignment.Center
                        ) {
                            val avatarModel = remember(user.localAvatarPath, user.avatarUrl) {
                                resolveAvatarModel(user.localAvatarPath, user.avatarUrl)
                            }
                            if (avatarModel != null) {
                                AsyncImage(
                                    model = avatarModel,
                                    contentDescription = "Avatar",
                                    contentScale = androidx.compose.ui.layout.ContentScale.Crop,
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
                                        text = user.displayName.take(1).uppercase(),
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

                        // Online indicator (non-current-user only — edit badge occupies that spot)
                        if (user.isOnline && !uiState.isCurrentUser) {
                            Icon(
                                imageVector = Icons.Default.Circle,
                                contentDescription = "Online",
                                tint = OnlineGreen,
                                modifier = Modifier
                                    .size(20.dp)
                                    .align(Alignment.BottomEnd)
                            )
                        }

                        // Edit badge (current user only)
                        if (uiState.isCurrentUser) {
                            Box(
                                modifier = Modifier
                                    .size(28.dp)
                                    .align(Alignment.BottomEnd)
                                    .background(MaterialTheme.colorScheme.primary, CircleShape)
                                    .clickable(enabled = !uiState.isUploading) { showPhotoSourceDialog = true },
                                contentAlignment = Alignment.Center
                            ) {
                                Icon(
                                    imageVector = Icons.Default.CameraAlt,
                                    contentDescription = "Change photo",
                                    tint = MaterialTheme.colorScheme.onPrimary,
                                    modifier = Modifier.size(16.dp)
                                )
                            }
                        }
                    }

                    Spacer(Modifier.height(12.dp))

                    EditableProfileField(
                        displayValue = user.displayName,
                        label = "Name",
                        isEditable = uiState.isCurrentUser,
                        isSaving = uiState.isSavingProfile,
                        isEditing = editingName,
                        input = nameInput,
                        onInputChange = { nameInput = it },
                        onEditClick = { nameInput = user.displayName; editingName = true },
                        onSave = { viewModel.updateDisplayName(nameInput); editingName = false },
                        onCancel = { editingName = false },
                        saveEnabled = nameInput.isNotBlank(),
                        displayTextStyle = MaterialTheme.typography.headlineSmall,
                        displayFontWeight = FontWeight.Bold,
                        iconSize = 18.dp
                    )

                    EditableProfileField(
                        displayValue = user.statusText,
                        label = "About",
                        isEditable = uiState.isCurrentUser,
                        isSaving = uiState.isSavingProfile,
                        isEditing = editingAbout,
                        input = aboutInput,
                        onInputChange = { aboutInput = it },
                        onEditClick = { aboutInput = user.statusText; editingAbout = true },
                        onSave = { viewModel.updateStatusText(aboutInput); editingAbout = false },
                        onCancel = { editingAbout = false },
                        displayTextStyle = MaterialTheme.typography.bodyMedium,
                        displayColor = MaterialTheme.colorScheme.onSurfaceVariant,
                        rowModifier = Modifier.padding(top = 4.dp, bottom = 8.dp),
                        iconSize = 16.dp
                    )

                    // Profile save error
                    if (uiState.profileSaveError != null) {
                        Text(
                            text = uiState.profileSaveError!!,
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.error,
                            modifier = Modifier.padding(bottom = 4.dp)
                        )
                    }

                    if (!user.isOnline && user.lastSeen > 0) {
                        val formatted = SimpleDateFormat("MMM d, h:mm a", Locale.getDefault())
                            .format(Date(user.lastSeen))
                        Text(
                            text = "Last seen $formatted",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }

                    // Upload error
                    if (uiState.uploadError != null) {
                        Text(
                            text = uiState.uploadError!!,
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.error,
                            modifier = Modifier.padding(top = 4.dp)
                        )
                    }

                    Spacer(Modifier.height(24.dp))
                    HorizontalDivider()

                    // Phone number row
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            imageVector = Icons.Default.Phone,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary
                        )
                        Spacer(Modifier.width(16.dp))
                        Column {
                            Text(
                                text = user.phoneNumber,
                                style = MaterialTheme.typography.bodyLarge
                            )
                            Text(
                                text = "Phone",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }

                    HorizontalDivider()

                    // Block/Unblock button (only for other users)
                    if (!uiState.isCurrentUser) {
                        Spacer(Modifier.height(16.dp))

                        if (uiState.isBlocked) {
                            OutlinedButton(
                                onClick = { showBlockDialog = true },
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 16.dp),
                                enabled = !uiState.isBlockLoading
                            ) {
                                if (uiState.isBlockLoading) {
                                    CircularProgressIndicator(modifier = Modifier.size(16.dp))
                                    Spacer(Modifier.width(8.dp))
                                }
                                Icon(Icons.Default.Block, contentDescription = null)
                                Spacer(Modifier.width(8.dp))
                                Text("Unblock User")
                            }
                        } else {
                            Button(
                                onClick = { showBlockDialog = true },
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 16.dp),
                                colors = ButtonDefaults.buttonColors(
                                    containerColor = MaterialTheme.colorScheme.error
                                ),
                                enabled = !uiState.isBlockLoading
                            ) {
                                if (uiState.isBlockLoading) {
                                    CircularProgressIndicator(
                                        modifier = Modifier.size(16.dp),
                                        color = MaterialTheme.colorScheme.onError
                                    )
                                    Spacer(Modifier.width(8.dp))
                                }
                                Icon(Icons.Default.Block, contentDescription = null)
                                Spacer(Modifier.width(8.dp))
                                Text("Block User")
                            }
                        }

                        Spacer(Modifier.height(16.dp))
                        HorizontalDivider()
                    }

                    // Shared Media section
                    Spacer(Modifier.height(16.dp))

                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            imageVector = Icons.Default.PhotoLibrary,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary
                        )
                        Spacer(Modifier.width(12.dp))
                        Text(
                            text = "Shared Media",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.SemiBold
                        )
                        Spacer(Modifier.weight(1f))
                        Text(
                            text = "${uiState.sharedMedia.size} items",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }

                    Spacer(Modifier.height(8.dp))

                    if (uiState.sharedMedia.isEmpty()) {
                        Text(
                            text = "No shared media yet",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            textAlign = TextAlign.Center,
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 24.dp)
                        )
                    } else {
                        SharedMediaGrid(
                            media = uiState.sharedMedia,
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 16.dp)
                        )
                    }

                    Spacer(Modifier.height(24.dp))
                }
            }
            uiState.error != null -> {
                Box(
                    modifier = Modifier.fillMaxSize().padding(padding),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = uiState.error ?: "Error loading profile",
                        color = MaterialTheme.colorScheme.error
                    )
                }
            }
        }
    }

    // Fullscreen avatar viewer
    val avatarUrl = uiState.user?.avatarUrl
    val localAvatarPath = uiState.user?.localAvatarPath
    BackHandler(enabled = fullscreenAvatar) { fullscreenAvatar = false }
    AnimatedVisibility(visible = fullscreenAvatar && (avatarUrl != null || localAvatarPath != null), enter = fadeIn(), exit = fadeOut()) {
        if (avatarUrl != null || localAvatarPath != null) {
            FullscreenImageViewer(
                imageUrl = avatarUrl ?: "",
                localUri = localAvatarPath,
                onDismiss = { fullscreenAvatar = false }
            )
        }
    }

    // Photo source picker dialog (current user only)
    if (showPhotoSourceDialog) {
        AlertDialog(
            onDismissRequest = { showPhotoSourceDialog = false },
            title = { Text("Profile photo") },
            text = {
                Column {
                    TextButton(
                        onClick = {
                            showPhotoSourceDialog = false
                            if (ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA)
                                == PackageManager.PERMISSION_GRANTED
                            ) {
                                val uri = createAvatarCameraUri(context)
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

    // Block/Unblock confirmation dialog
    if (showBlockDialog) {
        val isBlocked = uiState.isBlocked
        val userName = uiState.user?.displayName ?: "this user"
        AlertDialog(
            onDismissRequest = { showBlockDialog = false },
            title = { Text(if (isBlocked) "Unblock User" else "Block User") },
            text = {
                Text(
                    if (isBlocked)
                        "Are you sure you want to unblock $userName? They will be able to send you messages again."
                    else
                        "Are you sure you want to block $userName? They won't be able to send you messages."
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        if (isBlocked) viewModel.unblockUser() else viewModel.blockUser()
                        showBlockDialog = false
                    }
                ) {
                    Text(
                        text = if (isBlocked) "Unblock" else "Block",
                        color = if (isBlocked) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error
                    )
                }
            },
            dismissButton = {
                TextButton(onClick = { showBlockDialog = false }) {
                    Text("Cancel")
                }
            }
        )
    }
}

@Composable
private fun EditableProfileField(
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
    displayTextStyle: TextStyle = MaterialTheme.typography.bodyMedium,
    displayFontWeight: FontWeight? = null,
    displayColor: Color = Color.Unspecified,
    rowModifier: Modifier = Modifier,
    iconSize: Dp = 18.dp
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
        Row(verticalAlignment = Alignment.CenterVertically, modifier = rowModifier.then(modifier)) {
            Text(
                text = displayValue,
                style = displayTextStyle,
                fontWeight = displayFontWeight,
                color = displayColor
            )
            if (isEditable) {
                IconButton(onClick = onEditClick) {
                    Icon(
                        Icons.Default.Edit,
                        contentDescription = "Edit $label",
                        modifier = Modifier.size(iconSize),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
    }
}

@Composable
private fun SharedMediaGrid(
    media: List<Message>,
    modifier: Modifier = Modifier
) {
    val rows = media.chunked(3)
    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(4.dp)) {
        rows.forEach { rowItems ->
            Row(
                horizontalArrangement = Arrangement.spacedBy(4.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                rowItems.forEach { message ->
                    AsyncImage(
                        model = message.mediaThumbnailUrl ?: message.mediaUrl,
                        contentDescription = "Shared media",
                        contentScale = ContentScale.Crop,
                        modifier = Modifier
                            .weight(1f)
                            .aspectRatio(1f)
                            .clip(RoundedCornerShape(4.dp))
                    )
                }
                repeat(3 - rowItems.size) {
                    Spacer(Modifier.weight(1f))
                }
            }
        }
    }
}

private fun createAvatarCameraUri(context: Context): Uri {
    val cacheDir = File(context.cacheDir, "camera").also { it.mkdirs() }
    val file = File(cacheDir, "avatar_${System.currentTimeMillis()}.jpg")
    return FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
}
