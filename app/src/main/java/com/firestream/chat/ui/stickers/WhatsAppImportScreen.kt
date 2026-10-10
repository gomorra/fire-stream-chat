package com.firestream.chat.ui.stickers

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material3.Switch
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

internal const val SHOW_ALL_SWITCH_TAG = "whatsapp-show-all"

private const val EVERY_FILE_INTRO = "Every sticker you have sent or received in WhatsApp, newest first."
private const val NEW_ONLY_INTRO = "The stickers that arrived in WhatsApp since your last import, newest first."

/**
 * The granted WhatsApp sticker folder as a multi-select grid, newest first.
 * After a first import it shows the files that are newer than that import,
 * and a *Show all* switch brings back the rest.
 *
 * The files are not grouped here. Which pack a sticker belongs to is written
 * inside the file and is read by the import, which sorts them into packs.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun WhatsAppImportScreen(
    state: WhatsAppImportState,
    isImporting: Boolean,
    onClose: () -> Unit,
    onToggleFile: (String) -> Unit,
    onToggleSelectAll: () -> Unit,
    onImport: () -> Unit,
    onSetShowAll: (Boolean) -> Unit = {},
    snackbarHostState: SnackbarHostState = remember { SnackbarHostState() },
) {
    val shownFiles = state.shownFiles
    // With an earlier import to compare with, the grid holds only what came after it.
    val newOnly = state.hasEarlierImport && !state.showAll
    val selectedCount = state.selected.size
    Scaffold(
        topBar = {
            StickerTopBar(
                title = "WhatsApp stickers",
                isImporting = isImporting,
                navigationIcon = {
                    IconButton(onClick = onClose) { Icon(Icons.Default.Close, contentDescription = "Close") }
                },
                actions = {
                    if (shownFiles.isNotEmpty()) {
                        TextButton(onClick = onToggleSelectAll, enabled = !isImporting) {
                            Text(if (state.allSelected) "Select none" else "Select all")
                        }
                    }
                },
            )
        },
        bottomBar = {
            if (state.files.isNotEmpty()) {
                Button(
                    onClick = onImport,
                    enabled = selectedCount > 0 && !isImporting,
                    modifier = Modifier
                        .fillMaxWidth()
                        .navigationBarsPadding()
                        .padding(horizontal = 16.dp, vertical = 8.dp),
                ) {
                    Text(if (isImporting) "Importing…" else "Import $selectedCount")
                }
            }
        },
        snackbarHost = { SnackbarHost(snackbarHostState) },
    ) { padding ->
        when {
            state.isLoading -> Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) {
                CircularProgressIndicator()
            }
            state.files.isEmpty() -> Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) {
                EmptyHint(
                    title = "No stickers in this folder",
                    body = "Go back and choose the folder named WhatsApp Stickers",
                )
            }
            else -> Column(Modifier.fillMaxSize().padding(padding)) {
                Text(
                    text = (if (newOnly) NEW_ONLY_INTRO else EVERY_FILE_INTRO) +
                        " Your WhatsApp favourites are not stored here and cannot be imported.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                )
                if (state.hasEarlierImport) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier
                            .fillMaxWidth()
                            .toggleable(
                                value = state.showAll,
                                enabled = !isImporting,
                                role = Role.Switch,
                                onValueChange = onSetShowAll,
                            )
                            .padding(horizontal = 16.dp, vertical = 4.dp)
                            .testTag(SHOW_ALL_SWITCH_TAG),
                    ) {
                        Text("Show all", style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
                        // The row is the switch: it takes the tap and says its state.
                        Switch(checked = state.showAll, onCheckedChange = null, enabled = !isImporting)
                    }
                }
                if (shownFiles.isEmpty()) {
                    EmptyHint(
                        title = "Nothing new",
                        body = "No sticker has arrived in WhatsApp since your last import",
                        modifier = Modifier.fillMaxWidth().padding(vertical = 48.dp),
                    )
                } else {
                    LazyVerticalGrid(
                        columns = GridCells.Adaptive(STICKER_CELL),
                        modifier = Modifier.fillMaxSize(),
                        contentPadding = PaddingValues(8.dp),
                    ) {
                        items(shownFiles, key = { it.uri }) { file ->
                            StickerCell(
                                id = file.uri,
                                model = file.uri,
                                isSelected = file.uri in state.selected,
                                onClick = onToggleFile,
                                enabled = !isImporting,
                                hasStill = file.hasStill,
                            )
                        }
                    }
                }
            }
        }
    }
}
