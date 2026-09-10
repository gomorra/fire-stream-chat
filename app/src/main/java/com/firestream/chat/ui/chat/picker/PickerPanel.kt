package com.firestream.chat.ui.chat.picker

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.core.tween
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.outlined.Category
import androidx.compose.material.icons.outlined.EmojiEmotions
import androidx.compose.material.icons.outlined.Gif
import androidx.compose.material.icons.outlined.StickyNote2
import androidx.compose.material.icons.outlined.TextFields
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

/**
 * The picker's chrome: a search control, the island of tabs the host declared,
 * a delete button for the host's current selection, and whichever tab's content
 * is showing.
 *
 * ### One picker, four hosts
 *
 * The app had one emoji panel mounted from three places and was about to grow a
 * fourth for the image editor (`.claude/plans/image-editor.md` §2.8). This is
 * the shell those hosts share. It owns the chrome and the per-tab state and
 * **implements no content**: what a tab shows is [content]'s business, so a
 * tab's own parameters — recents, colours, a text style — stay with the host
 * that has them rather than accumulating here.
 *
 * ### The row has two states, and a one-tab host only ever sees one
 *
 * With more than one tab the row is a circular search button, the island, and
 * the delete button. Tapping search expands the field out of the button and
 * slides the island away; the field's own × brings it back — **the island has
 * no other way back**, so that × is not decoration (§4).
 *
 * With a single tab there is no island to slide away, so there is nothing for a
 * collapsed search button to buy: the field is simply always expanded, which is
 * exactly what the composer, the reaction sheet and the caption bar have always
 * shown. A one-tab host therefore renders no island, no search button and no
 * segment — it is indistinguishable from the panel that preceded this file.
 *
 * ### The query is per tab
 *
 * Emoji filters a local list, stickers filter local packs, and a GIF search
 * would be a debounced network query — three different things with three
 * different placeholders. One shared string would carry a query that means
 * nothing where it lands, so the field is keyed on the active tab: every switch
 * arrives at that tab's own empty field, asking that tab's own question.
 *
 * @param tabs the island's segments, in order; the first is the one it opens on
 * @param onDelete acts on the host's current selection, or null while there is
 *   nothing selected — the button is then **hidden, not greyed**, because a
 *   control that is always present and usually dead teaches people to ignore it
 * @param header drawn above the search row, full width — the reaction sheet's
 *   quick-reactions strip, and nothing else so far
 * @param searchTrailing drawn at the right end of the search row, after the
 *   delete button — the composer's backspace key
 * @param content the active tab and the query it is filtered by
 */
@Composable
internal fun PickerPanel(
    tabs: List<PickerTab>,
    modifier: Modifier = Modifier,
    onDelete: (() -> Unit)? = null,
    header: @Composable ColumnScope.(PickerTab) -> Unit = {},
    searchTrailing: @Composable RowScope.(PickerTab) -> Unit = {},
    content: @Composable (PickerTab, String) -> Unit,
) {
    val declared = tabs.ifEmpty { listOf(PickerTab.EMOJI) }
    var activeName by rememberSaveable(declared) { mutableStateOf(declared.first().name) }
    val active = declared.firstOrNull { it.name == activeName } ?: declared.first()

    // Keyed on the active tab, which is the whole of "the query is per tab":
    // switching tabs lands on that tab's own empty field with that tab's own
    // placeholder, and a query typed on one can never follow you to another
    // where it would mean nothing (§2.8).
    var query by rememberSaveable(active) { mutableStateOf("") }

    // A single-tab host has no island to hide, so the field never collapses and
    // the button that would collapse it is never drawn.
    val hasIsland = declared.size > 1
    var searchOpen by rememberSaveable(declared) { mutableStateOf(false) }
    val fieldExpanded = !hasIsland || searchOpen

    // Back closes the search before anything else gets a say. The island slides
    // away when search opens and the field's × is otherwise the only way to
    // bring it back (§4), so a back press that skipped straight to closing the
    // panel would take the tab switcher with it. Disabled entirely for a
    // one-tab host, whose field never collapses and whose host owns back.
    BackHandler(enabled = hasIsland && searchOpen) {
        query = ""
        searchOpen = false
    }

    Column(modifier = modifier.background(MaterialTheme.colorScheme.surface)) {
        header(active)

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 8.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            if (hasIsland && !searchOpen) {
                SearchButton(onClick = { searchOpen = true })
            }

            if (fieldExpanded) {
                SearchField(
                    query = query,
                    hint = active.searchHint,
                    onQueryChange = { query = it },
                    // Only a collapsible field offers to collapse; the one-tab
                    // hosts keep the plain clear-the-text × they always had.
                    onCollapse = if (hasIsland) {
                        {
                            // Clears as well as collapses: a filter still
                            // running behind a field that is no longer on
                            // screen is a tab that looks broken.
                            query = ""
                            searchOpen = false
                        }
                    } else {
                        null
                    },
                    modifier = Modifier.weight(1f),
                )
            }

            AnimatedVisibility(
                visible = hasIsland && !searchOpen,
                enter = fadeIn(tween(120)) + slideInHorizontally(tween(120)) { it / 4 },
                exit = fadeOut(tween(120)) + slideOutHorizontally(tween(120)) { it / 4 },
            ) {
                TabIsland(
                    tabs = declared,
                    active = active,
                    onSelect = { activeName = it.name },
                )
            }

            // Stays put when search expands and slides the island away:
            // deletion belongs to the selection, not to the picker.
            if (onDelete != null) {
                IconButton(onClick = onDelete, modifier = Modifier.size(36.dp)) {
                    Icon(
                        imageVector = Icons.Default.Delete,
                        contentDescription = "Delete selected",
                        modifier = Modifier.size(20.dp),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            searchTrailing(active)
        }
        HorizontalDivider(thickness = 0.5.dp)

        Box(modifier = Modifier.weight(1f).fillMaxWidth()) {
            content(active, query)
        }
    }
}

/** The circular button the field expands out of; drawn only when there is an island to hide. */
@Composable
private fun SearchButton(onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .size(38.dp)
            .background(MaterialTheme.colorScheme.surfaceVariant, CircleShape)
            .clickable(onClickLabel = "Search", onClick = onClick)
            .semantics { contentDescription = "Search" },
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            imageVector = Icons.Default.Search,
            contentDescription = null,
            modifier = Modifier.size(18.dp),
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/**
 * The search field itself.
 *
 * [onCollapse] is what the trailing × does when there is an island waiting to
 * come back; without one the × keeps its older, smaller job of clearing the
 * text, and only appears while there is text to clear.
 */
@Composable
private fun SearchField(
    query: String,
    hint: String,
    onQueryChange: (String) -> Unit,
    onCollapse: (() -> Unit)?,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier
            .background(MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(16.dp))
            .padding(horizontal = 10.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            imageVector = Icons.Default.Search,
            contentDescription = null,
            modifier = Modifier.size(18.dp),
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(modifier = Modifier.width(6.dp))
        Box(modifier = Modifier.weight(1f)) {
            if (query.isEmpty()) {
                Text(
                    text = hint,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
                )
            }
            BasicTextField(
                value = query,
                onValueChange = onQueryChange,
                modifier = Modifier.semantics { contentDescription = "Search field" },
                singleLine = true,
                textStyle = MaterialTheme.typography.bodySmall.copy(
                    color = MaterialTheme.colorScheme.onSurface,
                ),
                cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
            )
        }
        if (onCollapse != null) {
            Icon(
                imageVector = Icons.Default.Close,
                contentDescription = "Close search",
                modifier = Modifier
                    .size(18.dp)
                    .clickable(onClick = onCollapse),
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        } else if (query.isNotEmpty()) {
            Icon(
                imageVector = Icons.Default.Close,
                contentDescription = "Clear search",
                modifier = Modifier
                    .size(18.dp)
                    .clickable { onQueryChange("") },
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/**
 * The segmented island of tabs.
 *
 * **Icon-only except the active segment, which keeps its label.** Four segments
 * plus the search and delete buttons do not fit a 390 dp row with every label
 * showing, and the alternative to dropping them was a row that scrolls — which
 * is worse in a mode switcher than in a preset list, because scrolling hides
 * the modes you are not in (§4). One label, on the segment whose meaning you
 * most need confirmed, is what fits and what reads.
 */
@Composable
private fun TabIsland(
    tabs: List<PickerTab>,
    active: PickerTab,
    onSelect: (PickerTab) -> Unit,
) {
    Row(
        modifier = Modifier
            .background(MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(16.dp))
            .padding(2.dp)
            .semantics { contentDescription = "Picker tabs" },
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        tabs.forEach { tab ->
            val isActive = tab == active
            Row(
                modifier = Modifier
                    .background(
                        color = if (isActive) MaterialTheme.colorScheme.primaryContainer else Color.Transparent,
                        shape = RoundedCornerShape(14.dp),
                    )
                    .clickable(onClick = { onSelect(tab) })
                    .semantics { contentDescription = tab.label }
                    .padding(horizontal = 8.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(
                    imageVector = tab.icon(),
                    contentDescription = null,
                    modifier = Modifier.size(15.dp),
                    tint = if (isActive) {
                        MaterialTheme.colorScheme.onPrimaryContainer
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    },
                )
                if (isActive) {
                    Spacer(modifier = Modifier.width(4.dp))
                    Text(
                        text = tab.label,
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.onPrimaryContainer,
                    )
                }
            }
        }
    }
}

/** The glyph a segment shows. Resolved here rather than stored on the enum, which keeps [PickerTab] free of Compose. */
private fun PickerTab.icon(): ImageVector = when (this) {
    PickerTab.EMOJI -> Icons.Outlined.EmojiEmotions
    PickerTab.STICKER -> Icons.Outlined.StickyNote2
    PickerTab.GIF -> Icons.Outlined.Gif
    PickerTab.TEXT -> Icons.Outlined.TextFields
    PickerTab.SHAPE -> Icons.Outlined.Category
}
