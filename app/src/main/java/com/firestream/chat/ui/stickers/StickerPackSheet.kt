package com.firestream.chat.ui.stickers

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel

/**
 * The pack a received sticker came from, as a sheet: its name, its stickers,
 * and **Add pack**, which copies it into the library. A pack the library
 * already holds says so and offers nothing to add.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun StickerPackSheet(
    packId: String,
    onDismiss: () -> Unit,
    viewModel: StickerPackPreviewViewModel = hiltViewModel(),
) {
    val uiState by viewModel.uiState.collectAsState()
    LaunchedEffect(packId) { viewModel.load(packId) }
    ModalBottomSheet(onDismissRequest = onDismiss) {
        StickerPackSheetContent(uiState = uiState, onAddPack = viewModel::addPack)
    }
}

@Composable
internal fun StickerPackSheetContent(
    uiState: StickerPackPreviewUiState,
    onAddPack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, bottom = 32.dp)) {
        val preview = uiState.preview
        when {
            uiState.isLoading -> Box(Modifier.fillMaxWidth().height(160.dp), contentAlignment = Alignment.Center) {
                CircularProgressIndicator()
            }
            // A look-up that found nothing always says why.
            preview == null -> Text(
                text = uiState.error?.message.orEmpty(),
                style = MaterialTheme.typography.bodyLarge,
                modifier = Modifier.padding(vertical = 24.dp),
            )
            else -> {
                val count = "${preview.stickers.size} ${stickers(preview.stickers.size)}"
                Text(preview.name, style = MaterialTheme.typography.titleLarge)
                Text(
                    text = preview.publisher?.let { "$count · $it" } ?: count,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                LazyVerticalGrid(
                    columns = GridCells.Fixed(4),
                    modifier = Modifier.fillMaxWidth().heightIn(max = 320.dp).padding(vertical = 12.dp),
                ) {
                    items(preview.stickers, key = { it.id }) { sticker ->
                        StickerCell(sticker = sticker, isSelected = false, onClick = {}, enabled = false)
                    }
                }
                if (preview.isInLibrary) {
                    Text(
                        text = "This pack is in your library",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.align(Alignment.CenterHorizontally).padding(vertical = 12.dp),
                    )
                } else {
                    Button(onClick = onAddPack, enabled = !uiState.isAdding, modifier = Modifier.fillMaxWidth()) {
                        Text("Add pack")
                    }
                }
                uiState.error?.let { error ->
                    Text(
                        text = error.message,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                        modifier = Modifier.padding(top = 8.dp),
                    )
                }
            }
        }
    }
}
