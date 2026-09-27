package com.firestream.chat.ui.chat

import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier

/** Confirm before opening a file that can install or run code (an APK, a script). */
@Composable
internal fun RiskyFileDialog(
    fileName: String,
    onOpen: () -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        modifier = modifier,
        title = { Text("Open this file?") },
        text = {
            Text(
                "\"$fileName\" can install an app or run code on your device. " +
                    "Only open it if you trust the person who sent it."
            )
        },
        confirmButton = { TextButton(onClick = onOpen) { Text("Open") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}
