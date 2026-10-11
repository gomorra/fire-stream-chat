package com.firestream.chat.ui.stickers

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
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
import androidx.compose.material3.FilterChip
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
import com.firestream.chat.domain.util.StickerNameFilter
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
 * The two chips under the field narrow the grid to the stickers nobody named,
 * or to those the naming service named.
 */
@Composable
internal fun AllStickersTab(uiState: StickerLibraryUiState, actions: StickerLibraryActions, modifier: Modifier = Modifier) {
    val sections = remember(uiState.packs, uiState.query, uiState.nameFilter) {
        StickerSearch.sections(
            packs = uiState.packs,
            query = uiState.query,
            emojisFor = EmojiSearchData::searchEmojis,
            packName = { it.label() },
            filter = uiState.nameFilter,
        )
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
        Row(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
        ) {
            NameFilterChip(NO_NAME_FILTER_LABEL, StickerNameFilter.NO_NAME, uiState.nameFilter, actions.onSetNameFilter)
            // Without a naming service no sticker is ever named automatically.
            if (uiState.autoNaming.isSupported) {
                NameFilterChip(
                    AUTO_NAMED_FILTER_LABEL,
                    StickerNameFilter.NAMED_AUTOMATICALLY,
                    uiState.nameFilter,
                    actions.onSetNameFilter,
                )
            }
        }
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
            uiState.nameFilter == StickerNameFilter.NO_NAME -> EmptyHint(
                title = "Every sticker has a name",
                body = "A sticker shows here while it has no name of yours and none that was found automatically",
                modifier = Modifier.fillMaxWidth().padding(vertical = 48.dp),
            )
            uiState.nameFilter == StickerNameFilter.NAMED_AUTOMATICALLY -> EmptyHint(
                title = "No sticker is named automatically yet",
                body = "A sticker shows here once the naming service has found a name for it",
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

internal const val NO_NAME_FILTER_LABEL = "No name"
internal const val AUTO_NAMED_FILTER_LABEL = "Named automatically"

/** One filter of the grid by how a sticker is named. A tap on the chip that is on shows every sticker again. */
@Composable
private fun NameFilterChip(
    label: String,
    filter: StickerNameFilter,
    current: StickerNameFilter,
    onSet: (StickerNameFilter) -> Unit,
) {
    val selected = current == filter
    FilterChip(
        selected = selected,
        onClick = { onSet(if (selected) StickerNameFilter.ALL else filter) },
        label = { Text(label) },
    )
}
