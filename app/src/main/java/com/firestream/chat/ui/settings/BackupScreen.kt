package com.firestream.chat.ui.settings

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.CloudDone
import androidx.compose.material.icons.filled.CloudOff
import androidx.compose.material.icons.filled.CloudUpload
import androidx.compose.material.icons.filled.Key
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.firestream.chat.domain.model.BackupFrequency
import com.firestream.chat.domain.model.BackupProgress
import com.firestream.chat.domain.model.BackupStage
import com.firestream.chat.domain.model.MIN_BACKUP_PASSPHRASE_LENGTH
import java.text.DateFormat
import java.util.Date
import java.util.Locale

@Immutable
data class BackupScreenCallbacks(
    val onBackClick: () -> Unit,
    val onBackupNow: () -> Unit,
    val onOpenPassphraseDialog: () -> Unit,
    val onDismissPassphraseDialog: () -> Unit,
    val onSavePassphrase: (passphrase: String, confirmation: String, initialFrequency: BackupFrequency?) -> Unit,
    val onSelectFrequency: (BackupFrequency) -> Unit,
    val onConsumeSnackbar: () -> Unit,
)

@Composable
fun BackupScreen(
    onBackClick: () -> Unit,
    viewModel: BackupViewModel = hiltViewModel(),
) {
    val uiState by viewModel.uiState.collectAsState()
    val callbacks = remember(viewModel, onBackClick) {
        BackupScreenCallbacks(
            onBackClick = onBackClick,
            onBackupNow = viewModel::backupNow,
            onOpenPassphraseDialog = viewModel::openPassphraseDialog,
            onDismissPassphraseDialog = viewModel::dismissPassphraseDialog,
            onSavePassphrase = viewModel::savePassphrase,
            onSelectFrequency = viewModel::setFrequency,
            onConsumeSnackbar = viewModel::consumeSnackbar,
        )
    }
    BackupScreenContent(uiState = uiState, callbacks = callbacks)
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BackupScreenContent(
    uiState: BackupUiState,
    callbacks: BackupScreenCallbacks,
) {
    val snackbarHostState = remember { SnackbarHostState() }
    LaunchedEffect(uiState.snackbarMessage) {
        val msg = uiState.snackbarMessage ?: return@LaunchedEffect
        snackbarHostState.showSnackbar(msg)
        callbacks.onConsumeSnackbar()
    }

    val view = LocalView.current
    DisposableEffect(view, uiState.isBackupRunning) {
        val previousKeepScreenOn = view.keepScreenOn
        if (uiState.isBackupRunning) {
            view.keepScreenOn = true
        }
        onDispose {
            view.keepScreenOn = previousKeepScreenOn
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Chat Backup") },
                navigationIcon = {
                    IconButton(onClick = callbacks.onBackClick) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.background,
                    titleContentColor = MaterialTheme.colorScheme.onBackground,
                    navigationIconContentColor = MaterialTheme.colorScheme.onBackground,
                ),
            )
        },
        snackbarHost = { SnackbarHost(snackbarHostState) },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            BackupStatusCard(uiState = uiState)

            if (uiState.isBackupRunning) {
                BackupProgressCard(progress = uiState.progress)
            } else if (uiState.progress.stage == BackupStage.FAILED && uiState.progress.errorMessage != null) {
                Card(
                    modifier = Modifier.fillMaxWidth().testTag("backup_error_card"),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer),
                ) {
                    Text(
                        text = uiState.progress.errorMessage,
                        color = MaterialTheme.colorScheme.onErrorContainer,
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.padding(16.dp),
                    )
                }
            }

            Button(
                onClick = callbacks.onBackupNow,
                enabled = uiState.status.isSupported && !uiState.isBackupRunning,
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("backup_now_button"),
            ) {
                Text(
                    if (uiState.status.isPassphraseConfigured) "Back up now" else "Set passphrase & back up"
                )
            }

            HorizontalDivider()

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable(
                        enabled = uiState.status.isSupported && !uiState.isBackupRunning,
                        onClick = callbacks.onOpenPassphraseDialog,
                    )
                    .padding(vertical = 12.dp)
                    .testTag("backup_passphrase_row"),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(
                    imageVector = Icons.Default.Key,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(24.dp),
                )
                Spacer(Modifier.width(16.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = if (uiState.status.isPassphraseConfigured) {
                            "Change backup passphrase"
                        } else {
                            "Set backup passphrase"
                        },
                        style = MaterialTheme.typography.bodyLarge,
                        fontWeight = FontWeight.Medium,
                    )
                    Text(
                        text = "Your backup is end-to-end encrypted with a passphrase only you know.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            HorizontalDivider()

            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    text = "Automatic Backup",
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.primary,
                    fontWeight = FontWeight.SemiBold,
                )
                Text(
                    text = "Runs automatically on Wi-Fi while charging.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                BackupFrequencySegmentedControl(
                    selected = uiState.status.frequency,
                    enabled = uiState.status.isPassphraseConfigured,
                    tagPrefix = "backup_freq",
                    onSelect = callbacks.onSelectFrequency,
                )
            }
        }
    }

    if (uiState.showPassphraseDialog) {
        PassphraseDialog(
            isChange = uiState.status.isPassphraseConfigured,
            isSaving = uiState.isSavingPassphrase,
            error = uiState.passphraseError?.message,
            onDismiss = callbacks.onDismissPassphraseDialog,
            onConfirm = callbacks.onSavePassphrase,
        )
    }
}

@Composable
private fun BackupStatusCard(uiState: BackupUiState) {
    val status = uiState.status
    val icon = when {
        uiState.isBackupRunning -> Icons.Default.CloudUpload
        status.lastBackupAt != null -> Icons.Default.CloudDone
        else -> Icons.Default.CloudOff
    }
    Card(
        modifier = Modifier.fillMaxWidth().testTag("backup_status_card"),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
    ) {
        Row(
            modifier = Modifier.padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(36.dp),
            )
            Spacer(Modifier.width(16.dp))
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(
                    text = when {
                        status.lastBackupAt != null -> "Last backup: ${formatBackupTimestamp(status.lastBackupAt)}"
                        status.isPassphraseConfigured -> "Passphrase set · Not backed up yet"
                        else -> "Not set up"
                    },
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                )
                status.lastBackupSizeBytes?.let { size ->
                    Text(
                        text = "Encrypted database size: ${formatBackupSize(size)}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                if (status.isPassphraseConfigured) {
                    Text(
                        text = formatBackupScheduleSummary(status.frequency),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                if (status.lastBackupAt != null) {
                    Text(
                        text = if (status.lastBackupLegacyMediaComplete) {
                            "All legacy media backed up ✓"
                        } else {
                            "Some legacy media could not be backed up — tap Back up now to retry"
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = if (status.lastBackupLegacyMediaComplete) {
                            MaterialTheme.colorScheme.primary
                        } else {
                            MaterialTheme.colorScheme.error
                        },
                        fontWeight = FontWeight.Medium,
                    )
                }
            }
        }
    }
}

@Composable
private fun BackupProgressCard(progress: BackupProgress) {
    val clamped = progress.fraction.coerceIn(0f, 1f)
    val percent = (clamped * 100).toInt()
    Card(
        modifier = Modifier.fillMaxWidth().testTag("backup_progress_card"),
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = backupStageLabel(progress),
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.Medium,
                    modifier = Modifier.weight(1f),
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    text = "$percent%",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.primary,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.testTag("backup_progress_percent"),
                )
            }
            LinearProgressIndicator(
                progress = { clamped },
                modifier = Modifier.fillMaxWidth().testTag("backup_progress_bar"),
            )
            Text(
                text = "Keep FireStream open on Wi-Fi until complete. You can adjust your automatic backup schedule below.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun BackupFrequencySegmentedControl(
    selected: BackupFrequency,
    enabled: Boolean,
    tagPrefix: String,
    onSelect: (BackupFrequency) -> Unit,
) {
    val options = listOf(
        Triple(BackupFrequency.OFF, "Off", "${tagPrefix}_off"),
        Triple(BackupFrequency.DAILY, "Daily", "${tagPrefix}_daily"),
        Triple(BackupFrequency.WEEKLY, "Weekly", "${tagPrefix}_weekly"),
    )
    SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
        options.forEachIndexed { index, (frequency, label, tag) ->
            SegmentedButton(
                selected = selected == frequency,
                onClick = { onSelect(frequency) },
                enabled = enabled,
                shape = SegmentedButtonDefaults.itemShape(index = index, count = options.size),
                modifier = Modifier.testTag(tag),
                label = {
                    Text(
                        text = label,
                        maxLines = 1,
                        softWrap = false,
                    )
                },
            )
        }
    }
}

@Composable
private fun PassphraseDialog(
    isChange: Boolean,
    isSaving: Boolean,
    error: String?,
    onDismiss: () -> Unit,
    onConfirm: (String, String, BackupFrequency?) -> Unit,
) {
    var passphrase by remember { mutableStateOf("") }
    var confirmation by remember { mutableStateOf("") }
    var showText by remember { mutableStateOf(false) }
    var selectedFrequency by remember { mutableStateOf(BackupFrequency.WEEKLY) }
    val submit = {
        if (!isSaving) {
            onConfirm(passphrase, confirmation, if (isChange) null else selectedFrequency)
        }
    }

    AlertDialog(
        onDismissRequest = { if (!isSaving) onDismiss() },
        title = { Text(if (isChange) "Change Backup Passphrase" else "Set Backup Passphrase") },
        text = {
            Column(
                modifier = Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Row(verticalAlignment = Alignment.Top) {
                    Icon(
                        imageVector = Icons.Default.Warning,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.error,
                        modifier = Modifier.size(20.dp),
                    )
                    Spacer(Modifier.width(8.dp))
                    Text(
                        text = "If you forget this passphrase, your encrypted backup cannot be recovered. Save it in a password manager.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                OutlinedTextField(
                    value = passphrase,
                    onValueChange = { passphrase = it },
                    label = { Text("Passphrase (min. $MIN_BACKUP_PASSPHRASE_LENGTH chars)") },
                    singleLine = true,
                    enabled = !isSaving,
                    visualTransformation = if (showText) VisualTransformation.None else PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(
                        keyboardType = KeyboardType.Password,
                        autoCorrectEnabled = false,
                        imeAction = ImeAction.Next,
                    ),
                    trailingIcon = {
                        IconButton(onClick = { showText = !showText }) {
                            Icon(
                                imageVector = if (showText) Icons.Default.VisibilityOff else Icons.Default.Visibility,
                                contentDescription = if (showText) "Hide passphrase" else "Show passphrase",
                            )
                        }
                    },
                    modifier = Modifier.fillMaxWidth().testTag("passphrase_input"),
                )
                OutlinedTextField(
                    value = confirmation,
                    onValueChange = { confirmation = it },
                    label = { Text("Confirm passphrase") },
                    singleLine = true,
                    enabled = !isSaving,
                    visualTransformation = if (showText) VisualTransformation.None else PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(
                        keyboardType = KeyboardType.Password,
                        autoCorrectEnabled = false,
                        imeAction = ImeAction.Done,
                    ),
                    keyboardActions = KeyboardActions(onDone = { submit() }),
                    modifier = Modifier.fillMaxWidth().testTag("passphrase_confirm_input"),
                )
                if (!isChange) {
                    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text(
                            text = "Automatic backup",
                            style = MaterialTheme.typography.labelLarge,
                            color = MaterialTheme.colorScheme.primary,
                            fontWeight = FontWeight.SemiBold,
                        )
                        BackupFrequencySegmentedControl(
                            selected = selectedFrequency,
                            enabled = !isSaving,
                            tagPrefix = "setup_freq",
                            onSelect = { selectedFrequency = it },
                        )
                        Text(
                            text = "Runs automatically on Wi-Fi while charging.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                if (error != null) {
                    Text(
                        text = error,
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.testTag("passphrase_error_text"),
                    )
                }
            }
        },
        confirmButton = {
            Button(
                onClick = submit,
                enabled = !isSaving,
                modifier = Modifier.testTag("passphrase_save_button"),
            ) {
                if (isSaving) {
                    CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
                } else {
                    Text("Save")
                }
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss, enabled = !isSaving) {
                Text("Cancel")
            }
        },
    )
}

internal fun backupStageLabel(progress: BackupProgress): String = when (progress.stage) {
    BackupStage.SYNCING_CHATS -> "Syncing chats…"
    BackupStage.DOWNLOADING_LEGACY_MEDIA ->
        if (progress.totalItems > 0) "Downloading legacy media (${progress.completedItems}/${progress.totalItems})…"
        else "Checking legacy media…"
    BackupStage.SNAPSHOTTING_DB -> "Preparing database snapshot…"
    BackupStage.ENCRYPTING_DB -> "Encrypting database…"
    BackupStage.UPLOADING_DB -> "Uploading encrypted backup…"
    BackupStage.UPLOADING_LEGACY_MEDIA ->
        if (progress.totalItems > 0) "Uploading legacy media (${progress.completedItems}/${progress.totalItems})…"
        else "Checking legacy media…"
    BackupStage.FINALIZING -> "Finalizing backup…"
    BackupStage.COMPLETED -> "Backup complete"
    BackupStage.FAILED -> progress.errorMessage ?: "Backup failed"
    BackupStage.IDLE -> ""
}

internal fun formatBackupTimestamp(timestampMs: Long): String =
    DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT, Locale.getDefault())
        .format(Date(timestampMs))

internal fun formatBackupScheduleSummary(frequency: BackupFrequency): String = when (frequency) {
    BackupFrequency.OFF -> "Auto-backup: Off"
    BackupFrequency.DAILY -> "Auto-backup: Daily · Wi-Fi & charging"
    BackupFrequency.WEEKLY -> "Auto-backup: Weekly · Wi-Fi & charging"
}

internal fun formatBackupSize(bytes: Long): String = when {
    bytes >= 1024L * 1024L -> String.format(Locale.US, "%.1f MB", bytes / (1024.0 * 1024.0))
    bytes >= 1024L -> String.format(Locale.US, "%.1f KB", bytes / 1024.0)
    else -> "$bytes B"
}
