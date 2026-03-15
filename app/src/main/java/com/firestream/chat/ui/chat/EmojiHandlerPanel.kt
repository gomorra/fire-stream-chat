package com.firestream.chat.ui.chat

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.wrapContentSize
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
import androidx.compose.material3.Surface
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
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInParent
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

private const val GRID_COLUMNS = 8
private const val RECENTS_ICON = "⏱"
private const val SIZE_MIN = 0.8f
private const val SIZE_MAX = 5.0f
private const val SIZE_DEFAULT = 1.0f

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
// Category data
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
// Long-press size picker state
// ===========================================================================

private data class SizePickerState(
    val gridItemIndex: Int,      // index in gridItems list
    val emoji: String,
    val columnIndex: Int,        // 0-7 within the row
    val cellOffset: Offset,      // position of the pressed cell relative to grid
    val sizeMultiplier: Float = SIZE_DEFAULT
)

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

    val categoryHeaderIndices = remember(gridItems) {
        gridItems.mapIndexedNotNull { idx, item ->
            (item as? GridItem.Header)?.let { it.categoryIndex to idx }
        }
    }

    val gridState = rememberLazyGridState()
    val scope = rememberCoroutineScope()

    val activeCategoryIndex by remember(categoryHeaderIndices) {
        derivedStateOf {
            val firstVisible = gridState.firstVisibleItemIndex
            categoryHeaderIndices.lastOrNull { (_, gridIdx) -> gridIdx <= firstVisible }?.first ?: 0
        }
    }

    // Long-press size picker state — null means picker is hidden
    var sizePicker by remember { mutableStateOf<SizePickerState?>(null) }

    // Track cell positions so the size slider can anchor to the pressed cell
    val cellPositions = remember { mutableMapOf<Int, Offset>() }

    Column(modifier = modifier.background(MaterialTheme.colorScheme.surface)) {
        if (mode == EmojiMode.REACTION) {
            QuickReactionsRow(
                currentReaction = currentReaction,
                onSelect = { emoji ->
                    onEmojiSelected(emoji, SIZE_DEFAULT)
                    onRecentUsed(emoji)
                }
            )
            HorizontalDivider(thickness = 0.5.dp)
        }

        SearchToolbar(
            query = searchQuery,
            onQueryChange = { searchQuery = it },
            mode = mode,
            onBackspace = onBackspace
        )
        HorizontalDivider(thickness = 0.5.dp)

        val hasEmojiResults = remember(gridItems) { gridItems.any { it is GridItem.Emoji } }
        Box(modifier = Modifier.weight(1f).fillMaxWidth()) {
            if (isSearching && !hasEmojiResults) {
                Box(modifier = Modifier.fillMaxWidth().align(Alignment.Center)) {
                    Text(
                        text = "No emoji found",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.align(Alignment.Center).padding(16.dp)
                    )
                }
            } else {
                LazyVerticalGrid(
                    columns = GridCells.Fixed(GRID_COLUMNS),
                    state = gridState,
                    modifier = Modifier.fillMaxWidth(),
                    contentPadding = PaddingValues(horizontal = 8.dp),
                    horizontalArrangement = Arrangement.spacedBy(2.dp),
                    verticalArrangement = Arrangement.spacedBy(2.dp),
                    userScrollEnabled = sizePicker == null  // lock scroll during size pick
                ) {
                    items(
                        count = gridItems.size,
                        span = { index ->
                            GridItemSpan(if (gridItems[index] is GridItem.Header) GRID_COLUMNS else 1)
                        }
                    ) { index ->
                        when (val item = gridItems[index]) {
                            is GridItem.Header -> CategoryHeader(item.icon, item.title)
                            is GridItem.Emoji -> {
                                val sp = sizePicker
                                // Siblings in the same row as the held cell fade out
                                val heldRow = sp?.let { sp.gridItemIndex / GRID_COLUMNS }
                                val thisRow = index / GRID_COLUMNS
                                val isSiblingFaded = sp != null &&
                                    thisRow == heldRow &&
                                    index != sp.gridItemIndex
                                val targetAlpha = if (isSiblingFaded) 0f else 1f
                                val alpha by animateFloatAsState(
                                    targetValue = targetAlpha,
                                    animationSpec = tween(150),
                                    label = "sibling_alpha"
                                )
                                EmojiCell(
                                    emoji = item.emoji,
                                    fontSize = 22.sp,
                                    modifier = Modifier
                                        .alpha(alpha)
                                        .onGloballyPositioned { coords ->
                                            cellPositions[index] = coords.positionInParent()
                                        }
                                        .pointerInput(item.emoji) {
                                            detectDragGesturesAfterLongPress(
                                                onDragStart = { _ ->
                                                    val pos = cellPositions[index] ?: Offset.Zero
                                                    val colIdx = index % GRID_COLUMNS
                                                    sizePicker = SizePickerState(
                                                        gridItemIndex = index,
                                                        emoji = item.emoji,
                                                        columnIndex = colIdx,
                                                        cellOffset = pos
                                                    )
                                                },
                                                onDrag = { _, dragAmount ->
                                                    val sp2 = sizePicker ?: return@detectDragGesturesAfterLongPress
                                                    // Drag up (negative y) → larger; drag down → smaller
                                                    val delta = -dragAmount.y / 200f
                                                    val newSize = (sp2.sizeMultiplier + delta)
                                                        .coerceIn(SIZE_MIN, SIZE_MAX)
                                                    // Skip write when already at bounds (avoids recomposition churn)
                                                    if (newSize != sp2.sizeMultiplier) {
                                                        sizePicker = sp2.copy(sizeMultiplier = newSize)
                                                    }
                                                },
                                                onDragEnd = {
                                                    val sp2 = sizePicker ?: return@detectDragGesturesAfterLongPress
                                                    onEmojiSelected(sp2.emoji, sp2.sizeMultiplier)
                                                    onRecentUsed(sp2.emoji)
                                                    sizePicker = null
                                                },
                                                onDragCancel = {
                                                    sizePicker = null
                                                }
                                            )
                                        },
                                    onClick = {
                                        onEmojiSelected(item.emoji, SIZE_DEFAULT)
                                        onRecentUsed(item.emoji)
                                    }
                                )
                            }
                            is GridItem.EmptySlot -> EmptySlotCell()
                        }
                    }
                }

                // Size picker overlay — anchored to the held cell
                sizePicker?.let { sp ->
                    SizePickerOverlay(
                        emoji = sp.emoji,
                        sizeMultiplier = sp.sizeMultiplier,
                        anchorOffset = sp.cellOffset,
                        showOnRight = sp.columnIndex < GRID_COLUMNS - 1
                    )
                }
            }
        }

        HorizontalDivider(thickness = 0.5.dp)

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
// Size picker overlay
// ===========================================================================

@Composable
private fun SizePickerOverlay(
    emoji: String,
    sizeMultiplier: Float,
    anchorOffset: Offset,
    showOnRight: Boolean
) {
    val pct = ((sizeMultiplier - SIZE_MIN) / (SIZE_MAX - SIZE_MIN)).coerceIn(0f, 1f)
    val displaySize = (22 * sizeMultiplier).sp

    // Convert dp offsets to pixels using current density so the overlay positions
    // correctly across all screen densities.
    val density = androidx.compose.ui.platform.LocalDensity.current
    val xOffset = with(density) {
        if (showOnRight) (anchorOffset.x + 56.dp.toPx()).roundToInt()
        else (anchorOffset.x - 120.dp.toPx()).roundToInt()
    }
    val yOffset = with(density) { (anchorOffset.y - 100.dp.toPx()).roundToInt() }

    Box(
        modifier = Modifier
            .offset { IntOffset(xOffset, yOffset) }
            .wrapContentSize()
    ) {
        Surface(
            shape = RoundedCornerShape(12.dp),
            shadowElevation = 6.dp,
            color = MaterialTheme.colorScheme.surfaceContainer
        ) {
            Column(
                modifier = Modifier.padding(8.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                Text(text = emoji, fontSize = displaySize)
                Text(
                    text = "${(sizeMultiplier * 100).roundToInt()}%",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                androidx.compose.material3.LinearProgressIndicator(
                    progress = { pct },
                    modifier = Modifier.width(48.dp),
                    color = MaterialTheme.colorScheme.primary,
                    trackColor = MaterialTheme.colorScheme.surfaceVariant
                )
                Text(
                    text = "↑ drag ↓",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
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
private fun EmojiCell(
    emoji: String,
    fontSize: TextUnit = 22.sp,
    modifier: Modifier = Modifier,
    onClick: () -> Unit
) {
    Box(
        modifier = modifier
            .aspectRatio(1f)
            .clip(RoundedCornerShape(4.dp))
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        Text(text = emoji, fontSize = fontSize)
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
    add(GridItem.Header(RECENTS_ICON, "Recents", categoryIndex = 0))
    recentEmojis.forEach { add(GridItem.Emoji(it)) }
    val recentCount = recentEmojis.size
    val nextFullRow = if (recentCount == 0) GRID_COLUMNS
        else ((recentCount + GRID_COLUMNS - 1) / GRID_COLUMNS) * GRID_COLUMNS
    repeat(nextFullRow - recentCount) { add(GridItem.EmptySlot) }
    addAll(STATIC_CATEGORY_GRID)
}

private fun buildSearchResults(query: String): List<GridItem> = buildList {
    add(GridItem.Header("\uD83D\uDD0D", "Results for \"$query\"", categoryIndex = -1))
    EmojiSearchData.searchEmojis(query).forEach { add(GridItem.Emoji(it)) }
}
