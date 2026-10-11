package com.firestream.chat.ui.stickers

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items as listItems
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ViewList
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.GridView
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.firestream.chat.domain.model.Sticker
import com.firestream.chat.domain.util.StickerNameFilter
import com.firestream.chat.domain.util.StickerPackMatches
import com.firestream.chat.domain.util.StickerSearch
import com.firestream.chat.ui.chat.picker.EmojiSearchData
import com.firestream.chat.ui.components.LibraryStickerImage

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
    // A tap picks once a long press has started a selection. Before that it opens the sticker's sheet.
    val isSelecting = selected.isNotEmpty()
    Column(modifier = modifier.fillMaxSize().testTag(ALL_STICKERS_TAB_TAG)) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.fillMaxWidth().padding(start = 16.dp, end = 4.dp, top = 8.dp, bottom = 8.dp),
        ) {
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
                modifier = Modifier.weight(1f).testTag(STICKER_SEARCH_TAG),
            )
            IconButton(onClick = { actions.onSetListView(!uiState.isListView) }) {
                if (uiState.isListView) {
                    Icon(Icons.Default.GridView, contentDescription = SHOW_AS_GRID)
                } else {
                    Icon(Icons.AutoMirrored.Filled.ViewList, contentDescription = SHOW_AS_LIST)
                }
            }
        }
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
            sections.isNotEmpty() && uiState.isListView -> LazyColumn(modifier = Modifier.fillMaxSize()) {
                sections.forEach { section ->
                    val pack = section.pack
                    item(key = "title:${pack.id}", contentType = "title") { SectionTitle(section) }
                    listItems(section.stickers, key = { "${pack.id}/${it.id}" }, contentType = { "sticker" }) { sticker ->
                        val entry = StickerEntry(pack.id, sticker.id)
                        val onToggle = rememberEntryToggle(pack.id, actions.onToggleSticker)
                        StickerListRow(
                            sticker = sticker,
                            isSelected = entry in selected,
                            onClick = if (isSelecting) onToggle else actions.onOpenSticker,
                            onLongClick = onToggle,
                            tag = stickerEntryTag(entry),
                        )
                    }
                }
            }
            sections.isNotEmpty() -> LazyVerticalGrid(
                columns = GridCells.Adaptive(SMALL_STICKER_CELL),
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(horizontal = 4.dp, vertical = 4.dp),
            ) {
                sections.forEach { section ->
                    val pack = section.pack
                    item(key = "title:${pack.id}", span = { GridItemSpan(maxLineSpan) }, contentType = "title") {
                        SectionTitle(section, inset = 12.dp)
                    }
                    items(section.stickers, key = { "${pack.id}/${it.id}" }, contentType = { "sticker" }) { sticker ->
                        val entry = StickerEntry(pack.id, sticker.id)
                        val onToggle = rememberEntryToggle(pack.id, actions.onToggleSticker)
                        StickerCell(
                            sticker = sticker,
                            isSelected = entry in selected,
                            onClick = if (isSelecting) onToggle else actions.onOpenSticker,
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

internal const val SHOW_AS_LIST = "Show as a list with names"
internal const val SHOW_AS_GRID = "Show as a grid"

/** A pack's name and how many of its stickers are shown, over them. */
@Composable
private fun SectionTitle(section: StickerPackMatches, inset: Dp = 16.dp) {
    Text(
        text = "${section.pack.label()} · ${section.stickers.size}",
        style = MaterialTheme.typography.labelMedium,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(start = inset, end = inset, top = 12.dp, bottom = 4.dp),
    )
}

/**
 * One sticker of the list view: the picture, its names in one line and what
 * else it is found by in a second (`nameLine`, `wordLine`). A sticker with
 * neither says *No name*. The clicks hand the sticker's id back, as a grid's
 * cell does.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun StickerListRow(
    sticker: Sticker,
    isSelected: Boolean,
    onClick: (String) -> Unit,
    onLongClick: (String) -> Unit,
    tag: String,
) {
    val names = remember(sticker) { sticker.nameLine() }
    val words = remember(sticker) { sticker.wordLine() }
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .testTag(tag)
            .fillMaxWidth()
            .background(if (isSelected) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surface)
            .combinedClickable(onClick = { onClick(sticker.id) }, onLongClick = { onLongClick(sticker.id) })
            .padding(horizontal = 16.dp, vertical = 4.dp),
    ) {
        LibraryStickerImage(sticker = sticker, modifier = Modifier.size(SMALL_STICKER_CELL))
        Column(modifier = Modifier.weight(1f).padding(start = 12.dp)) {
            // A sticker without a name shows its words in the first line. Only one with neither says so.
            val first = names ?: words
            Text(
                text = first ?: NO_NAME_FILTER_LABEL,
                style = MaterialTheme.typography.bodyLarge,
                color = if (first != null) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (names != null && words != null) {
                Text(
                    text = words,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        if (isSelected) {
            Icon(Icons.Default.CheckCircle, contentDescription = "Selected", tint = MaterialTheme.colorScheme.primary)
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
