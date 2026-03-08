package com.firestream.chat.ui.profile

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
import androidx.compose.material.icons.filled.Circle
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
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import coil.compose.AsyncImage
import com.firestream.chat.domain.model.Message
import com.firestream.chat.ui.theme.OnlineGreen
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
    var showBlockDialog by remember { mutableStateOf(false) }

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
                        AsyncImage(
                            model = user.avatarUrl,
                            contentDescription = "Avatar",
                            modifier = Modifier
                                .size(96.dp)
                                .clip(CircleShape)
                        )
                        if (user.isOnline) {
                            Icon(
                                imageVector = Icons.Default.Circle,
                                contentDescription = "Online",
                                tint = OnlineGreen,
                                modifier = Modifier
                                    .size(20.dp)
                                    .align(Alignment.BottomEnd)
                            )
                        }
                    }

                    Spacer(Modifier.height(12.dp))

                    Text(
                        text = user.displayName,
                        style = MaterialTheme.typography.headlineSmall,
                        fontWeight = FontWeight.Bold
                    )

                    Text(
                        text = user.statusText,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 4.dp, bottom = 8.dp)
                    )

                    if (!user.isOnline && user.lastSeen > 0) {
                        val formatted = SimpleDateFormat("MMM d, h:mm a", Locale.getDefault())
                            .format(Date(user.lastSeen))
                        Text(
                            text = "Last seen $formatted",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
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
