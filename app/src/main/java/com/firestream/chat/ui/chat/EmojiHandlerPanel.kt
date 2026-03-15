package com.firestream.chat.ui.chat

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Backspace
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.launch

private const val GRID_COLUMNS = 8
private const val RECENTS_ICON = "⏱"

enum class EmojiMode { TEXT_INPUT, REACTION }

// ---------------------------------------------------------------------------
// Grid item model for the flat LazyVerticalGrid
// ---------------------------------------------------------------------------

private sealed class GridItem {
    data class Header(val icon: String, val title: String, val categoryIndex: Int) : GridItem()
    data class Emoji(val emoji: String) : GridItem()
    data object EmptySlot : GridItem()
}

// ---------------------------------------------------------------------------
// Category data (mirrors EmojiPicker.kt — old file deleted in Step 8)
// ---------------------------------------------------------------------------

private data class PanelCategory(val icon: String, val label: String, val emojis: List<String>)

private val PANEL_CATEGORIES = listOf(
    PanelCategory("😀", "Smileys", listOf(
        "😀","😃","😄","😁","😆","😅","🤣","😂","🙂","🙃",
        "😉","😊","😇","🥰","😍","🤩","😘","😗","😚","😙",
        "🥲","😋","😛","😜","🤪","😝","🤑","🤗","🤭","🫢",
        "🤫","🤔","🫡","🤐","🤨","😐","😑","😶","🫥","😏",
        "😒","🙄","😬","🤥","😌","😔","😪","🤤","😴","😷"
    )),
    PanelCategory("👋", "People", listOf(
        "👋","🤚","🖐️","✋","🖖","🫱","🫲","👌","🤌","🤏",
        "✌️","🤞","🫰","🤟","🤘","🤙","👈","👉","👆","🖕",
        "👇","☝️","🫵","👍","👎","✊","👊","🤛","🤜","👏",
        "🙌","🫶","👐","🤲","🤝","🙏","💪","🦾","🦿","🦵"
    )),
    PanelCategory("🐶", "Animals", listOf(
        "🐶","🐱","🐭","🐹","🐰","🦊","🐻","🐼","🐻‍❄️","🐨",
        "🐯","🦁","🐮","🐷","🐸","🐵","🙈","🙉","🙊","🐒",
        "🐔","🐧","🐦","🐤","🐣","🐥","🦆","🦅","🦉","🦇",
        "🐺","🐗","🐴","🦄","🐝","🪱","🐛","🦋","🐌","🐞"
    )),
    PanelCategory("🍎", "Food", listOf(
        "🍎","🍐","🍊","🍋","🍌","🍉","🍇","🍓","🫐","🍈",
        "🍒","🍑","🥭","🍍","🥥","🥝","🍅","🥑","🍆","🥔",
        "🥕","🌽","🌶️","🫑","🥒","🥬","🥦","🧄","🧅","🍄",
        "🥜","🫘","🌰","🍞","🥐","🥖","🫓","🥨","🥯","🥞"
    )),
    PanelCategory("⚽", "Activities", listOf(
        "⚽","🏀","🏈","⚾","🥎","🎾","🏐","🏉","🥏","🎱",
        "🪀","🏓","🏸","🏒","🏑","🥍","🏏","🪃","🥅","⛳",
        "🪁","🏹","🎣","🤿","🥊","🥋","🎽","🛹","🛼","🛷",
        "⛸️","🥌","🎿","⛷️","🏂","🪂","🏋️","🤸","🤼","🤺"
    )),
    PanelCategory("❤️", "Symbols", listOf(
        "❤️","🧡","💛","💚","💙","💜","🖤","🤍","🤎","💔",
        "❤️‍🔥","❤️‍🩹","💕","💞","💓","💗","💖","💘","💝","💟",
        "☮️","✝️","☪️","🕉️","☸️","✡️","🔯","🕎","☯️","☦️",
        "♈","♉","♊","♋","♌","♍","♎","♏","♐","♑"
    )),
    PanelCategory("🚗", "Travel", listOf(
        "🚗","🚕","🚙","🚌","🚎","🏎️","🚓","🚑","🚒","🚐",
        "🛻","🚚","🚛","🚜","🏍️","🛵","🚲","🛴","🛺","🚔",
        "🚍","🚘","🚖","🛞","🚡","🚠","🚟","🚃","🚋","🚞",
        "🚝","🚄","🚅","🚈","🚂","🚆","🚇","🚊","🚉","✈️"
    )),
    PanelCategory("💡", "Objects", listOf(
        "💡","🔦","🕯️","🪔","📱","💻","⌨️","🖥️","🖨️","🖱️",
        "🖲️","💾","💿","📀","📼","📷","📸","📹","🎥","📽️",
        "🎬","📺","📻","🎙️","🎚️","🎛️","🧭","⏱️","⏲️","⏰",
        "🔔","🔕","📢","📣","🔉","🔊","📯","🔇","🔈","🎵"
    )),
    PanelCategory("🔞", "Special", listOf(
        "🖕","💀","☠️","👿","😈","🤬","😡","😤","🤮","🤢",
        "💩","👻","👹","👺","🤡","🎃","⚰️","🪦","🩸","🦴",
        "😼","😾","😰","😨","😱","🥶","🥵","🤯","😳","🫣",
        "😶‍🌫️","🥴","😵‍💫","🫠","😏","💋","🫦","🍑","🍆","😒",
        "🙄","🤥","🤫","🍺","🍻","🥃","🍷","🥂","🍾","🚬",
        "🎲","🃏","♠️","♥️","♦️","♣️","🎰","🔞","⚠️","💥",
        "💣","🔥","⚡","☢️","☣️","💊","💉","🗡️"
    ))
)

// Toolbar icons: Recents + all categories
private data class ToolbarEntry(val icon: String, val categoryIndex: Int)

private val TOOLBAR_ENTRIES: List<ToolbarEntry> = buildList {
    add(ToolbarEntry(RECENTS_ICON, 0))
    PANEL_CATEGORIES.forEachIndexed { i, cat -> add(ToolbarEntry(cat.icon, i + 1)) }
}

// Quick reactions for reaction mode
private val PANEL_QUICK_REACTIONS = listOf("👍", "❤️", "😂", "😮", "😢", "🙏")

// Pre-built static category portion (never changes — only Recents section varies)
private val STATIC_CATEGORY_GRID: List<GridItem> = buildList {
    PANEL_CATEGORIES.forEachIndexed { index, category ->
        add(GridItem.Header(category.icon, category.label, categoryIndex = index + 1))
        category.emojis.forEach { add(GridItem.Emoji(it)) }
    }
}

// ===========================================================================
// Main composable
// ===========================================================================

@Composable
internal fun EmojiHandlerPanel(
    mode: EmojiMode,
    currentReaction: String? = null,
    recentEmojis: List<String>,
    onEmojiSelected: (emoji: String, size: Float) -> Unit,
    onBackspace: () -> Unit = {},
    onRecentUsed: (String) -> Unit = {},
    modifier: Modifier = Modifier
) {
    var searchQuery by remember { mutableStateOf("") }
    val isSearching = searchQuery.isNotBlank()

    val gridItems = remember(recentEmojis, searchQuery) {
        if (isSearching) buildSearchResults(searchQuery) else buildCategoryGrid(recentEmojis)
    }

    // Map (categoryIndex → gridItemIndex) for scroll-to-category and active-category tracking
    val categoryHeaderIndices = remember(gridItems) {
        gridItems.mapIndexedNotNull { idx, item ->
            (item as? GridItem.Header)?.let { it.categoryIndex to idx }
        }
    }

    val gridState = rememberLazyGridState()
    val scope = rememberCoroutineScope()

    // Re-keyed on categoryHeaderIndices so derivedStateOf captures the updated indices
    // after grid changes (e.g. search toggle) — plain List is not Compose State, so without
    // the key the lambda would silently capture the stale list from the previous composition.
    val activeCategoryIndex by remember(categoryHeaderIndices) {
        derivedStateOf {
            val firstVisible = gridState.firstVisibleItemIndex
            categoryHeaderIndices.lastOrNull { (_, gridIdx) -> gridIdx <= firstVisible }?.first ?: 0
        }
    }

    Column(modifier = modifier.background(MaterialTheme.colorScheme.surface)) {
        // Quick reactions (REACTION mode only)
        if (mode == EmojiMode.REACTION) {
            QuickReactionsRow(
                currentReaction = currentReaction,
                onSelect = { emoji ->
                    onEmojiSelected(emoji, 1f)
                    onRecentUsed(emoji)
                }
            )
            HorizontalDivider(thickness = 0.5.dp)
        }

        // Top toolbar: search + backspace
        SearchToolbar(
            query = searchQuery,
            onQueryChange = { searchQuery = it },
            mode = mode,
            onBackspace = onBackspace
        )
        HorizontalDivider(thickness = 0.5.dp)

        // Scrollable emoji grid (or "no results" placeholder)
        val hasEmojiResults = remember(gridItems) { gridItems.any { it is GridItem.Emoji } }
        if (isSearching && !hasEmojiResults) {
            Box(
                modifier = Modifier.weight(1f).fillMaxWidth(),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = "No emoji found",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        } else {
            LazyVerticalGrid(
                columns = GridCells.Fixed(GRID_COLUMNS),
                state = gridState,
                modifier = Modifier.weight(1f).fillMaxWidth(),
                contentPadding = PaddingValues(horizontal = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(2.dp),
                verticalArrangement = Arrangement.spacedBy(2.dp)
            ) {
                items(
                    count = gridItems.size,
                    span = { index ->
                        GridItemSpan(if (gridItems[index] is GridItem.Header) GRID_COLUMNS else 1)
                    }
                ) { index ->
                    when (val item = gridItems[index]) {
                        is GridItem.Header -> CategoryHeader(item.icon, item.title)
                        is GridItem.Emoji -> EmojiCell(item.emoji) {
                            onEmojiSelected(item.emoji, 1f)
                            onRecentUsed(item.emoji)
                        }
                        is GridItem.EmptySlot -> EmptySlotCell()
                    }
                }
            }
        }

        HorizontalDivider(thickness = 0.5.dp)

        // Bottom category toolbar (hidden during search)
        if (!isSearching) {
            CategoryToolbar(
                activeCategoryIndex = activeCategoryIndex,
                onCategoryClick = { catIdx ->
                    val target = categoryHeaderIndices
                        .firstOrNull { it.first == catIdx }?.second ?: return@CategoryToolbar
                    scope.launch { gridState.scrollToItem(target) }
                }
            )
        }
    }
}

// ===========================================================================
// Quick Reactions Row
// ===========================================================================

@Composable
private fun QuickReactionsRow(
    currentReaction: String?,
    onSelect: (String) -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 8.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.SpaceEvenly
    ) {
        PANEL_QUICK_REACTIONS.forEach { emoji ->
            SelectableEmojiBox(
                icon = emoji,
                isActive = currentReaction == emoji,
                onClick = { onSelect(emoji) },
                modifier = Modifier.size(48.dp),
                shape = CircleShape,
                fontSize = 24.sp
            )
        }
    }
}

// ===========================================================================
// Search Toolbar
// ===========================================================================

@Composable
private fun SearchToolbar(
    query: String,
    onQueryChange: (String) -> Unit,
    mode: EmojiMode,
    onBackspace: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 8.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        // Search box
        Row(
            modifier = Modifier
                .weight(1f)
                .background(MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(16.dp))
                .padding(horizontal = 10.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                imageVector = Icons.Default.Search,
                contentDescription = null,
                modifier = Modifier.size(18.dp),
                tint = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(modifier = Modifier.width(6.dp))
            Box(modifier = Modifier.weight(1f)) {
                if (query.isEmpty()) {
                    Text(
                        text = "Search emoji\u2026",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f)
                    )
                }
                BasicTextField(
                    value = query,
                    onValueChange = onQueryChange,
                    singleLine = true,
                    textStyle = MaterialTheme.typography.bodySmall.copy(
                        color = MaterialTheme.colorScheme.onSurface
                    ),
                    cursorBrush = SolidColor(MaterialTheme.colorScheme.primary)
                )
            }
            if (query.isNotEmpty()) {
                Icon(
                    imageVector = Icons.Default.Close,
                    contentDescription = "Clear search",
                    modifier = Modifier
                        .size(18.dp)
                        .clickable { onQueryChange("") },
                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }

        // Backspace button (TEXT_INPUT mode only)
        if (mode == EmojiMode.TEXT_INPUT) {
            IconButton(
                onClick = onBackspace,
                modifier = Modifier.size(36.dp)
            ) {
                Icon(
                    imageVector = Icons.AutoMirrored.Filled.Backspace,
                    contentDescription = "Backspace",
                    modifier = Modifier.size(20.dp),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

// ===========================================================================
// Grid cells
// ===========================================================================

@Composable
private fun CategoryHeader(icon: String, title: String) {
    Text(
        text = "$icon $title",
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(top = 8.dp, bottom = 4.dp)
    )
}

@Composable
private fun EmojiCell(emoji: String, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .aspectRatio(1f)
            .clip(RoundedCornerShape(4.dp))
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        Text(text = emoji, fontSize = 22.sp)
    }
}

@Composable
private fun EmptySlotCell() {
    Box(
        modifier = Modifier.aspectRatio(1f),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = "\u00B7",
            fontSize = 14.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.3f)
        )
    }
}

// ===========================================================================
// Shared selectable emoji/icon box (used by QuickReactionsRow + CategoryToolbar)
// ===========================================================================

@Composable
private fun SelectableEmojiBox(
    icon: String,
    isActive: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    shape: Shape = RoundedCornerShape(6.dp),
    fontSize: TextUnit = 16.sp,
    activeTextColor: Color = Color.Unspecified,
    inactiveTextColor: Color = Color.Unspecified
) {
    Box(
        modifier = modifier
            .clip(shape)
            .background(if (isActive) MaterialTheme.colorScheme.primaryContainer else Color.Transparent)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = icon,
            fontSize = fontSize,
            color = if (isActive) activeTextColor else inactiveTextColor
        )
    }
}

// ===========================================================================
// Bottom Category Toolbar
// ===========================================================================

@Composable
private fun CategoryToolbar(
    activeCategoryIndex: Int,
    onCategoryClick: (Int) -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 4.dp, vertical = 4.dp),
        horizontalArrangement = Arrangement.SpaceEvenly
    ) {
        TOOLBAR_ENTRIES.forEach { entry ->
            SelectableEmojiBox(
                icon = entry.icon,
                isActive = entry.categoryIndex == activeCategoryIndex,
                onClick = { onCategoryClick(entry.categoryIndex) },
                modifier = Modifier.size(32.dp),
                activeTextColor = MaterialTheme.colorScheme.onPrimaryContainer,
                inactiveTextColor = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f)
            )
        }
    }
}

// ===========================================================================
// Grid builders
// ===========================================================================

private fun buildCategoryGrid(recentEmojis: List<String>): List<GridItem> = buildList {
    // Recents section
    add(GridItem.Header(RECENTS_ICON, "Recents", categoryIndex = 0))
    recentEmojis.forEach { add(GridItem.Emoji(it)) }
    // Pad to fill the row (at least one row of empty dots when empty)
    val recentCount = recentEmojis.size
    val nextFullRow = if (recentCount == 0) GRID_COLUMNS
        else ((recentCount + GRID_COLUMNS - 1) / GRID_COLUMNS) * GRID_COLUMNS
    repeat(nextFullRow - recentCount) { add(GridItem.EmptySlot) }
    // Static category items (pre-built at file load)
    addAll(STATIC_CATEGORY_GRID)
}

private fun buildSearchResults(query: String): List<GridItem> = buildList {
    add(GridItem.Header("\uD83D\uDD0D", "Results for \"$query\"", categoryIndex = -1))
    EmojiSearchData.searchEmojis(query).forEach { add(GridItem.Emoji(it)) }
}
