package com.firestream.chat.ui.stickers

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.firestream.chat.domain.util.StickerSearch
import com.firestream.chat.ui.chat.picker.EmojiSearchData

internal const val ALL_STICKERS_TAB_TAG = "all-stickers-tab"
internal const val STICKER_SEARCH_TAG = "sticker-search"

/** The test tag of a cell in the grid of every sticker, where a sticker id alone can name two cells. */
internal fun stickerEntryTag(entry: StickerEntry): String = "sticker:${entry.packId}/${entry.stickerId}"

/** Six cells in a row on a phone 360 dp wide. */
private val SMALL_STICKER_CELL = 56.dp

/**
 * Every sticker of the library in one grid, under a search field. A long press
 * starts a selection that can span packs. The search is `StickerSearch.sections`:
 * a sticker's names, what was found on it automatically, its emojis and its pack's name.
 */
@Composable
internal fun AllStickersTab(uiState: StickerLibraryUiState, actions: StickerLibraryActions, modifier: Modifier = Modifier) {
    val sections = remember(uiState.packs, uiState.query) {
        StickerSearch.sections(uiState.packs, uiState.query, EmojiSearchData::searchEmojis) { it.label() }
    }
    val selected = uiState.selectedStickers
    Column(modifier = modifier.fillMaxSize().testTag(ALL_STICKERS_TAB_TAG)) {
        OutlinedTextField(
            value = uiState.query,
            onValueChange = actions.onSetQuery,
            singleLine = true,
            placeholder = { Text("Search by name, emoji or pack") },
            leadingIcon = { Icon(Icons.Default.Search, contentDescription = null) },
            trailingIcon = {
                if (uiState.query.isNotEmpty()) {
                    IconButton(onClick = { actions.onSetQuery("") }) {
                        Icon(Icons.Default.Close, contentDescription = "Clear search")
                    }
                }
            },
            shape = MaterialTheme.shapes.extraLarge,
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp).testTag(STICKER_SEARCH_TAG),
        )
        when {
            sections.isNotEmpty() -> LazyVerticalGrid(
                columns = GridCells.Adaptive(SMALL_STICKER_CELL),
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(horizontal = 4.dp, vertical = 4.dp),
            ) {
                sections.forEach { section ->
                    val pack = section.pack
                    item(key = "title:${pack.id}", span = { GridItemSpan(maxLineSpan) }, contentType = "title") {
                        Text(
                            text = "${pack.label()} · ${section.stickers.size}",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.padding(start = 12.dp, end = 12.dp, top = 12.dp, bottom = 4.dp),
                        )
                    }
                    items(section.stickers, key = { "${pack.id}/${it.id}" }, contentType = { "sticker" }) { sticker ->
                        val entry = StickerEntry(pack.id, sticker.id)
                        val onToggle = rememberEntryToggle(pack.id, actions.onToggleSticker)
                        StickerCell(
                            sticker = sticker,
                            isSelected = entry in selected,
                            // A tap picks only once a long press has started a selection.
                            onClick = if (selected.isNotEmpty()) onToggle else NO_TAP,
                            onLongClick = onToggle,
                            tag = stickerEntryTag(entry),
                            isSmall = true,
                        )
                    }
                }
            }
            uiState.query.isNotBlank() -> EmptyHint(
                title = "No stickers for “${uiState.query.trim()}”",
                body = "Search by a sticker's name, an emoji, a word for one, or a pack's name",
                modifier = Modifier.fillMaxWidth().padding(vertical = 48.dp),
            )
            !uiState.isLoading -> EmptyHint(
                title = "No stickers yet",
                body = "Tap + to make one from a photo, or to import some from WhatsApp or from files",
                modifier = Modifier.fillMaxWidth().padding(vertical = 48.dp),
            )
        }
    }
}
