package com.firestream.chat.ui.settings

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.ExitToApp
import androidx.compose.material.icons.automirrored.filled.HelpOutline
import androidx.compose.material.icons.filled.Archive
import androidx.compose.material.icons.filled.Bookmark
import androidx.compose.material.icons.filled.DarkMode
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Email
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.LightMode
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.AlternateEmail
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.NotificationsActive
import androidx.compose.material.icons.filled.RemoveRedEye
import androidx.compose.material.icons.filled.ScreenLockPortrait
import androidx.compose.material.icons.filled.Security
import androidx.compose.material.icons.filled.SettingsBrightness
import androidx.compose.material.icons.filled.Storage
import androidx.compose.material.icons.filled.Vibration
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
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
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import coil.compose.AsyncImage
import com.firestream.chat.data.local.AppTheme
import com.firestream.chat.data.local.AutoDownloadOption
import com.firestream.chat.data.local.NotificationSound

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    onBackClick: () -> Unit,
    onStarredMessagesClick: () -> Unit,
    onArchivedChatsClick: () -> Unit = {},
    onProfileClick: (userId: String) -> Unit,
    onSignedOut: () -> Unit = {},
    viewModel: SettingsViewModel = hiltViewModel()
) {
    val uiState by viewModel.uiState.collectAsState()
    var showThemePicker by remember { mutableStateOf(false) }
    var showSignOutDialog by remember { mutableStateOf(false) }
    var showSoundPicker by remember { mutableStateOf(false) }
    var showAutoDownloadPicker by remember { mutableStateOf(false) }
    var showClearCacheDialog by remember { mutableStateOf(false) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Settings") },
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
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
        ) {
            // Account section
            uiState.currentUser?.let { user ->
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { onProfileClick(user.uid) }
                        .padding(16.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    AsyncImage(
                        model = user.avatarUrl,
                        contentDescription = "Avatar",
                        modifier = Modifier
                            .size(56.dp)
                            .clip(CircleShape)
                    )
                    Spacer(Modifier.width(16.dp))
                    Column {
                        Text(
                            text = user.displayName,
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.SemiBold
                        )
                        Text(
                            text = user.statusText,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
                HorizontalDivider()
            }

            Spacer(Modifier.height(8.dp))
            SectionHeader("Chats")

            SettingsItem(
                icon = Icons.Default.Bookmark,
                title = "Starred Messages",
                subtitle = "Messages you've starred for quick reference",
                onClick = onStarredMessagesClick
            )

            SettingsItem(
                icon = Icons.Default.Archive,
                title = "Archived Chats",
                subtitle = "Chats you've archived",
                onClick = onArchivedChatsClick
            )

            Spacer(Modifier.height(8.dp))
            SectionHeader("Appearance")

            val themeLabel = when (uiState.appTheme) {
                AppTheme.SYSTEM -> "System default"
                AppTheme.LIGHT -> "Light"
                AppTheme.DARK -> "Dark"
            }
            val themeIcon = when (uiState.appTheme) {
                AppTheme.SYSTEM -> Icons.Default.SettingsBrightness
                AppTheme.LIGHT -> Icons.Default.LightMode
                AppTheme.DARK -> Icons.Default.DarkMode
            }
            SettingsItem(
                icon = themeIcon,
                title = "Theme",
                subtitle = themeLabel,
                onClick = { showThemePicker = true }
            )

            // Privacy section
            Spacer(Modifier.height(8.dp))
            SectionHeader("Privacy")

            SettingsToggleItem(
                icon = Icons.Default.RemoveRedEye,
                title = "Read Receipts",
                subtitle = "Show when you've read messages",
                checked = uiState.readReceipts,
                onCheckedChange = { viewModel.setReadReceipts(it) }
            )

            SettingsToggleItem(
                icon = Icons.Default.Visibility,
                title = "Last Seen",
                subtitle = "Show your last seen status to others",
                checked = uiState.lastSeenVisible,
                onCheckedChange = { viewModel.setLastSeenVisible(it) }
            )

            SettingsToggleItem(
                icon = Icons.Default.ScreenLockPortrait,
                title = "Screen Security",
                subtitle = "Prevent screenshots in the app",
                checked = uiState.screenSecurity,
                onCheckedChange = { viewModel.setScreenSecurity(it) }
            )

            // Notifications section
            Spacer(Modifier.height(8.dp))
            SectionHeader("Notifications")

            SettingsToggleItem(
                icon = Icons.Default.Notifications,
                title = "Message Notifications",
                subtitle = "Receive notifications for new messages",
                checked = uiState.messageNotifications,
                onCheckedChange = { viewModel.setMessageNotifications(it) }
            )

            SettingsToggleItem(
                icon = Icons.Default.NotificationsActive,
                title = "Group Notifications",
                subtitle = "Receive notifications for group messages",
                checked = uiState.groupNotifications,
                onCheckedChange = { viewModel.setGroupNotifications(it) }
            )

            SettingsToggleItem(
                icon = Icons.Default.AlternateEmail,
                title = "Mention-only Notifications",
                subtitle = "Only notify for group messages that mention you",
                checked = uiState.mentionOnlyNotifications,
                onCheckedChange = { viewModel.setMentionOnlyNotifications(it) }
            )

            val soundLabel = when (uiState.notificationSound) {
                NotificationSound.DEFAULT -> "Default"
                NotificationSound.SILENT -> "Silent"
            }
            SettingsItem(
                icon = Icons.Default.MusicNote,
                title = "Notification Sound",
                subtitle = soundLabel,
                onClick = { showSoundPicker = true }
            )

            SettingsToggleItem(
                icon = Icons.Default.Vibration,
                title = "Vibration",
                subtitle = "Vibrate on new notifications",
                checked = uiState.vibration,
                onCheckedChange = { viewModel.setVibration(it) }
            )

            // Storage section
            Spacer(Modifier.height(8.dp))
            SectionHeader("Storage")

            SettingsItem(
                icon = Icons.Default.Storage,
                title = "Storage Used",
                subtitle = formatCacheSize(uiState.cacheSize),
                onClick = { }
            )

            SettingsItem(
                icon = Icons.Default.Delete,
                title = "Clear Cache",
                subtitle = "Free up space by clearing cached media",
                onClick = { showClearCacheDialog = true }
            )

            val autoDownloadLabel = when (uiState.autoDownload) {
                AutoDownloadOption.WIFI_ONLY -> "WiFi only"
                AutoDownloadOption.ALWAYS -> "Always"
                AutoDownloadOption.NEVER -> "Never"
            }
            SettingsItem(
                icon = Icons.Default.Download,
                title = "Auto-download Media",
                subtitle = autoDownloadLabel,
                onClick = { showAutoDownloadPicker = true }
            )

            // Help section
            Spacer(Modifier.height(8.dp))
            SectionHeader("Help")

            SettingsItem(
                icon = Icons.Default.Info,
                title = "App Version",
                subtitle = "1.0.0",
                onClick = { }
            )

            SettingsItem(
                icon = Icons.Default.Description,
                title = "Terms of Service",
                subtitle = "Read our terms of service",
                onClick = { /* Placeholder */ }
            )

            SettingsItem(
                icon = Icons.Default.Security,
                title = "Privacy Policy",
                subtitle = "Read our privacy policy",
                onClick = { /* Placeholder */ }
            )

            SettingsItem(
                icon = Icons.Default.Email,
                title = "Contact Support",
                subtitle = "Get help from our support team",
                onClick = { /* Placeholder */ }
            )

            // Account section
            Spacer(Modifier.height(8.dp))
            SectionHeader("Account")

            SettingsItem(
                icon = Icons.AutoMirrored.Filled.ExitToApp,
                title = "Sign Out",
                subtitle = "Sign out of your account",
                onClick = { showSignOutDialog = true },
                tintError = true
            )

            Spacer(Modifier.height(16.dp))
        }
    }

    if (showThemePicker) {
        ThemePickerDialog(
            currentTheme = uiState.appTheme,
            onSelect = { theme ->
                viewModel.setTheme(theme)
                showThemePicker = false
            },
            onDismiss = { showThemePicker = false }
        )
    }

    if (showSoundPicker) {
        SoundPickerDialog(
            currentSound = uiState.notificationSound,
            onSelect = { sound ->
                viewModel.setNotificationSound(sound)
                showSoundPicker = false
            },
            onDismiss = { showSoundPicker = false }
        )
    }

    if (showAutoDownloadPicker) {
        AutoDownloadPickerDialog(
            currentOption = uiState.autoDownload,
            onSelect = { option ->
                viewModel.setAutoDownload(option)
                showAutoDownloadPicker = false
            },
            onDismiss = { showAutoDownloadPicker = false }
        )
    }

    if (showClearCacheDialog) {
        AlertDialog(
            onDismissRequest = { showClearCacheDialog = false },
            title = { Text("Clear Cache") },
            text = { Text("Are you sure you want to clear the media cache? This will free up ${formatCacheSize(uiState.cacheSize)} of storage.") },
            confirmButton = {
                TextButton(onClick = {
                    viewModel.clearCache()
                    showClearCacheDialog = false
                }) {
                    Text("Clear")
                }
            },
            dismissButton = {
                TextButton(onClick = { showClearCacheDialog = false }) {
                    Text("Cancel")
                }
            }
        )
    }

    if (showSignOutDialog) {
        AlertDialog(
            onDismissRequest = { showSignOutDialog = false },
            title = { Text("Sign Out") },
            text = { Text("Are you sure you want to sign out?") },
            confirmButton = {
                TextButton(onClick = {
                    viewModel.signOut()
                    showSignOutDialog = false
                    onSignedOut()
                }) {
                    Text("Sign Out", color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                TextButton(onClick = { showSignOutDialog = false }) {
                    Text("Cancel")
                }
            }
        )
    }
}

@Composable
private fun SectionHeader(title: String) {
    Text(
        text = title,
        style = MaterialTheme.typography.labelMedium,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp)
    )
}

@Composable
private fun SettingsItem(
    icon: ImageVector,
    title: String,
    subtitle: String,
    onClick: () -> Unit,
    tintError: Boolean = false
) {
    val tint = if (tintError) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface
    ListItem(
        headlineContent = {
            Text(title, color = if (tintError) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface)
        },
        supportingContent = { Text(subtitle, style = MaterialTheme.typography.bodySmall) },
        leadingContent = { Icon(icon, contentDescription = null, tint = tint) },
        modifier = Modifier.clickable(onClick = onClick)
    )
}

@Composable
private fun SettingsToggleItem(
    icon: ImageVector,
    title: String,
    subtitle: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit
) {
    ListItem(
        headlineContent = { Text(title) },
        supportingContent = { Text(subtitle, style = MaterialTheme.typography.bodySmall) },
        leadingContent = { Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.onSurface) },
        trailingContent = {
            Switch(checked = checked, onCheckedChange = onCheckedChange)
        },
        modifier = Modifier.clickable { onCheckedChange(!checked) }
    )
}

@Composable
private fun ThemePickerDialog(
    currentTheme: AppTheme,
    onSelect: (AppTheme) -> Unit,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Choose theme") },
        text = {
            Column {
                AppTheme.entries.forEach { theme ->
                    val label = when (theme) {
                        AppTheme.SYSTEM -> "System default"
                        AppTheme.LIGHT -> "Light"
                        AppTheme.DARK -> "Dark"
                    }
                    TextButton(
                        onClick = { onSelect(theme) },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text(
                            text = label,
                            color = if (theme == currentTheme) MaterialTheme.colorScheme.primary
                            else MaterialTheme.colorScheme.onSurface
                        )
                    }
                }
            }
        },
        confirmButton = {},
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
        }
    )
}

@Composable
private fun SoundPickerDialog(
    currentSound: NotificationSound,
    onSelect: (NotificationSound) -> Unit,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Notification Sound") },
        text = {
            Column {
                NotificationSound.entries.forEach { sound ->
                    val label = when (sound) {
                        NotificationSound.DEFAULT -> "Default"
                        NotificationSound.SILENT -> "Silent"
                    }
                    TextButton(
                        onClick = { onSelect(sound) },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text(
                            text = label,
                            color = if (sound == currentSound) MaterialTheme.colorScheme.primary
                            else MaterialTheme.colorScheme.onSurface
                        )
                    }
                }
            }
        },
        confirmButton = {},
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
        }
    )
}

@Composable
private fun AutoDownloadPickerDialog(
    currentOption: AutoDownloadOption,
    onSelect: (AutoDownloadOption) -> Unit,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Auto-download Media") },
        text = {
            Column {
                AutoDownloadOption.entries.forEach { option ->
                    val label = when (option) {
                        AutoDownloadOption.WIFI_ONLY -> "WiFi only"
                        AutoDownloadOption.ALWAYS -> "Always"
                        AutoDownloadOption.NEVER -> "Never"
                    }
                    TextButton(
                        onClick = { onSelect(option) },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text(
                            text = label,
                            color = if (option == currentOption) MaterialTheme.colorScheme.primary
                            else MaterialTheme.colorScheme.onSurface
                        )
                    }
                }
            }
        },
        confirmButton = {},
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
        }
    )
}

private fun formatCacheSize(bytes: Long): String {
    return when {
        bytes < 1024 -> "$bytes B"
        bytes < 1024 * 1024 -> "%.1f KB".format(bytes / 1024.0)
        bytes < 1024 * 1024 * 1024 -> "%.1f MB".format(bytes / (1024.0 * 1024.0))
        else -> "%.1f GB".format(bytes / (1024.0 * 1024.0 * 1024.0))
    }
}
