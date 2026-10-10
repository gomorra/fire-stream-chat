package com.firestream.chat.ui.chat.picker

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyGridScope
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Public
import androidx.compose.material.icons.filled.StarBorder
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.firestream.chat.domain.model.OnlineMediaKind
import com.firestream.chat.domain.model.Sticker
import com.firestream.chat.domain.model.StickerPack
import com.firestream.chat.domain.model.StickerPackKind
import com.firestream.chat.domain.util.StickerSearch
import com.firestream.chat.ui.chat.gif.OnlineMediaUiState
import com.firestream.chat.ui.stickers.EmptyHint
import com.firestream.chat.ui.components.LibraryStickerImage
import com.firestream.chat.ui.stickers.StickerCell
import com.firestream.chat.ui.stickers.label

/**
 * One entry of the pack row: Recents, the favourites or a pack.
 *
 * [packId] is what a pick from this shelf is sent with. Recents has none,
 * because a recent sticker no longer says which pack it came from.
 */
internal data class StickerShelf(
    val key: String,
    val label: String,
    val packId: String?,
    val kind: StickerPackKind?,
    val stickers: List<Sticker>,
)

internal const val RECENTS_SHELF_KEY = "recents"

/** The key of the **+** that ends the pack row. No pack has it: a pack's key is its id, a UUID. */
private const val CREATE_KEY = "create"

/** The key of the **Online** entry, KLIPY's stickers. It sits after the packs and before the **+**. */
private const val ONLINE_KEY = "online"
private const val ONLINE_LABEL = "Online"
private const val MORE_ONLINE_LABEL = "More online"

/**
 * The pack row's entries: Recents, the favourites, then the packs in the user's
 * order. A shelf with no stickers is left out, so nothing in the row opens an
 * empty grid.
 */
internal fun stickerShelves(packs: List<StickerPack>, recents: List<Sticker>): List<StickerShelf> = buildList {
    if (recents.isNotEmpty()) add(StickerShelf(RECENTS_SHELF_KEY, "Recents", null, null, recents))
    val (favourites, others) = packs.partition { it.kind == StickerPackKind.FAVOURITES }
    (favourites + others).forEach { pack ->
        if (pack.stickers.isNotEmpty()) add(StickerShelf(pack.id, pack.label(), pack.id, pack.kind, pack.stickers))
    }
}

/**
 * The user's sticker library as a picker tab: a row of packs, then the active
 * pack's grid. A tap sends the sticker and a long press flips its favourite.
 *
 * Thumbnails are still. A grid of animated stickers would run one decoder per
 * cell.
 *
 * A search runs across every pack. [query] becomes emojis through the emoji
 * keyword table, and the stickers tagged with them are the results.
 *
 * When [online] is available, the pack row has an **Online** entry with
 * KLIPY's stickers, and a search shows KLIPY's results in a *More online*
 * section under the library's. The two are never mixed. A tap on an online
 * sticker sends it, and a long press does nothing, because it cannot be kept.
 *
 * @param recents the stickers sent most recently, newest first
 * @param onImport opens the sticker library screen, offered while the library is empty
 * @param onCreate opens the sticker maker, from the **+** that ends the pack row
 */
@Composable
internal fun StickerLibraryTab(
    query: String,
    packs: List<StickerPack>,
    recents: List<Sticker>,
    onSelection: (PickerSelection.Sticker) -> Unit,
    onToggleFavourite: (String) -> Unit,
    onImport: () -> Unit,
    onCreate: () -> Unit,
    modifier: Modifier = Modifier,
    online: OnlineMediaUiState = OnlineMediaUiState(),
    onlineCallbacks: OnlineMediaCallbacks = OnlineMediaCallbacks(),
) {
    // Every send moves a sticker to the front of Recents. The shelf holds its
    // order while the panel is open and only follows when a sticker joins it
    // (docs/GOTCHAS.md, "Freeze list order in the presentation layer").
    val recentIds = remember(recents) { recents.mapTo(HashSet()) { it.id } }
    val frozenRecents = remember(recentIds) { recents }
    val shelves = remember(packs, frozenRecents) { stickerShelves(packs, frozenRecents) }

    if (shelves.isEmpty() && !online.isAvailable) {
        EmptyLibrary(onImport = onImport, onCreate = onCreate, modifier = modifier)
        return
    }

    // Pinned to the shelf the tab opened on, so a Recents shelf that a first send
    // adds in front does not take the grid away. Null only for an empty library.
    var activeKey by rememberSaveable { mutableStateOf(shelves.firstOrNull()?.key) }
    val onlineActive = online.isAvailable && activeKey == ONLINE_KEY
    val active = shelves.firstOrNull { it.key == activeKey } ?: shelves.firstOrNull()
    val searching = query.isNotBlank()
    val matches = remember(query, packs) {
        if (searching) StickerSearch.byEmojis(packs, EmojiSearchData.searchEmojis(query) + query) else emptyList()
    }
    val stickers = remember(matches, searching, active) {
        if (searching) matches.map { it.sticker } else active?.stickers.orEmpty()
    }
    // One function for every cell, so a cell is skipped when the grid recomposes.
    val onPick: (String) -> Unit = remember(matches, searching, active, onSelection) {
        val foundIn = matches.associate { it.sticker.id to it.packId }
        val pick: (String) -> Unit = { id ->
            onSelection(PickerSelection.Sticker(id, if (searching) foundIn[id] else active?.packId))
        }
        pick
    }

    // KLIPY's stickers show on the Online shelf and under a search of the library.
    val showOnline = online.isAvailable && (searching || onlineActive)
    val showLocal = searching || !onlineActive
    if (showOnline) {
        LaunchedEffect(query) { onlineCallbacks.onQuery(OnlineMediaKind.STICKER, query) }
    }

    Column(modifier = modifier.fillMaxSize()) {
        // A search runs across every pack, so the row that picks one pack steps aside.
        if (!searching) {
            LazyRow(
                contentPadding = PaddingValues(horizontal = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(2.dp),
                modifier = Modifier.fillMaxWidth().semantics { contentDescription = "Sticker packs" },
            ) {
                items(shelves, key = { it.key }) { shelf ->
                    ShelfButton(
                        label = shelf.label,
                        selected = !onlineActive && shelf.key == active?.key,
                        onClick = { activeKey = shelf.key },
                    ) { tint ->
                        when {
                            shelf.key == RECENTS_SHELF_KEY -> Icon(Icons.Default.History, null, Modifier.size(22.dp), tint)
                            shelf.kind == StickerPackKind.FAVOURITES ->
                                Icon(Icons.Default.StarBorder, null, Modifier.size(22.dp), tint)
                            else -> LibraryStickerImage(sticker = shelf.stickers.first(), modifier = Modifier.size(26.dp))
                        }
                    }
                }
                if (online.isAvailable) {
                    item(key = ONLINE_KEY) {
                        ShelfButton(label = ONLINE_LABEL, selected = onlineActive, onClick = { activeKey = ONLINE_KEY }) { tint ->
                            Icon(Icons.Default.Public, null, Modifier.size(22.dp), tint)
                        }
                    }
                }
                item(key = CREATE_KEY) {
                    IconButton(onClick = onCreate, modifier = Modifier.size(48.dp)) {
                        Icon(
                            Icons.Default.Add,
                            contentDescription = "Make a sticker",
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.size(22.dp),
                        )
                    }
                }
            }
        }

        when {
            // An empty library that has the Online shelf: the row above, and the way to fill the library.
            active == null && !searching && !onlineActive -> EmptyLibrary(onImport = onImport, onCreate = onCreate)

            stickers.isEmpty() && !showOnline -> Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                NoStickersFor(query)
            }

            else -> LazyVerticalGrid(
                columns = GridCells.Fixed(4),
                contentPadding = PaddingValues(start = 8.dp, end = 8.dp, bottom = 8.dp),
                modifier = Modifier.fillMaxSize(),
            ) {
                if (showLocal) {
                    item(key = "title", span = { GridItemSpan(maxLineSpan) }) {
                        if (stickers.isEmpty()) {
                            NoStickersFor(query)
                        } else {
                            ShelfTitle(if (searching) "Results" else "${active?.label} · ${stickers.size}")
                        }
                    }
                    items(stickers, key = { it.id }) { sticker ->
                        StickerCell(
                            sticker = sticker,
                            isSelected = false,
                            onClick = onPick,
                            onLongClick = onToggleFavourite,
                        )
                    }
                }
                // KLIPY's results stay in a section of their own, under the library's.
                if (showOnline) {
                    onlineStickers(
                        title = if (searching) MORE_ONLINE_LABEL else ONLINE_LABEL,
                        online = online,
                        callbacks = onlineCallbacks,
                    )
                }
            }
        }
    }
}

/**
 * KLIPY's stickers as a section of the grid: a title with KLIPY's mark, then
 * the first-use notice or the results in KLIPY's order.
 */
private fun LazyGridScope.onlineStickers(title: String, online: OnlineMediaUiState, callbacks: OnlineMediaCallbacks) {
    item(key = "online-title", span = { GridItemSpan(maxLineSpan) }) {
        Row(verticalAlignment = Alignment.Bottom) {
            ShelfTitle(title, modifier = Modifier.weight(1f))
            PoweredByKlipy(modifier = Modifier.padding(end = 8.dp))
        }
    }
    when (online.noticeAccepted) {
        null -> Unit
        false -> item(key = "online-notice", span = { GridItemSpan(maxLineSpan) }) {
            OnlineMediaNotice(onAccept = callbacks.onAcceptNotice)
        }
        true -> {
            // A library sticker's key is its id, 64 hex digits. A slug behind this prefix is never one.
            items(online.stickers.items, key = { onlineMediaTag(it.key) }) { media ->
                OnlineStickerCell(media = media, onPick = callbacks.onPick)
            }
            item(key = "online-footer", span = { GridItemSpan(maxLineSpan) }) {
                OnlineMediaFooter(kind = OnlineMediaKind.STICKER, feed = online.stickers, callbacks = callbacks)
            }
        }
    }
}

@Composable
private fun ShelfTitle(text: String, modifier: Modifier = Modifier) {
    Text(
        text = text,
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = modifier.padding(start = 8.dp, top = 4.dp),
    )
}

@Composable
private fun NoStickersFor(query: String) {
    Text(
        text = "No stickers for “${query.trim()}”",
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(16.dp),
    )
}

@Composable
private fun EmptyLibrary(onImport: () -> Unit, onCreate: () -> Unit, modifier: Modifier = Modifier) {
    Column(
        modifier = modifier.fillMaxSize(),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        EmptyHint(title = "No stickers yet", body = "Bring them over from WhatsApp or from files, or make one from a photo.")
        Button(onClick = onImport, modifier = Modifier.padding(top = 12.dp)) {
            Text("Import stickers")
        }
        TextButton(onClick = onCreate) {
            Text("Make a sticker")
        }
    }
}

/**
 * One 48 dp entry of the pack row. [icon] draws what stands for it, in the tint
 * it is handed: a pack shows its first sticker, and Recents, the favourites
 * and Online an icon.
 */
@Composable
private fun ShelfButton(label: String, selected: Boolean, onClick: () -> Unit, icon: @Composable (tint: Color) -> Unit) {
    Box(
        modifier = Modifier
            .size(48.dp)
            .clickable(onClickLabel = "Open pack", onClick = onClick)
            .semantics { contentDescription = label },
        contentAlignment = Alignment.Center,
    ) {
        Box(
            modifier = Modifier
                .size(38.dp)
                .background(
                    color = if (selected) MaterialTheme.colorScheme.primaryContainer else Color.Transparent,
                    shape = MaterialTheme.shapes.medium,
                ),
            contentAlignment = Alignment.Center,
        ) {
            val tint = if (selected) {
                MaterialTheme.colorScheme.onPrimaryContainer
            } else {
                MaterialTheme.colorScheme.onSurfaceVariant
            }
            icon(tint)
        }
    }
}
