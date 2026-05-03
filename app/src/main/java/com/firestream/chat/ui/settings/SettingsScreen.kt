package com.firestream.chat.ui.settings

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.os.Build
import android.widget.Toast
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.DarkMode
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Hd
import androidx.compose.material.icons.filled.Email
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.LightMode
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.AlternateEmail
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.automirrored.filled.OpenInNew
import androidx.compose.material.icons.filled.NotificationsActive
import androidx.compose.material.icons.filled.RemoveRedEye
import androidx.compose.material.icons.filled.Replay
import androidx.compose.material.icons.filled.ScreenLockPortrait
import androidx.compose.material.icons.filled.Security
import androidx.compose.material.icons.filled.SettingsBrightness
import androidx.compose.material.icons.filled.Storage
import androidx.compose.material.icons.filled.SystemUpdate
import androidx.compose.material.icons.filled.Vibration
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
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
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import coil.compose.AsyncImage
import com.firestream.chat.BuildConfig
import com.firestream.chat.data.local.AppTheme
import com.firestream.chat.data.local.AutoDownloadOption
import com.firestream.chat.data.local.DictationLanguage
import com.firestream.chat.data.local.NotificationSound
import com.firestream.chat.data.util.ChangelogParser
import com.firestream.chat.data.util.ChangelogVersion
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

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
    var showDictationLanguagePicker by remember { mutableStateOf(false) }
    var showClearCacheDialog by remember { mutableStateOf(false) }
    var showDisableEncryptionDialog by remember { mutableStateOf(false) }
    var showBuildInfo by remember { mutableStateOf(false) }
    // Ephemeral confirm dialog for cancelling an in-flight APK download. Lives
    // here rather than in SettingsUiState because it's pure UI intent and
    // doesn't need to survive process death.
    var showCancelConfirm by remember { mutableStateOf(false) }
    val appContext = LocalContext.current.applicationContext
    val lifecycleOwner = androidx.compose.ui.platform.LocalLifecycleOwner.current

    // Re-check "Install unknown apps" permission when the user returns from
    // the system settings screen. Without this the row stays stuck on
    // "Allow installs…" until the user leaves and re-enters Settings.
    androidx.compose.runtime.DisposableEffect(lifecycleOwner) {
        val observer = androidx.lifecycle.LifecycleEventObserver { _, event ->
            if (event == androidx.lifecycle.Lifecycle.Event.ON_RESUME) {
                viewModel.recheckInstallPermission()
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

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
                    containerColor = MaterialTheme.colorScheme.background,
                    titleContentColor = MaterialTheme.colorScheme.onBackground,
                    navigationIconContentColor = MaterialTheme.colorScheme.onBackground
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

            // Hidden in debug — debug builds always send plaintext regardless of this toggle.
            if (!BuildConfig.DEBUG) {
                SettingsToggleItem(
                    icon = Icons.Default.Lock,
                    title = "End-to-End Encryption",
                    subtitle = if (uiState.e2eEncryption) {
                        "Encrypt 1:1 messages with Signal Protocol (recommended)"
                    } else {
                        "Off — outgoing 1:1 messages are sent as plaintext"
                    },
                    checked = uiState.e2eEncryption,
                    onCheckedChange = { enabled ->
                        if (enabled) viewModel.setE2eEncryption(true)
                        else showDisableEncryptionDialog = true
                    }
                )
            }

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

            // Chat section
            Spacer(Modifier.height(8.dp))
            SectionHeader("Chat")

            val dictationLanguageLabel = when (uiState.dictationLanguage) {
                DictationLanguage.GERMAN -> "German (de-DE)"
                DictationLanguage.ENGLISH -> "English (en-US)"
            }
            SettingsItem(
                icon = Icons.Default.Mic,
                title = "Dictation Language",
                subtitle = dictationLanguageLabel,
                onClick = { showDictationLanguagePicker = true }
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

            SettingsToggleItem(
                icon = Icons.Default.Hd,
                title = "Send Images in Full Quality",
                subtitle = "Send images without compression (larger file size)",
                checked = uiState.sendImagesFullQuality,
                onCheckedChange = { viewModel.setSendImagesFullQuality(it) }
            )

            // Download All Media button / progress
            if (uiState.mediaBackfillRunning && uiState.mediaBackfillProgress != null) {
                val progress = uiState.mediaBackfillProgress!!
                ListItem(
                    headlineContent = { Text("Downloading Media...") },
                    supportingContent = {
                        Column {
                            Text(
                                "${progress.first}/${progress.second}",
                                style = MaterialTheme.typography.bodySmall
                            )
                            Spacer(Modifier.height(4.dp))
                            LinearProgressIndicator(
                                progress = { progress.first.toFloat() / progress.second },
                                modifier = Modifier.fillMaxWidth()
                            )
                        }
                    },
                    leadingContent = {
                        Icon(
                            Icons.Default.Download,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onSurface
                        )
                    }
                )
            } else {
                ListItem(
                    headlineContent = { Text("Download All Media") },
                    supportingContent = {
                        Text(
                            "Download media from all chats",
                            style = MaterialTheme.typography.bodySmall
                        )
                    },
                    leadingContent = {
                        Icon(
                            Icons.Default.Download,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onSurface
                        )
                    },
                    modifier = Modifier.clickable { viewModel.startMediaBackfill() }
                )
            }

            // Help section
            Spacer(Modifier.height(8.dp))
            SectionHeader("Help")

            SettingsItem(
                icon = Icons.Default.Info,
                title = "App Version",
                subtitle = BuildConfig.VERSION_NAME + if (BuildConfig.DEBUG) " (debug build)" else "",
                onClick = { showBuildInfo = true },
                onLongClick = {
                    val clipboard = appContext.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                    clipboard.setPrimaryClip(ClipData.newPlainText("Build info", buildInfoPlaintext()))
                    // API 33+ shows a system clipboard confirmation; avoid the duplicate toast.
                    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
                        Toast.makeText(appContext, "Build info copied", Toast.LENGTH_SHORT).show()
                    }
                }
            )

            UpdateRow(
                state = uiState.update,
                onCheckForUpdate = viewModel::checkForUpdate,
                onShowAvailable = { /* handled by the Available dialog below */ },
                onRequestCancel = { showCancelConfirm = true },
                onInstall = viewModel::installNow,
                onRequestPermission = viewModel::requestInstallPermission
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

    if (showDictationLanguagePicker) {
        DictationLanguagePickerDialog(
            currentLanguage = uiState.dictationLanguage,
            onSelect = { language ->
                viewModel.setDictationLanguage(language)
                showDictationLanguagePicker = false
            },
            onDismiss = { showDictationLanguagePicker = false }
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

    if (showBuildInfo) {
        BuildInfoDialog(onDismiss = { showBuildInfo = false })
    }

    if (showDisableEncryptionDialog) {
        AlertDialog(
            onDismissRequest = { showDisableEncryptionDialog = false },
            title = { Text("Disable End-to-End Encryption?") },
            text = {
                Text(
                    "New 1:1 messages you send will be readable by anyone with access to the " +
                        "server. Messages already in your chats stay as they were sent. " +
                        "Group and broadcast messages were never encrypted."
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    viewModel.setE2eEncryption(false)
                    showDisableEncryptionDialog = false
                }) {
                    Text("Disable", color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                TextButton(onClick = { showDisableEncryptionDialog = false }) {
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

    // Modal dialogs survive only for two states now: Available (user must
    // confirm before kicking off the download) and Failed (a recap with an OK
    // dismiss). The Downloading state is rendered inline in the row so the
    // app stays usable during the download. ReadyToInstall and
    // NeedsInstallPermission are also row-only.
    when (val s = uiState.update) {
        is UpdateUiState.Available -> AlertDialog(
            onDismissRequest = { viewModel.dismissUpdateState() },
            title = { Text("Update available") },
            text = {
                Column {
                    Text("Version ${s.update.versionName} is ready to install.")
                    if (s.update.releaseNotes.isNotBlank()) {
                        Spacer(Modifier.height(8.dp))
                        Text(s.update.releaseNotes, style = MaterialTheme.typography.bodySmall)
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { viewModel.downloadAndInstall(s.update) }) {
                    Text("Update now")
                }
            },
            dismissButton = {
                TextButton(onClick = { viewModel.dismissUpdateState() }) {
                    Text("Later")
                }
            }
        )
        is UpdateUiState.Failed -> AlertDialog(
            onDismissRequest = { viewModel.dismissUpdateState() },
            title = { Text("Update failed") },
            text = { Text(s.message) },
            confirmButton = {
                TextButton(onClick = { viewModel.dismissUpdateState() }) {
                    Text("OK")
                }
            }
        )
        else -> Unit
    }

    if (showCancelConfirm) {
        AlertDialog(
            onDismissRequest = { showCancelConfirm = false },
            title = { Text("Cancel download?") },
            text = { Text("Partial progress will be kept. You can resume later.") },
            confirmButton = {
                TextButton(onClick = {
                    viewModel.cancelUpdateDownload()
                    showCancelConfirm = false
                }) {
                    Text("Cancel download")
                }
            },
            dismissButton = {
                TextButton(onClick = { showCancelConfirm = false }) {
                    Text("Keep downloading")
                }
            }
        )
    }
}

internal fun updateRowSubtitle(state: UpdateUiState): String = when (state) {
    UpdateUiState.Idle -> "Tap to check for a newer version"
    UpdateUiState.Checking -> "Checking…"
    UpdateUiState.UpToDate -> "You're on the latest version"
    is UpdateUiState.Available -> "Version ${state.update.versionName} ready to install"
    is UpdateUiState.Downloading -> {
        val mbDone = state.bytesDownloaded / 1024 / 1024
        if (state.totalBytes > 0) {
            val mbTotal = state.totalBytes / 1024 / 1024
            val pct = ((state.bytesDownloaded * 100) / state.totalBytes).toInt()
            "Downloading $mbDone MB / $mbTotal MB · $pct%"
        } else {
            "Downloading $mbDone MB…"
        }
    }
    is UpdateUiState.ReadyToInstall -> "Update ready — tap to install"
    is UpdateUiState.NeedsInstallPermission -> "Allow installs from FireStream to continue"
    is UpdateUiState.Failed -> "${state.message} — tap to retry"
}

/**
 * Settings row for "Check for updates". Renders inline progress + cancel ✕
 * during download, "Update ready — tap to install" when finished, and a
 * permission-prompt fallback when "Install unknown apps" is revoked. Replaces
 * the previous modal Downloading dialog so the user can keep using the app
 * during the download.
 */
@Composable
private fun UpdateRow(
    state: UpdateUiState,
    onCheckForUpdate: () -> Unit,
    onShowAvailable: () -> Unit,
    onRequestCancel: () -> Unit,
    onInstall: () -> Unit,
    onRequestPermission: () -> Unit
) {
    val rowOnClick: () -> Unit = when (state) {
        is UpdateUiState.Downloading -> ({})
        is UpdateUiState.Available -> onShowAvailable
        is UpdateUiState.ReadyToInstall -> onInstall
        is UpdateUiState.NeedsInstallPermission -> onRequestPermission
        else -> onCheckForUpdate
    }

    ListItem(
        headlineContent = { Text("Check for updates") },
        supportingContent = {
            Column {
                Text(
                    updateRowSubtitle(state),
                    style = MaterialTheme.typography.bodySmall
                )
                if (state is UpdateUiState.Downloading) {
                    Spacer(Modifier.height(6.dp))
                    if (state.totalBytes > 0) {
                        val progress = (state.bytesDownloaded.toFloat() / state.totalBytes)
                            .coerceIn(0f, 1f)
                        LinearProgressIndicator(
                            progress = { progress },
                            modifier = Modifier.fillMaxWidth()
                        )
                    } else {
                        // Indeterminate: total content-length not yet known.
                        LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                    }
                }
            }
        },
        leadingContent = {
            Icon(
                Icons.Default.SystemUpdate,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurface
            )
        },
        trailingContent = {
            when (state) {
                is UpdateUiState.Checking -> CircularProgressIndicator(
                    modifier = Modifier.size(20.dp),
                    strokeWidth = 2.dp
                )
                is UpdateUiState.Downloading -> IconButton(onClick = onRequestCancel) {
                    Icon(
                        Icons.Default.Close,
                        contentDescription = "Cancel download",
                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                is UpdateUiState.ReadyToInstall -> Icon(
                    Icons.Default.CheckCircle,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary
                )
                is UpdateUiState.NeedsInstallPermission -> Icon(
                    Icons.AutoMirrored.Filled.OpenInNew,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                )
                is UpdateUiState.Failed -> Icon(
                    Icons.Default.Replay,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.error
                )
                else -> {}
            }
        },
        modifier = Modifier.clickable(onClick = rowOnClick)
    )
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

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun SettingsItem(
    icon: ImageVector,
    title: String,
    subtitle: String,
    onClick: () -> Unit,
    tintError: Boolean = false,
    onLongClick: (() -> Unit)? = null
) {
    val tint = if (tintError) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface
    ListItem(
        headlineContent = {
            Text(title, color = if (tintError) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface)
        },
        supportingContent = { Text(subtitle, style = MaterialTheme.typography.bodySmall) },
        leadingContent = { Icon(icon, contentDescription = null, tint = tint) },
        modifier = Modifier.combinedClickable(onClick = onClick, onLongClick = onLongClick)
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

private fun buildInfoFields(): List<Pair<String, String>> = listOf(
    "Version" to BuildConfig.VERSION_NAME,
    "Build" to BuildConfig.VERSION_CODE.toString(),
    "Commit" to BuildConfig.GIT_SHA,
    "Committed" to BuildConfig.COMMIT_TIMESTAMP,
    "Type" to if (BuildConfig.DEBUG) "Debug" else "Release"
)

private fun buildInfoPlaintext(): String =
    buildInfoFields().joinToString("\n") { (label, value) -> "$label $value" }

@Composable
private fun BuildInfoDialog(onDismiss: () -> Unit) {
    val context = LocalContext.current
    val current by produceState<ChangelogVersion?>(initialValue = null) {
        value = withContext(Dispatchers.IO) {
            runCatching {
                ChangelogParser.selectCurrentVersion(
                    ChangelogParser.parse(ChangelogParser.loadFromAssets(context)),
                    BuildConfig.VERSION_NAME
                )
            }.getOrNull()
        }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = {
            TextButton(onClick = onDismiss) { Text("Close") }
        },
        title = { Text("Build info") },
        text = {
            Column(
                verticalArrangement = Arrangement.spacedBy(12.dp),
                modifier = Modifier.verticalScroll(rememberScrollState())
            ) {
                buildInfoFields().forEach { (label, value) ->
                    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        Text(
                            text = label,
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Text(
                            text = value,
                            style = MaterialTheme.typography.bodyMedium,
                            fontFamily = FontFamily.Monospace
                        )
                    }
                }
                if (current != null) {
                    HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp))
                    val title = if (current!!.version == "Unreleased") "What's new — Unreleased"
                                else "What's new in ${current!!.version}"
                    Text(
                        text = title,
                        style = MaterialTheme.typography.titleMedium,
                        modifier = Modifier.padding(bottom = 4.dp)
                    )
                    current!!.sections.forEach { section ->
                        Text(
                            text = section.heading,
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.padding(top = 4.dp)
                        )
                        section.entries.forEach { entry ->
                            Row(
                                horizontalArrangement = Arrangement.spacedBy(4.dp),
                                modifier = Modifier.padding(top = 4.dp)
                            ) {
                                Text(
                                    text = "•",
                                    style = MaterialTheme.typography.bodySmall
                                )
                                Text(
                                    text = buildAnnotatedString {
                                        if (entry.boldLabel != null) {
                                            withStyle(SpanStyle(fontWeight = FontWeight.SemiBold)) {
                                                append(entry.boldLabel)
                                            }
                                            if (entry.body.isNotEmpty()) {
                                                append(" ")
                                                append(entry.body)
                                            }
                                        } else {
                                            append(entry.body)
                                        }
                                    },
                                    style = MaterialTheme.typography.bodySmall
                                )
                            }
                        }
                    }
                }
            }
        }
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

@Composable
private fun DictationLanguagePickerDialog(
    currentLanguage: DictationLanguage,
    onSelect: (DictationLanguage) -> Unit,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Dictation Language") },
        text = {
            Column {
                DictationLanguage.entries.forEach { language ->
                    val label = when (language) {
                        DictationLanguage.GERMAN -> "German (de-DE)"
                        DictationLanguage.ENGLISH -> "English (en-US)"
                    }
                    TextButton(
                        onClick = { onSelect(language) },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text(
                            text = label,
                            color = if (language == currentLanguage) MaterialTheme.colorScheme.primary
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
