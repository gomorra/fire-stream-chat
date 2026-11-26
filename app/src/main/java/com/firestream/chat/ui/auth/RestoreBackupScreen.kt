package com.firestream.chat.ui.auth

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CloudDownload
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.firestream.chat.domain.model.RestoreProgress
import com.firestream.chat.domain.model.RestoreStage
import com.firestream.chat.ui.settings.formatBackupSize
import com.firestream.chat.ui.settings.formatBackupTimestamp

@Composable
fun RestoreBackupScreen(
    onRestoreComplete: () -> Unit,
    onSkip: () -> Unit,
    viewModel: RestoreBackupViewModel = hiltViewModel(),
) {
    val uiState by viewModel.uiState.collectAsState()

    LaunchedEffect(uiState.restoreSucceeded) {
        if (uiState.restoreSucceeded) {
            onRestoreComplete()
        }
    }

    RestoreBackupScreenContent(
        uiState = uiState,
        onRestore = viewModel::restore,
        onSkipConfirmed = {
            viewModel.markSkipped()
            onSkip()
        },
        onRetrySummary = viewModel::loadSummary,
    )
}

@Composable
fun RestoreBackupScreenContent(
    uiState: RestoreBackupUiState,
    onRestore: (String) -> Unit,
    onSkipConfirmed: () -> Unit,
    onRetrySummary: () -> Unit,
) {
    var passphrase by remember { mutableStateOf("") }
    var showPassphrase by remember { mutableStateOf(false) }
    var showSkipConfirm by rememberSaveable { mutableStateOf(false) }
    val controlsLocked = uiState.isRestoring || uiState.restoreSucceeded

    // Block system Back while the database swap or app restart is underway.
    BackHandler(enabled = controlsLocked) {}

    Scaffold { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .imePadding()
                .verticalScroll(rememberScrollState())
                .padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            Icon(
                imageVector = Icons.Default.CloudDownload,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(64.dp),
            )
            Spacer(Modifier.height(16.dp))
            Text(
                text = "Restore Encrypted Backup",
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.SemiBold,
                textAlign = TextAlign.Center,
            )
            Spacer(Modifier.height(8.dp))
            Text(
                text = "Enter your backup passphrase to restore your chats and media on this device.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )

            Spacer(Modifier.height(20.dp))

            when {
                uiState.isLoadingSummary -> {
                    CircularProgressIndicator(modifier = Modifier.size(28.dp))
                }
                uiState.summary != null -> {
                    Card(
                        modifier = Modifier.fillMaxWidth().testTag("restore_summary_card"),
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
                    ) {
                        Column(
                            modifier = Modifier.padding(16.dp),
                            verticalArrangement = Arrangement.spacedBy(4.dp),
                        ) {
                            Text(
                                text = "Backup date: ${formatBackupTimestamp(uiState.summary.createdAt)}",
                                style = MaterialTheme.typography.bodyMedium,
                                fontWeight = FontWeight.Medium,
                            )
                            Text(
                                text = "Size: ${formatBackupSize(uiState.summary.dbCipherSizeBytes)}",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
                uiState.summaryError != null -> {
                    TextButton(onClick = onRetrySummary) {
                        Text("Retry loading backup details")
                    }
                }
            }

            Spacer(Modifier.height(20.dp))

            OutlinedTextField(
                value = passphrase,
                onValueChange = { passphrase = it },
                label = { Text("Backup passphrase") },
                singleLine = true,
                enabled = !controlsLocked,
                visualTransformation = if (showPassphrase) VisualTransformation.None else PasswordVisualTransformation(),
                keyboardOptions = KeyboardOptions(
                    keyboardType = KeyboardType.Password,
                    autoCorrectEnabled = false,
                    imeAction = ImeAction.Done,
                ),
                keyboardActions = KeyboardActions(
                    onDone = { if (!controlsLocked && passphrase.isNotEmpty()) onRestore(passphrase) }
                ),
                trailingIcon = {
                    IconButton(onClick = { showPassphrase = !showPassphrase }) {
                        Icon(
                            imageVector = if (showPassphrase) Icons.Default.VisibilityOff else Icons.Default.Visibility,
                            contentDescription = if (showPassphrase) "Hide passphrase" else "Show passphrase",
                        )
                    }
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("restore_passphrase_input"),
            )

            if (uiState.error != null) {
                Spacer(Modifier.height(8.dp))
                Text(
                    text = uiState.error.message,
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.fillMaxWidth().testTag("restore_error_text"),
                )
            }

            if (uiState.isRestoring || uiState.restoreSucceeded) {
                Spacer(Modifier.height(16.dp))
                Text(
                    text = if (uiState.restoreSucceeded) "Restarting FireStream…" else restoreStageLabel(uiState.progress),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(6.dp))
                if (uiState.restoreSucceeded) {
                    LinearProgressIndicator(
                        modifier = Modifier.fillMaxWidth().testTag("restore_progress_bar"),
                    )
                } else {
                    LinearProgressIndicator(
                        progress = { uiState.progress.fraction.coerceIn(0f, 1f) },
                        modifier = Modifier.fillMaxWidth().testTag("restore_progress_bar"),
                    )
                }
            }

            Spacer(Modifier.height(24.dp))

            Button(
                onClick = { onRestore(passphrase) },
                enabled = !controlsLocked && passphrase.isNotEmpty(),
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("restore_button"),
            ) {
                Text(
                    when {
                        uiState.restoreSucceeded -> "Restarting…"
                        uiState.isRestoring -> "Restoring…"
                        else -> "Restore"
                    }
                )
            }

            Spacer(Modifier.height(8.dp))

            TextButton(
                onClick = { showSkipConfirm = true },
                enabled = !controlsLocked,
                modifier = Modifier.testTag("restore_skip_button"),
            ) {
                Text("Skip")
            }
        }
    }

    if (showSkipConfirm) {
        AlertDialog(
            onDismissRequest = { showSkipConfirm = false },
            title = { Text("Skip Restore?") },
            text = {
                Text(
                    "If you skip restoring, FireStream will start with an empty chat history on this device. " +
                        "Previously encrypted messages cannot be recovered without restoring from backup."
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        showSkipConfirm = false
                        onSkipConfirmed()
                    },
                    modifier = Modifier.testTag("restore_skip_confirm_button"),
                ) {
                    Text("Start Empty")
                }
            },
            dismissButton = {
                TextButton(onClick = { showSkipConfirm = false }) {
                    Text("Cancel")
                }
            },
        )
    }
}

internal fun restoreStageLabel(progress: RestoreProgress): String = when (progress.stage) {
    RestoreStage.FETCHING_MANIFEST -> "Fetching backup…"
    RestoreStage.VERIFYING_PASSPHRASE -> "Verifying passphrase…"
    RestoreStage.DOWNLOADING_DB -> "Downloading encrypted backup…"
    RestoreStage.DECRYPTING_DB -> "Decrypting backup…"
    RestoreStage.VERIFYING_DB -> "Verifying database integrity…"
    RestoreStage.REPLACING_DB -> "Finalizing restore…"
    RestoreStage.COMPLETED -> "Restore complete"
    RestoreStage.FAILED -> progress.errorMessage ?: "Restore failed"
    RestoreStage.IDLE -> ""
}
