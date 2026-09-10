package com.firestream.chat.ui.chat.picker

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.firestream.chat.domain.util.OverlayContent
import com.firestream.chat.domain.util.StickerDesign
import com.firestream.chat.domain.util.StickerPack
import com.firestream.chat.ui.chat.imageedit.drawSticker

/**
 * The bundled sticker pack, as a grid.
 *
 * Every cell is drawn by the *same* code that paints a placed sticker on the
 * photo (`drawSticker`), so what you tap is exactly what lands — there is no
 * thumbnail that could be a different rendering of the same thing. That is worth
 * more than it sounds: a pack of PNG assets would have had a thumbnail size and
 * a placed size and two chances to filter them differently.
 *
 * ### No recents row
 *
 * The plan pairs the pack with recents. With twelve stickers that fit on screen
 * without scrolling, a recents row would take a fifth of the panel to save no
 * scrolling at all — and persisting it means a DataStore key, a manager and a
 * ViewModel path that the emoji recents already own and this would duplicate.
 * It becomes worth building when pack management does (`docs/BACKLOG.md` §4.6).
 *
 * Search filters on the sticker's own name, which is also what a screen reader
 * announces for it.
 */
@Composable
internal fun StickerTab(
    query: String,
    onSelection: (PickerSelection) -> Unit,
    modifier: Modifier = Modifier,
) {
    val matches = remember(query) {
        if (query.isBlank()) {
            StickerPack.stickers
        } else {
            StickerPack.stickers.filter { it.name.contains(query.trim(), ignoreCase = true) }
        }
    }

    if (matches.isEmpty()) {
        Box(modifier = modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text(
                text = "No stickers found",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(16.dp),
            )
        }
        return
    }

    LazyVerticalGrid(
        columns = GridCells.Fixed(GRID_COLUMNS),
        modifier = modifier.fillMaxWidth().semantics { contentDescription = "Stickers" },
        contentPadding = PaddingValues(12.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        items(matches, key = { it.id }) { design ->
            StickerCell(
                design = design,
                onClick = { onSelection(PickerSelection.Overlay(OverlayContent.Sticker(design.id))) },
            )
        }
    }
}

/** One sticker, drawn into a square cell with a little breathing room round it. */
@Composable
private fun StickerCell(design: StickerDesign, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .aspectRatio(1f)
            .clickable(onClickLabel = "Place sticker", onClick = onClick)
            .semantics { contentDescription = design.name },
        contentAlignment = Alignment.Center,
    ) {
        Canvas(modifier = Modifier.fillMaxSize().padding(6.dp)) {
            drawSticker(
                design = design,
                center = Offset(size.width / 2f, size.height / 2f),
                size = minOf(size.width, size.height),
            )
        }
    }
}

/**
 * Four across, not the emoji grid's eight: a sticker is a picture rather than a
 * glyph, and at eight across the pack reads as a row of coloured specks.
 */
private const val GRID_COLUMNS = 4
