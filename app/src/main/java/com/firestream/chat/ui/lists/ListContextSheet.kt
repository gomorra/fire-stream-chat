package com.firestream.chat.ui.lists

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.CheckBox
import androidx.compose.material.icons.filled.CheckBoxOutlineBlank
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Remove
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.PushPin
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.SwapVert
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import com.firestream.chat.domain.model.HistoryAction
import com.firestream.chat.domain.model.ListData
import com.firestream.chat.domain.model.ListHistoryEntry
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun ListContextSheet(
    listData: ListData,
    isOwner: Boolean,
    isPinned: Boolean = false,
    history: List<ListHistoryEntry> = emptyList(),
    onDismiss: () -> Unit,
    onShare: () -> Unit,
    onDelete: () -> Unit,
    onRename: (String) -> Unit,
    onTogglePin: () -> Unit = {}
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = false)
    var showRenameDialog by remember { mutableStateOf(false) }
    var renameText by remember { mutableStateOf(listData.title) }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp)
        ) {
            Text(
                text = listData.title,
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.padding(bottom = 12.dp)
            )

            // Action chips
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                AssistChip(
                    onClick = { onTogglePin(); onDismiss() },
                    label = { Text(if (isPinned) "Unpin" else "Pin") },
                    leadingIcon = { Icon(Icons.Default.PushPin, contentDescription = null) }
                )
                if (isOwner) {
                    AssistChip(
                        onClick = onShare,
                        label = { Text("Share") },
                        leadingIcon = { Icon(Icons.Default.Share, contentDescription = null) }
                    )
                }
                AssistChip(
                    onClick = { showRenameDialog = true },
                    label = { Text("Rename") },
                    leadingIcon = { Icon(Icons.Default.Edit, contentDescription = null) }
                )
                if (isOwner) {
                    AssistChip(
                        onClick = onDelete,
                        label = { Text("Delete") },
                        leadingIcon = { Icon(Icons.Default.Delete, contentDescription = null) }
                    )
                }
            }

            Spacer(modifier = Modifier.height(12.dp))
            HorizontalDivider()
            Spacer(modifier = Modifier.height(8.dp))

            // History section
            if (history.isEmpty()) {
                Text(
                    text = "No history yet",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f),
                    modifier = Modifier.padding(vertical = 8.dp)
                )
            } else {
                Text(
                    text = "History",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(bottom = 4.dp)
                )
                LazyColumn {
                    items(history, key = { it.id }) { entry ->
                        HistoryEntryRow(entry)
                        HorizontalDivider(modifier = Modifier.padding(start = 40.dp))
                    }
                }
            }

            Spacer(modifier = Modifier.height(16.dp))
        }
    }

    if (showRenameDialog) {
        AlertDialog(
            onDismissRequest = { showRenameDialog = false },
            title = { Text("Rename list") },
            text = {
                OutlinedTextField(
                    value = renameText,
                    onValueChange = { renameText = it },
                    singleLine = true,
                    label = { Text("Title") }
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        if (renameText.isNotBlank()) {
                            onRename(renameText)
                            showRenameDialog = false
                        }
                    }
                ) { Text("Rename") }
            },
            dismissButton = {
                TextButton(onClick = { showRenameDialog = false }) { Text("Cancel") }
            }
        )
    }
}

@Composable
private fun HistoryEntryRow(entry: ListHistoryEntry) {
    val (icon, actionText) = entry.action.toDisplayInfo(entry.itemText)

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            modifier = Modifier.size(20.dp),
            tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f)
        )
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = "${entry.userName} $actionText",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurface
            )
        }
        Text(
            text = SimpleDateFormat("MMM d, HH:mm", Locale.getDefault())
                .format(Date(entry.timestamp)),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f)
        )
    }
}

private fun HistoryAction.toDisplayInfo(itemText: String?): Pair<ImageVector, String> = when (this) {
    HistoryAction.CREATED -> Icons.Default.Star to "created this list"
    HistoryAction.ITEM_ADDED -> Icons.Default.Add to "added \"${itemText.orEmpty()}\""
    HistoryAction.ITEM_REMOVED -> Icons.Default.Remove to "removed \"${itemText.orEmpty()}\""
    HistoryAction.ITEM_MODIFIED -> Icons.Default.Edit to "edited \"${itemText.orEmpty()}\""
    HistoryAction.ITEM_CHECKED -> Icons.Default.CheckBox to "checked \"${itemText.orEmpty()}\""
    HistoryAction.ITEM_UNCHECKED -> Icons.Default.CheckBoxOutlineBlank to "unchecked \"${itemText.orEmpty()}\""
    HistoryAction.TITLE_CHANGED -> Icons.Default.Edit to "renamed to \"${itemText.orEmpty()}\""
    HistoryAction.REORDERED -> Icons.Default.SwapVert to "reordered items"
}
